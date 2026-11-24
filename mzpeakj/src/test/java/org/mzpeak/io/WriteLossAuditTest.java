package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-25 (design §4.4, D-13): the remaining write losses of the split layout, audited against the reference outputs.
 * Each of current small.unpacked, has_uv, diaPASEF.ref and Example_Processed.img is read, written SPLIT and read back:
 * <ul>
 *   <li>(a) every column of every metadata member the fixture has exists in the written member, except those of
 *       {@link #EXCUSED}, each with its reason, and the wavelength columns the spec does not name
 *       ({@link #NOT_IN_SPEC}, W11);
 *   <li>(b) what mzPeakJ reads back equals what it read from the fixture (Canonical forms, exact arrays): spectrum
 *       descriptions, signal arrays and peaks with their extra arrays, chromatograms with their extra arrays, units
 *       and Params, wavelength spectra, scan settings and file metadata;
 *   <li>(c) has_uv's written wavelength scans hold the spectrum-scan columns the wavelength scan table has, an empty
 *       {@code scan_windows}, the stored times and the scans' injection times and instrument configurations;
 *   <li>(d) what mzPeakJ reads but does not write ({@link #READ_ONLY}): the products and the precursor MS level
 *       (R6, D13-21), which none of these fixtures has, are lost by both layouts on the crafted copy of
 *       {@link PrecursorMsLevelAndProductsTest}.
 *   <li>(e) a write that drops any of (d) says so in one WARNING with counts, until the writer units write them.
 * </ul>
 * The auxiliary arrays (R1), read only until X-32, are written by the split layout and lost by the packed one, on
 * has_uv given one auxiliary array per entity type; so are the wavelength scans (R8), read only until X-34, which (b)
 * expects of has_uv.
 */
class WriteLossAuditTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);

    /**
     * The columns of a reference metadata member a written member may lack, with the reason. Nothing else may be
     * missing; in particular not {@code ion_mobility_value}, {@code ion_mobility_type}, {@code scan_windows},
     * {@code auxiliary_arrays} or {@code number_of_auxiliary_arrays}.
     */
    private static final Map<String, String> EXCUSED = Map.of(
            "data_processing_id", "written only for an entity that has a data processing reference, and no entity of "
                    + "these fixtures has one (checked against the reference's cells)",
            "polynomial_delta_coordinate_interpolation", "mzPeakJ writes the reconstructed arrays, not null-marked "
                    + "profiles with interpolation coefficients (DESIGN §8.1)");

    /**
     * The columns of the reference's wavelength members the written ones lack on purpose (W11, D13-12): the wavelength
     * scan table of wavelength-spectra.md has no ion mobility or spectrum reference column, and the representation
     * is written as {@code opt_spectrum_representation}.
     */
    private static final Map<String, Set<String>> NOT_IN_SPEC = Map.of(
            "wavelength_spectra_metadata_scans.parquet",
            Set.of("ion_mobility_value", "ion_mobility_type", "spectrum_reference"),
            "wavelength_spectra_metadata.parquet", Set.of("spectrum_representation"));

    /** The model components mzPeakJ reads and does not write, with the reason: the write losses of (d). */
    static final Map<String, String> READ_ONLY = Map.of(
            "SpectrumDescription.products", "no products member is written (D13-21): the Rust reference panics on"
                    + " a products member (src/reader/metadata.rs DataKind::Products => todo!())",
            "Chromatogram.products", "no products member is written, as for spectra (D13-21)",
            "Precursor.msLevel", "the precursor ms_level is read only (D13-21)");

    /** The metadata members of each fixture: Example_Processed.img has no chromatograms, has_uv wavelength spectra. */
    private static final Map<String, Integer> METADATA_MEMBERS = Map.of("small.unpacked.mzpeak", 7,
            "has_uv.mzpeak", 9, "diaPASEF.ref.mzpeak", 7, "Example_Processed.img.mzpeak", 4);

    static Stream<String> fixtures() {
        return Stream.of("small.unpacked.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak",
                "Example_Processed.img.mzpeak");
    }

    // ---- (a) schemas --------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyReferenceMetadataColumnIsWrittenButTheExcusedOnes(String name, @TempDir Path tmp) {
        Path fixture = Fixtures.current(name);
        Path out = write(ForwardBackwardCorpusTest.readSource(fixture), tmp.resolve("out.mzpeak"));

        Map<String, List<String>> missing = new TreeMap<>();
        int members = 0;
        try (MzPeakSource reference = MzPeakSource.open(fixture); MzPeakSource written = MzPeakSource.open(out)) {
            Set<String> writtenNames = names(MzPeakManifest.fromSource(written));
            for (Entry entry : MzPeakManifest.fromSource(reference).files()) {
                if (!isMetadata(entry)) {
                    continue;
                }
                members++;
                assertThat(writtenNames).as("%s: written members", name).contains(entry.name());
                ParquetFooter ref = ParquetFooter.read(reference.inputFile(entry.name()));
                List<String> have = ParquetFooter.read(written.inputFile(entry.name())).topLevelFields();
                for (String column : ref.topLevelFields()) {
                    if (NOT_IN_SPEC.getOrDefault(entry.name(), Set.of()).contains(column)) {
                        assertThat(have).as("%s: %s", name, entry.name()).doesNotContain(column);
                        continue;
                    }
                    if (!have.contains(column)) {
                        missing.computeIfAbsent(column, k -> new ArrayList<>()).add(entry.name());
                        if (column.equals("data_processing_id")) {
                            assertNoCell(reference, entry.name(), column);
                        }
                    }
                }
            }
        }
        assertThat(members).as("%s: metadata members", name).isEqualTo(METADATA_MEMBERS.get(name));
        assertThat(missing.keySet()).as("%s: reference columns not written, by member: %s", name, missing)
                .isSubsetOf(EXCUSED.keySet());
    }

    // ---- (b) read, write, read ----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void whatIsReadBackEqualsWhatWasRead(String name, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(name));
        Source back = ForwardBackwardCorpusTest.readSource(write(source, tmp.resolve("out.mzpeak")));

        assertThat(Canonical.lines(descriptions(back.spectra), Canonical::describe)).as("%s descriptions", name)
                .containsExactlyElementsOf(expectedDescriptions(name, source.spectra));
        assertThat(back.spectra).hasSameSizeAs(source.spectra);
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            String what = name + " spectrum " + i;
            assertThat(b.mz()).as("%s mz", what).isEqualTo(a.mz());
            assertThat(b.intensity()).as("%s intensity", what).isEqualTo(a.intensity());
            assertThat(b.peaks()).as("%s peaks", what).isEqualTo(a.peaks());
            assertArraysEqual(b.arrays(), a.arrays(), what + " arrays");
            assertArraysEqual(b.peakArrays(), a.peakArrays(), what + " peakArrays");
        }

        assertThat(Canonical.lines(back.chromatograms, Canonical::describe)).as("%s chromatograms", name)
                .containsExactlyElementsOf(Canonical.lines(source.chromatograms, Canonical::describe));
        for (int i = 0; i < source.chromatograms.size(); i++) {
            Chromatogram a = source.chromatograms.get(i);
            Chromatogram b = back.chromatograms.get(i);
            String what = name + " chromatogram " + a.id();
            assertThat(b.time()).as("%s time", what).isEqualTo(a.time());
            assertThat(b.intensity()).as("%s intensity", what).isEqualTo(a.intensity());
            assertArraysEqual(b.arrays(), a.arrays(), what + " arrays");
            assertThat(b.intensityUnit()).as("%s intensityUnit", what).isEqualTo(a.intensityUnit());
            assertThat(b.timeUnit()).as("%s timeUnit", what).isEqualTo(a.timeUnit());
            assertThat(b.polarity()).as("%s polarity", what).isEqualTo(a.polarity());
            assertThat(b.dataProcessingRef()).as("%s dataProcessingRef", what).isEqualTo(a.dataProcessingRef());
            assertThat(Canonical.lines(b.parameters(), Canonical::param)).as("%s parameters", what)
                    .isEqualTo(Canonical.lines(a.parameters(), Canonical::param));
            assertThat(Canonical.lines(b.precursors(), Canonical::precursor)).as("%s precursors", what)
                    .isEqualTo(Canonical.lines(a.precursors(), Canonical::precursor));
        }

        // W11: the wavelength Params read back from their columns (WavelengthColumnsTest); R8: the scans as read
        List<WavelengthSpectrum> expectedWavelengthSpectra = new ArrayList<>();
        for (WavelengthSpectrum w : source.wavelengthSpectra) {
            assertThat(w.scans()).as("%s wavelength spectrum %d scans", name, w.index()).hasSize(1)
                    .first().extracting(ScanEvent::instrumentConfigurationRef).isEqualTo(0);
            expectedWavelengthSpectra.add(new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(),
                    w.wavelength(), w.intensity(), WavelengthColumnsTest.readBack(w.parameters()), w.scans(),
                    List.of()));
        }
        assertThat(Canonical.lines(back.wavelengthSpectra, Canonical::describe)).as("%s wavelength spectra", name)
                .containsExactlyElementsOf(Canonical.lines(expectedWavelengthSpectra, Canonical::describe));
        for (int i = 0; i < source.wavelengthSpectra.size(); i++) {
            WavelengthSpectrum a = source.wavelengthSpectra.get(i);
            WavelengthSpectrum b = back.wavelengthSpectra.get(i);
            assertThat(b.wavelength()).as("%s wavelength spectrum %d wavelength", name, i).isEqualTo(a.wavelength());
            assertThat(b.intensity()).as("%s wavelength spectrum %d intensity", name, i).isEqualTo(a.intensity());
        }

        assertThat(back.scanSettings).as("%s scan settings", name).isEqualTo(source.scanSettings);
        assertThat(back.fileMetadata).as("%s file metadata", name)
                .isEqualTo(withWriterDefaults(source.fileMetadata));
    }

    /** The pins of (b): what the round trip above compares holds the arrays and units the design names. */
    @Test
    void theRoundTripCoversTheExtraArraysUnitsAndWavelengthSpectra() {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Chromatogram tic = small.chromatograms.get(0);
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).hasSize(48);

        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        assertThat(dia.spectra.stream().filter(s -> s.peakArrays().containsKey("MS:1003006")).count())
                .as("diaPASEF.ref spectra with an MS:1003006 peak array").isPositive();

        Source uv = ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak"));
        assertThat(uv.wavelengthSpectra).hasSize(520);
        assertThat(uv.chromatograms).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000812");
        assertThat(uv.chromatograms).extracting(Chromatogram::intensityUnit)
                .containsExactly("MS:1000131", "UO:0000269");
    }

    // ---- (c) has_uv's wavelength scans --------------------------------------------------------------------------

    @Test
    void hasUvWavelengthScansHaveTheScanSchemaAnEmptyScanWindowListAndTheStoredValues(@TempDir Path tmp) {
        Path fixture = Fixtures.current("has_uv.mzpeak");
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = write(source, tmp.resolve("out.mzpeak"));
        String member = "wavelength_spectra_metadata_scans.parquet";

        List<Double> stored = new ArrayList<>();
        try (MzPeakSource reference = MzPeakSource.open(fixture)) {
            ParquetGroups.forEach(reference.inputFile(member),
                    row -> stored.add(ParquetGroups.optDouble(row, "scan_start_time")));
        }
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(out.resolve(member), rows::add);
        assertThat(rows).hasSize(520).hasSameSizeAs(stored);
        for (int i = 0; i < rows.size(); i++) {
            Group row = rows.get(i);
            String what = "wavelength scan " + i;
            assertThat(row.getLong("source_index", 0)).as(what).isEqualTo(source.wavelengthSpectra.get(i).index());
            assertThat(row.getLong("scan_index", 0)).as(what).isEqualTo(i);
            assertThat(ParquetGroups.optDouble(row, "scan_start_time")).as(what).isEqualTo(stored.get(i))
                    .isEqualTo(source.wavelengthSpectra.get(i).time());
            assertThat(ParquetGroups.has(row, "scan_windows")).as("%s scan_windows is not null", what).isTrue();
            assertThat(ParquetGroups.groupList(row, "scan_windows")).as(what).isEmpty();
            assertThat(ParquetGroups.groupList(row, "parameters")).as(what).isEmpty();
            // R8: has_uv's scans have an injection time 0.0 and instrument configuration 0, written as read
            assertThat(row.getDouble("ion_injection_time", 0)).as(what).isZero();
            assertThat(row.getInteger("instrument_configuration_id", 0)).as(what).isZero();
            for (String empty : List.of("preset_scan_configuration", "filter_string")) {
                assertThat(ParquetGroups.has(row, empty)).as("%s %s", what, empty).isFalse();
            }
        }
        try (MzPeakSource reference = MzPeakSource.open(fixture); MzPeakSource written = MzPeakSource.open(out)) {
            List<String> expected = new ArrayList<>(ParquetFooter.read(reference.inputFile(member)).topLevelFields());
            expected.removeAll(NOT_IN_SPEC.get(member));
            assertThat(ParquetFooter.read(written.inputFile(member)).topLevelFields()).isEqualTo(expected);
        }
    }

    // ---- (d) read only ------------------------------------------------------------------------------------------

    @Test
    void theProductsAndThePrecursorMsLevelAreWriteLosses(@TempDir Path tmp) throws IOException {
        assertThat(READ_ONLY.keySet()).contains("SpectrumDescription.products", "Chromatogram.products",
                "Precursor.msLevel");
        Source source = ForwardBackwardCorpusTest.readSource(PrecursorMsLevelAndProductsTest.craft(tmp, true));
        SpectrumDescription five = source.spectra.get((int) PrecursorMsLevelAndProductsTest.SPECTRUM).description();
        assertThat(five.products()).isEqualTo(PrecursorMsLevelAndProductsTest.PRODUCTS);
        assertThat(five.primaryPrecursor().msLevel()).isEqualTo(2);
        assertThat(source.chromatograms.get(0).products()).isEqualTo(PrecursorMsLevelAndProductsTest.PRODUCTS);

        for (MetadataLayout layout : MetadataLayout.values()) {
            Path out = tmp.resolve("out-" + layout + ".mzpeak");
            MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                    source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));
            try (MzPeakSource written = MzPeakSource.open(out)) {
                for (Entry e : MzPeakManifest.fromSource(written).files()) {
                    assertThat(e.kind()).as("%s: %s", layout, e.name()).isNotEqualTo(DataKind.PRODUCTS);
                    if (e.kind() == DataKind.PRECURSORS) {
                        assertThat(ParquetFooter.read(written.inputFile(e.name())).topLevelFields())
                                .as("%s: %s", layout, e.name()).doesNotContain("ms_level");
                    }
                }
            }
            Source back = ForwardBackwardCorpusTest.readSource(out);
            assertThat(back.spectra).as("%s", layout).hasSameSizeAs(source.spectra)
                    .allMatch(s -> s.description().products().isEmpty())
                    .allMatch(s -> s.description().precursors().stream().allMatch(p -> p.msLevel() == null));
            assertThat(back.spectra.get((int) PrecursorMsLevelAndProductsTest.SPECTRUM).description()
                    .primaryPrecursor()).as("%s", layout).isNotNull();
            assertThat(back.chromatograms).as("%s", layout).isNotEmpty()
                    .allMatch(c -> c.products().isEmpty());
        }
    }

    /**
     * The auxiliary arrays and the wavelength scans, read only until X-32 and X-34, are written by the split layout and
     * lost by the packed one.
     */
    @Test
    void theAuxiliaryArraysAndWavelengthScansAreWrittenSplitAndLostPacked(@TempDir Path tmp) {
        assertThat(READ_ONLY.keySet()).containsExactlyInAnyOrder("SpectrumDescription.products",
                "Chromatogram.products", "Precursor.msLevel");
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak"));
        List<AuxiliaryArray> one = List.of(new AuxiliaryArray(new Param("signal to noise array", "MS:1000517", null,
                null), new byte[8], "MS:1000523", "MS:1000576", 1, null, List.of(), null));
        Spectrum s = source.spectra.get(0);
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        spectra.set(0, new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                s.intensityUnit(), s.peakIntensityUnit(), one));
        Chromatogram c = source.chromatograms.get(0);
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(0, new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(),
                c.intensityUnit(), c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(),
                c.timeUnit(), c.products(), one));
        WavelengthSpectrum w = source.wavelengthSpectra.get(0);
        List<WavelengthSpectrum> wavelengths = new ArrayList<>(source.wavelengthSpectra);
        wavelengths.set(0, new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(),
                w.intensity(), w.parameters(), one));

        for (MetadataLayout layout : MetadataLayout.values()) {
            Path out = tmp.resolve("aux-" + layout + ".mzpeak");
            MzPeakWriter.writeDirectory(out, spectra, chromatograms, wavelengths, source.scanSettings,
                    source.fileMetadata, Options.defaults().withMetadataLayout(layout));
            Source back = ForwardBackwardCorpusTest.readSource(out);
            int written = layout == MetadataLayout.SPLIT ? 1 : 0;
            assertThat(back.spectra).as("%s", layout).hasSameSizeAs(spectra);
            assertThat(back.chromatograms).as("%s", layout).hasSameSizeAs(chromatograms);
            assertThat(back.wavelengthSpectra).as("%s", layout).hasSameSizeAs(wavelengths);
            assertThat(back.spectra.get(0).auxiliaryArrays()).as("%s", layout).hasSize(written);
            assertThat(back.chromatograms.get(0).auxiliaryArrays()).as("%s", layout).hasSize(written);
            assertThat(back.wavelengthSpectra.get(0).auxiliaryArrays()).as("%s", layout).hasSize(written);
            assertThat(back.spectra.subList(1, back.spectra.size())).as("%s", layout)
                    .allMatch(b -> b.auxiliaryArrays().isEmpty());
            for (int i = 1; i < wavelengths.size(); i++) {
                assertThat(back.wavelengthSpectra.get(i).scans()).as("%s wavelength spectrum %d scans", layout, i)
                        .isEqualTo(layout == MetadataLayout.SPLIT ? wavelengths.get(i).scans() : List.of())
                        .hasSize(written);
            }
        }
    }

    // ---- (e) the warning about what is not written ----------------------------------------------------------------

    /**
     * Rewriting has_uv, whose 520 wavelength spectra each have a scan naming instrument configuration 0, warns of
     * nothing the writer does not write: the wavelength scans are written since X-34 (the packed layout warns of them
     * itself), and so are one auxiliary array per entity type (since X-32) and a second scan of a wavelength
     * spectrum. The crafted copy of {@link PrecursorMsLevelAndProductsTest} counts its products and precursor MS
     * level. Batch writes and sessions, in both layouts, warn alike.
     */
    @Test
    void aWriteThatDropsReadComponentsWarnsOnceWithCounts(@TempDir Path tmp) throws IOException {
        Source uv = ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak"));
        assertThat(uv.wavelengthSpectra).allSatisfy(w -> assertThat(w.scans()).hasSize(1));
        assertThat(unwrittenWarnings(uv, tmp.resolve("uv"))).isEmpty();

        AuxiliaryArray aux = new AuxiliaryArray(new Param("signal to noise array", "MS:1000517", null, null),
                new byte[8], "MS:1000523", "MS:1000576", 1, null, List.of(), null);
        Spectrum s = uv.spectra.get(0);
        uv.spectra.set(0, new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), s.arrays(),
                s.peakArrays(), s.intensityUnit(), s.peakIntensityUnit(), List.of(aux)));
        Chromatogram c = uv.chromatograms.get(0);
        uv.chromatograms.set(0, new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(),
                c.intensityUnit(), c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(),
                c.timeUnit(), c.products(), List.of(aux)));
        WavelengthSpectrum w = uv.wavelengthSpectra.get(0);
        uv.wavelengthSpectra.set(0, new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(),
                w.wavelength(), w.intensity(), w.parameters(), w.scans(), List.of(aux)));
        WavelengthSpectrum three = uv.wavelengthSpectra.get(3);
        uv.wavelengthSpectra.set(3, new WavelengthSpectrum(three.index(), three.id(), three.time(),
                three.lambdaMax(), three.wavelength(), three.intensity(), three.parameters(),
                List.of(three.scans().get(0), three.scans().get(0)), List.of()));
        assertThat(unwrittenWarnings(uv, tmp.resolve("aux"))).isEmpty();

        Source crafted = ForwardBackwardCorpusTest.readSource(PrecursorMsLevelAndProductsTest.craft(tmp, true));
        assertThat(unwrittenWarnings(crafted, tmp.resolve("crafted"))).containsOnly(WritePlanner.UNWRITTEN
                + "the products of 2 spectra and 1 chromatogram; the MS level of 1 precursor");
    }

    /**
     * The WARNING messages about what is not written of the batch writes of {@code s} into {@code dir} in both
     * layouts and of a session of it in each, the same message repeated by each write.
     */
    private static List<String> unwrittenWarnings(Source s, Path dir) {
        List<String> out = new ArrayList<>();
        for (MetadataLayout layout : MetadataLayout.values()) {
            Options options = Options.defaults().withMetadataLayout(layout);
            out.addAll(writerWarnings(() -> MzPeakWriter.writeDirectory(dir.resolve("batch-" + layout), s.spectra,
                    s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata, options)));
            Set<String> keys = new java.util.LinkedHashSet<>();
            s.spectra.forEach(x -> keys.addAll(x.arrays().keySet()));
            s.spectra.forEach(x -> keys.addAll(x.peakArrays().keySet()));
            out.addAll(writerWarnings(() -> {
                try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(dir.resolve("session-" + layout),
                        s.fileMetadata, s.scanSettings, keys, options)) {
                    s.spectra.forEach(session::addSpectrum);
                    s.chromatograms.forEach(session::addChromatogram);
                    s.wavelengthSpectra.forEach(session::addWavelengthSpectrum);
                    session.finish();
                }
            }));
        }
        List<String> unwritten = out.stream().filter(m -> m.startsWith(WritePlanner.UNWRITTEN)).toList();
        assertThat(unwritten).as("one warning of each of the four writes").hasSizeLessThanOrEqualTo(4)
                .satisfiesAnyOf(l -> assertThat(l).isEmpty(), l -> assertThat(l).hasSize(4));
        return unwritten;
    }

    private static List<String> writerWarnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Path write(Source s, Path out) {
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    /** A metadata member: one that holds an entity's metadata or a facet of it, not signal. */
    private static boolean isMetadata(Entry entry) {
        return switch (entry.kind()) {
            case METADATA, SCANS, PRECURSORS, SELECTED_IONS -> true;
            default -> false;
        };
    }

    private static Set<String> names(MzPeakManifest manifest) {
        Set<String> out = new HashSet<>();
        for (Entry e : manifest.files()) {
            out.add(e.name());
        }
        return out;
    }

    /** The reference's {@code column} of {@code member} is null in every row: no entity has the value. */
    private static void assertNoCell(MzPeakSource reference, String member, String column) {
        List<Boolean> set = new ArrayList<>();
        ParquetGroups.forEach(reference.inputFile(member), row -> set.add(ParquetGroups.has(row, column)));
        assertThat(set).as("%s %s cells", member, column).doesNotContain(true);
    }

    /**
     * The Canonical lines of the descriptions read from fixture {@code name}, as they read back. The ion mobility
     * Params of diaPASEF.ref's scans read back with the name the reader gives MS:1002815 (V-24, WR-5c: equal apart
     * from the name).
     */
    private static List<String> expectedDescriptions(String name, List<Spectrum> spectra) {
        List<String> lines = Canonical.lines(descriptions(spectra), Canonical::describe);
        if (!name.equals("diaPASEF.ref.mzpeak")) {
            return lines;
        }
        String stored = "(inverse reduced ion mobility|MS:1002815|";
        String named = "(inverse reduced ion mobility drift time|MS:1002815|";
        List<String> out = new ArrayList<>(lines.size());
        int renamed = 0;
        for (String line : lines) {
            renamed += line.split(Pattern.quote(stored), -1).length - 1;
            out.add(line.replace(stored, named));
        }
        assertThat(renamed).as("ion mobility Params of diaPASEF.ref's scans").isEqualTo(8);
        return out;
    }

    /**
     * {@code meta} as the writer completes it: a run without {@code default_data_processing_id} gets the first data
     * processing's id, the spec requiring one (diaPASEF.ref's run has none). The configuration diaPASEF.ref's run
     * names but does not declare, which the writer rejects (W12), {@link ForwardBackwardCorpusTest#readSource} has
     * added ({@link Fixtures#withDeclaredDefaults}).
     */
    private static FileMetadata withWriterDefaults(FileMetadata meta) {
        MsRun run = meta.run();
        if (run == null) {
            return meta;
        }
        String dataProcessing = run.defaultDataProcessingId() != null || meta.dataProcessingMethods().isEmpty()
                ? run.defaultDataProcessingId() : meta.dataProcessingMethods().get(0).id();
        MsRun completed = new MsRun(run.id(), run.defaultInstrumentId(), dataProcessing, run.defaultSourceFileId(),
                run.startTime(), run.parameters());
        return new FileMetadata(meta.fileDescription(), meta.instrumentConfigurations(),
                meta.dataProcessingMethods(), meta.samples(), meta.software(), completed);
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static void assertArraysEqual(Map<String, double[]> actual, Map<String, double[]> expected, String what) {
        assertThat(actual.keySet()).as("%s keys", what).containsExactlyElementsOf(expected.keySet());
        for (Map.Entry<String, double[]> e : expected.entrySet()) {
            assertThat(actual.get(e.getKey())).as("%s %s", what, e.getKey()).isEqualTo(e.getValue());
        }
    }
}
