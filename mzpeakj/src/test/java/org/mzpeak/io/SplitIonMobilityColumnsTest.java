package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-24 (design §4.4, D-13): the split layout writes {@code ion_mobility_value} (DOUBLE) and {@code ion_mobility_type}
 * (STRING) in spectra_metadata_scans after {@code ion_injection_time} and in both selected-ion members after
 * {@code peak_intensity}, as the reference does. An ion mobility Param moves there only when the reader rebuilds it
 * equal apart from its name: one of the four types the reader names (MS:1002476, MS:1002815, MS:1001581, MS:1003371),
 * a finite Double or a Long, the unit the reader gives that type, and no other ion mobility type in the scope's
 * Params. Otherwise both cells are null and the Params stay as they are.
 *
 * <p>Oracles: current diaPASEF.ref (8 scans and 8 selected ions with MS:1002815) and small.unpacked (a chromatogram
 * given a precursor), and scans of diaPASEF.ref with one Param changed.
 */
class SplitIonMobilityColumnsTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final String SCANS = "spectra_metadata_scans.parquet";
    private static final String SELECTED_IONS = "spectra_metadata_selected_ions.parquet";
    private static final String CHROMATOGRAM_SELECTED_IONS = "chromatograms_metadata_selected_ions.parquet";
    private static final String VALUE = "ion_mobility_value";
    private static final String TYPE = "ion_mobility_type";
    private static final String INVERSE_K0 = "MS:1002815";

    @Test
    void diaPasefRefScansAndSelectedIonsWriteBothColumnsAndReadBackEqual(@TempDir Path tmp) {
        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        List<Param> scanMobility = new ArrayList<>();
        List<Param> ionMobility = new ArrayList<>();
        for (Spectrum s : dia.spectra) {
            for (ScanEvent scan : s.description().scans()) {
                scanMobility.add(only(scan.parameters()));
            }
            for (Precursor p : s.description().precursors()) {
                for (SelectedIon ion : p.selectedIons()) {
                    ionMobility.add(only(ion.parameters()));
                }
            }
        }
        assertThat(scanMobility).as("diaPASEF.ref scans").hasSize(9);
        assertThat(scanMobility.stream().filter(p -> p != null).count()).as("scans with MS:1002815").isEqualTo(8);
        assertThat(ionMobility.stream().filter(p -> p != null).count()).as("ions with MS:1002815").isEqualTo(8);

        Path out = write(dia.spectra, dia.chromatograms, dia, tmp.resolve("out.mzpeak"));

        assertColumnsAfter(schema(out, SCANS), "ion_injection_time");
        assertColumnsAfter(schema(out, SELECTED_IONS), "peak_intensity");
        assertColumnsAfter(schema(out, CHROMATOGRAM_SELECTED_IONS), "peak_intensity");
        assertCells(rows(out.resolve(SCANS)), scanMobility);
        assertCells(rows(out.resolve(SELECTED_IONS)), ionMobility);

        // The fixture's scans name MS:1002815 "inverse reduced ion mobility" in their parameters lists; a promoted
        // Param reads back with the name the reader gives its type (WR-5c: equal apart from its name), and after the
        // list, where the fixture has it. The selected ions read theirs from the fixture's columns, with the
        // reader's name already.
        String stored = "(inverse reduced ion mobility|" + INVERSE_K0 + "|";
        String named = "(inverse reduced ion mobility drift time|" + INVERSE_K0 + "|";
        List<String> expected = new ArrayList<>();
        int renamed = 0;
        for (String line : Canonical.lines(descriptions(dia.spectra), Canonical::describe)) {
            renamed += line.split(Pattern.quote(stored), -1).length - 1;
            expected.add(line.replace(stored, named));
        }
        assertThat(renamed).as("ion mobility Params of the fixture's scans").isEqualTo(8);
        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(Canonical.lines(descriptions(back.spectra), Canonical::describe)).as("diaPASEF.ref read back")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void aWrongUnitTwoTypesOrAnUnnamedTypeStayInTheParameters(@TempDir Path tmp) {
        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        List<UnaryOperator<List<Param>>> changes = List.of(
                // the unit of drift time, not the one the reader gives 1/K0
                params -> replace(params, p -> new Param(p.name(), p.accession(), p.value(), "UO:0000028")),
                // no unit
                params -> replace(params, p -> new Param(p.name(), p.accession(), p.value(), null)),
                // a second ion mobility type
                params -> append(params, new Param("ion mobility drift time", "MS:1002476", 12.5, "UO:0000028")),
                // the same type twice
                params -> append(params, only(params)),
                // a type the reader names "ion mobility value", without unit
                params -> replace(params, p -> new Param("SelexION separation voltage", "MS:1003394", 2.0,
                        "UO:0000218")),
                // a value that is not finite
                params -> replace(params, p -> new Param(p.name(), p.accession(), Double.NaN, p.unit())),
                // a value that is not a number
                params -> replace(params, p -> new Param(p.name(), p.accession(), "0.9", p.unit())));
        int i = 0;
        for (UnaryOperator<List<Param>> change : changes) {
            List<Spectrum> spectra = withScanParams(dia.spectra, 1, change);
            Path out = write(spectra, dia.chromatograms, dia, tmp.resolve("out" + i++ + ".mzpeak"));
            Group row = rows(out.resolve(SCANS)).get(1);
            String what = "change " + (i - 1);
            assertThat(ParquetGroups.has(row, VALUE)).as(what).isFalse();
            assertThat(ParquetGroups.has(row, TYPE)).as(what).isFalse();
            List<Param> expected = spectra.get(1).description().scans().get(0).parameters();
            assertThat(ParquetGroups.readParams(row, "parameters")).as(what).isEqualTo(expected);

            Source back = ForwardBackwardCorpusTest.readSource(out);
            assertThat(back.spectra.get(1).description().scans().get(0).parameters()).as(what)
                    .isEqualTo(expected);
        }
    }

    @Test
    void aLongValuePromotesAndReadsBackAsDouble(@TempDir Path tmp) {
        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        List<Spectrum> spectra = withScanParams(dia.spectra, 1,
                params -> replace(params, p -> new Param("FAIMS compensation voltage", "MS:1001581", 35L,
                        "UO:0000218")));
        Path out = write(spectra, dia.chromatograms, dia, tmp.resolve("out.mzpeak"));
        Group row = rows(out.resolve(SCANS)).get(1);
        assertThat(ParquetGroups.optDouble(row, VALUE)).isEqualTo(35.0);
        assertThat(ParquetGroups.optString(row, TYPE)).isEqualTo("MS:1001581");
        Param windowGroup = new Param("window group", null, 1L, null);
        assertThat(ParquetGroups.readParams(row, "parameters")).containsExactly(windowGroup);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(back.spectra.get(1).description().scans().get(0).parameters()).containsExactly(windowGroup,
                new Param("FAIMS compensation voltage", "MS:1001581", 35.0, "UO:0000218"));
    }

    /**
     * Only Params with an ion mobility type count against the promotion: a scan and a selected ion that also hold
     * another CV Param still move their ion mobility to the columns and keep the other Param in the list.
     */
    @Test
    void anotherCvParamInTheScopeDoesNotStopThePromotion(@TempDir Path tmp) {
        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        Param filter = new Param("filter string", "MS:1000512", "FTMS + p NSI Full ms", null);
        Param possibleCharge = new Param("possible charge state", "MS:1000633", 2L, null);
        List<Spectrum> spectra = withScanParams(dia.spectra, 1, params -> append(params, filter));
        int at = -1;
        for (int i = 0; i < spectra.size() && at < 0; i++) {
            List<Precursor> precursors = spectra.get(i).description().precursors();
            if (!precursors.isEmpty() && !precursors.get(0).selectedIons().isEmpty()
                    && only(precursors.get(0).selectedIons().get(0).parameters()) != null) {
                at = i;
            }
        }
        assertThat(at).as("a spectrum whose first selected ion has MS:1002815").isGreaterThanOrEqualTo(0);
        spectra.set(at, withFirstSelectedIonParams(spectra.get(at), params -> append(params, possibleCharge)));
        Param scanMobility = only(spectra.get(1).description().scans().get(0).parameters());
        Param ionMobility = only(spectra.get(at).description().precursors().get(0).selectedIons().get(0)
                .parameters());

        Path out = write(spectra, dia.chromatograms, dia, tmp.resolve("out.mzpeak"));

        Group scan = rows(out.resolve(SCANS)).get(1);
        assertThat(ParquetGroups.optDouble(scan, VALUE)).isEqualTo(scanMobility.value());
        assertThat(ParquetGroups.optString(scan, TYPE)).isEqualTo(INVERSE_K0);
        assertThat(ParquetGroups.readParams(scan, "parameters"))
                .containsExactly(new Param("window group", null, 1L, null), filter);
        Group ion = null;
        for (Group row : rows(out.resolve(SELECTED_IONS))) {
            if (ParquetGroups.readParams(row, "parameters").contains(possibleCharge)) {
                assertThat(ion).as("one selected ion with the extra Param").isNull();
                ion = row;
            }
        }
        assertThat(ion).isNotNull();
        assertThat(ParquetGroups.optDouble(ion, VALUE)).isEqualTo(ionMobility.value());
        assertThat(ParquetGroups.optString(ion, TYPE)).isEqualTo(INVERSE_K0);
        assertThat(ParquetGroups.readParams(ion, "parameters"))
                .noneMatch(q -> CvTerms.ION_MOBILITY_TYPES.contains(q.accession())).contains(possibleCharge);
    }

    @Test
    void aChromatogramSelectedIonWritesBothColumns(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Param drift = new Param("ion mobility drift time", "MS:1002476", 21.5, "UO:0000028");
        Param note = new Param("note", null, "user", null);
        Precursor precursor = new Precursor(null, null, null,
                List.of(new SelectedIon(445.3, 2, null, List.of(note, drift)),
                        new SelectedIon(446.3, 2, null, List.of(note))),
                null);
        List<Chromatogram> chromatograms = new ArrayList<>(small.chromatograms);
        Chromatogram c = chromatograms.get(0);
        chromatograms.set(0, new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(),
                c.intensityUnit(), c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(),
                List.of(precursor), c.timeUnit()));
        Path out = write(small.spectra, chromatograms, small, tmp.resolve("out.mzpeak"));

        List<Group> ions = rows(out.resolve(CHROMATOGRAM_SELECTED_IONS));
        assertThat(ions).hasSize(2);
        assertThat(ParquetGroups.optDouble(ions.get(0), VALUE)).isEqualTo(21.5);
        assertThat(ParquetGroups.optString(ions.get(0), TYPE)).isEqualTo("MS:1002476");
        assertThat(ParquetGroups.readParams(ions.get(0), "parameters")).containsExactly(note);
        assertThat(ParquetGroups.has(ions.get(1), VALUE)).isFalse();
        assertThat(ParquetGroups.has(ions.get(1), TYPE)).isFalse();

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.chromatograms().get(0).precursors()).containsExactly(precursor);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The one ion mobility Param of {@code params}, or null when there is none. */
    private static Param only(List<Param> params) {
        List<Param> mobility = params.stream().filter(p -> CvTerms.ION_MOBILITY_TYPES.contains(p.accession()))
                .toList();
        assertThat(mobility).hasSizeLessThanOrEqualTo(1);
        if (mobility.isEmpty()) {
            return null;
        }
        assertThat(mobility.get(0).accession()).isEqualTo(INVERSE_K0);
        assertThat(mobility.get(0).unit()).isEqualTo("MS:1002814");
        return mobility.get(0);
    }

    /** The two optional columns, DOUBLE then STRING, right after {@code before}. */
    private static void assertColumnsAfter(MessageType schema, String before) {
        int at = schema.getFieldIndex(before);
        Type value = schema.getType(at + 1);
        Type type = schema.getType(at + 2);
        assertThat(value.getName()).as(schema.getName()).isEqualTo(VALUE);
        assertThat(value.isRepetition(Type.Repetition.OPTIONAL)).isTrue();
        assertThat(value.asPrimitiveType().getPrimitiveTypeName()).isEqualTo(PrimitiveTypeName.DOUBLE);
        assertThat(type.getName()).as(schema.getName()).isEqualTo(TYPE);
        assertThat(type.isRepetition(Type.Repetition.OPTIONAL)).isTrue();
        assertThat(type.asPrimitiveType().getPrimitiveTypeName()).isEqualTo(PrimitiveTypeName.BINARY);
        assertThat(type.getLogicalTypeAnnotation()).isEqualTo(LogicalTypeAnnotation.stringType());
    }

    /** Each row holds the value and type of its Param, or neither, and no ion mobility Param in its list. */
    private static void assertCells(List<Group> rows, List<Param> mobility) {
        assertThat(rows).hasSameSizeAs(mobility);
        for (int i = 0; i < rows.size(); i++) {
            Group row = rows.get(i);
            Param p = mobility.get(i);
            String what = "row " + i;
            if (p == null) {
                assertThat(ParquetGroups.has(row, VALUE)).as(what).isFalse();
                assertThat(ParquetGroups.has(row, TYPE)).as(what).isFalse();
            } else {
                assertThat(ParquetGroups.optDouble(row, VALUE)).as(what).isEqualTo(p.value());
                assertThat(ParquetGroups.optString(row, TYPE)).as(what).isEqualTo(INVERSE_K0);
            }
            assertThat(ParquetGroups.readParams(row, "parameters")).as(what)
                    .noneMatch(q -> CvTerms.ION_MOBILITY_TYPES.contains(q.accession()));
        }
    }

    private static List<Param> replace(List<Param> params, UnaryOperator<Param> change) {
        List<Param> out = new ArrayList<>();
        for (Param p : params) {
            out.add(CvTerms.ION_MOBILITY_TYPES.contains(p.accession()) ? change.apply(p) : p);
        }
        return out;
    }

    private static List<Param> append(List<Param> params, Param extra) {
        List<Param> out = new ArrayList<>(params);
        out.add(extra);
        return out;
    }

    /** {@code spectra} with the Params of the first scan of spectrum {@code at} changed by {@code change}. */
    private static List<Spectrum> withScanParams(List<Spectrum> spectra, int at, UnaryOperator<List<Param>> change) {
        List<Spectrum> out = new ArrayList<>(spectra);
        Spectrum s = spectra.get(at);
        SpectrumDescription d = s.description();
        ScanEvent scan = d.scans().get(0);
        assertThat(only(scan.parameters())).as("spectrum %d", at).isNotNull();
        ScanEvent changed = new ScanEvent(scan.startTime(), scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), change.apply(scan.parameters()));
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        scans.set(0, changed);
        SpectrumDescription description = new SpectrumDescription(d.index(), d.id(), d.msLevel(),
                d.retentionTime(), d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                scans, d.precursors(), d.parameters());
        out.set(at, new Spectrum(description, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays()));
        return out;
    }

    /** {@code s} with the Params of the first selected ion of its first precursor changed by {@code change}. */
    private static Spectrum withFirstSelectedIonParams(Spectrum s, UnaryOperator<List<Param>> change) {
        SpectrumDescription d = s.description();
        Precursor p = d.precursors().get(0);
        List<SelectedIon> ions = new ArrayList<>(p.selectedIons());
        SelectedIon ion = ions.get(0);
        ions.set(0, new SelectedIon(ion.mz(), ion.charge(), ion.intensity(), change.apply(ion.parameters())));
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        precursors.set(0, new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), ions,
                p.activation()));
        SpectrumDescription description = new SpectrumDescription(d.index(), d.id(), d.msLevel(),
                d.retentionTime(), d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                d.scans(), precursors, d.parameters());
        return new Spectrum(description, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Path write(List<Spectrum> spectra, List<Chromatogram> chromatograms, Source s, Path out) {
        MzPeakWriter.writeDirectory(out, spectra, chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static MessageType schema(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema();
        }
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
