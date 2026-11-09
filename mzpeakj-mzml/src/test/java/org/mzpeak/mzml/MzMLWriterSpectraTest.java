package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Mode;
import org.mzpeak.mzml.ModelAssert.Model;
import org.mzpeak.mzml.MzMLWriter.Contents;
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.model.mzml.ParamGroup;
import uk.ac.ebi.jmzml.model.mzml.Scan;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * JZ-6: {@link MzMLWriter} part 2, the spectra, the wavelength spectra and every reference to them (mzML design 5.2,
 * 5.4-5.7, 5.9, 5.11, 6.2, 6.4, 8.1). Every document is written with and without zlib, each checked by
 * {@link MzMLAssert#valid}, and read back through {@link MzMLReader}: (a) every mzPeak fixture equals its read-back
 * model up to the A exclusions of design 12.4 for this leg; (b) ids and the references that follow them; (c) spectrum
 * precursors; (d) scans; (e) spectrum and wavelength spectrum params; (f) peak lists; (g) arrays; (h) the writer's
 * contract and the cv-list check; (i) determinism.
 */
class MzMLWriterSpectraTest {

    private static final String NO_COMPRESSION = "MS:1000576";
    private static final String NO_COMBINATION = "MS:1000795";
    private static final String CHARGE_ARRAY = "MS:1000516";
    private static final Param SOURCE = new Param("nanoelectrospray", "MS:1000398", null, null);
    private static final Param ANALYZER = new Param("quadrupole ion trap", "MS:1000082", null, null);
    private static final Param DETECTOR = new Param("electron multiplier", "MS:1000253", null, null);
    /** The writer's lines about spectra, which the fixtures may give only for the exclusions they pass. */
    private static final Predicate<String> SPECTRUM_LINE = line -> line.matches("A-(1|7|9|16|23) .*")
            || line.contains("spectrum precursorIndex") || line.contains("scan instrumentConfigurationRef")
            || line.contains("scan holding");
    /** The writer's line for each exclusion whose differences it counts itself. */
    private static final Map<Exclusion, String> COUNTED = Map.of(ModelAssert.A_1, MzMLWriter.A_1, ModelAssert.A_7,
            MzMLWriter.A_7, ModelAssert.A_9, MzMLWriter.A_9, ModelAssert.A_16, MzMLWriter.A_16);

    @TempDir
    Path dir;

    // ---- (a) every mzPeak fixture ------------------------------------------------------------------------------

    static Stream<Arguments> fixtures() {
        Map<Exclusion, Integer> dia = counts(ModelAssert.A_2, 1, ModelAssert.A_7, 9, ModelAssert.A_8, 1,
                ModelAssert.A_18, 1);
        Map<Exclusion, Integer> legacySmall = counts(ModelAssert.A_7, 48, ModelAssert.A_9, 48, ModelAssert.A_16, 48,
                ModelAssert.A_17, 1);
        Map<Exclusion, Integer> legacyChunked = new LinkedHashMap<>(legacySmall);
        legacyChunked.put(ModelAssert.A_1, 14);
        return Stream.of(
                fixture("current", "small.mzpeak", counts(ModelAssert.A_7, 48)),
                fixture("current", "small.chunked.mzpeak", counts(ModelAssert.A_1, 14, ModelAssert.A_7, 48)),
                fixture("current", "small.numpress.mzpeak", counts(ModelAssert.A_7, 48)),
                fixture("current", "small.unpacked.mzpeak", counts(ModelAssert.A_7, 48)),
                fixture("current", "has_uv.mzpeak", counts(ModelAssert.A_7, 212 + 520)),
                fixture("current", "diaPASEF.ref.mzpeak", dia),
                fixture("current", "diaPASEF.grid.mzpeak", dia),
                fixture("current", "Example_Processed.img.mzpeak", counts(ModelAssert.A_7, 9, ModelAssert.A_17, 1)),
                fixture("legacy", "small.mzpeak", legacySmall),
                fixture("legacy", "small.chunked.mzpeak", legacyChunked),
                fixture("legacy", "small.numpress.mzpeak", legacySmall),
                fixture("legacy", "small.unpacked.mzpeak", legacySmall),
                fixture("legacy", "has_uv.mzpeak", counts(ModelAssert.A_7, 212 + 520, ModelAssert.A_9, 211,
                        ModelAssert.A_16, 212, ModelAssert.A_17, 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureReadsBackEqualUpToTheExclusionsOfThisLeg(String name, Path fixture,
                                                             Map<Exclusion, Integer> hidden) {
        try (MzPeakReader reader = MzPeakReader.open(fixture, MzPeakReader.Options.defaults())) {
            List<Spectrum> spectra = new ArrayList<>();
            for (int i = 0; i < reader.size(); i++) {
                spectra.add(reader.getSpectrumAt(i));
            }
            Model expected = new Model(spectra, reader.chromatograms(), reader.wavelengthSpectra(),
                    reader.fileMetadata(), reader.scanSettings(), reader.manifest().cvList());
            Map<String, Integer> counts = new LinkedHashMap<>();
            hidden.forEach((exclusion, n) -> counts.put(exclusion.id(), n));
            List<String> lines = new ArrayList<>();
            hidden.forEach((exclusion, n) -> {
                if (COUNTED.containsKey(exclusion)) {
                    lines.add(COUNTED.get(exclusion) + " (" + n + ")");
                }
            });

            for (Written w : write(name.replace(' ', '-'), contents(reader), writer -> {
                spectra.forEach(writer::write);
                reader.wavelengthSpectra().forEach(writer::write);
            })) {
                ReadBack back = read(w.file());
                Model actual = new Model(back.spectra(), back.chromatograms(), back.wavelengthSpectra(),
                        back.header().fileMetadata(), back.header().scanSettings(), back.header().cvList());
                ModelAssert.Result result = ModelAssert.assertEqual(name + " " + w, expected, actual,
                        Mode.MZPEAK_VS_MZML, hidden.keySet().toArray(Exclusion[]::new));
                assertThat(result.hidden()).isEqualTo(counts);
                assertThat(w.warnings()).filteredOn(SPECTRUM_LINE).containsExactlyInAnyOrderElementsOf(lines);
                assertThat(w.warnings()).noneMatch(line -> line.matches("A-2[5-9] .*"));
            }
        }
    }

    // ---- (b) ids and the references that follow them ----------------------------------------------------------

    @Test
    void spectrumIdsAreRepairedAndReferencesFollowTheirSpectrum() {
        List<String> ids = Arrays.asList("scan=1\u0001", "scan=1\u0002", null, "", "bad id", "scan=5", "scan=5",
                "index=8", null);
        List<Spectrum> spectra = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            List<Precursor> precursors = switch (i) {
                case 2 -> List.of(new Precursor(0L, "scan=1\u0001", null, List.of(), null));
                case 3 -> List.of(new Precursor(1L, "scan=1\u0002", null, List.of(), null));
                case 8 -> List.of(new Precursor(4L, "bad id", null, List.of(), null));
                default -> List.of();
            };
            String reference = i == 6 ? "scan=5" : i == 7 ? "bad id" : null;
            spectra.add(spectrum(description(ids.get(i), 2, List.of(new ScanEvent(1.0, null, null, null, null,
                    reference, List.of(), List.of())), precursors, List.of())));
        }
        Chromatogram chromatogram = new Chromatogram(0, "scan=5", "MS:1000627", new double[]{1}, new double[]{2},
                null, Map.of(), Polarity.UNKNOWN, null, List.of(), List.of(new Precursor(null, "bad id", null,
                List.of(), null)));
        Contents contents = new Contents(metadata(), List.of(), List.of(), ids, List.of(), List.of(chromatogram));

        for (Written w : write("ids", contents, writer -> spectra.forEach(writer::write))) {
            ReadBack back = read(w.file());
            assertThat(back.spectra()).extracting(s -> s.description().id()).containsExactly(
                    "scan=1���", "index=1", "index=2", "index=3", "index=4", "scan=5", "index=6",
                    "index=8", "index=8 mzpeakj=1");
            assertThat(back.spectra().get(2).description().precursors().get(0).precursorId())
                    .isEqualTo("scan=1���");
            assertThat(back.spectra()).extracting(s -> s.description().precursors()).extracting(p -> p.isEmpty()
                    ? null : p.get(0).precursorIndex()).containsExactly(null, null, 0L, 1L, null, null, null, null,
                    4L);
            assertThat(back.spectra().get(3).description().precursors().get(0).precursorId()).isEqualTo("index=1");
            assertThat(back.spectra().get(8).description().precursors().get(0).precursorId()).isEqualTo("index=4");
            assertThat(back.spectra().get(6).description().scans().get(0).spectrumReference()).isEqualTo("scan=5");
            assertThat(back.spectra().get(7).description().scans().get(0).spectrumReference()).isEqualTo("index=4");
            Chromatogram c = back.chromatograms().get(0);
            assertThat(c.id()).isEqualTo("index=0");
            assertThat(c.precursors()).containsExactly(new Precursor(null, "index=4", null, List.of(), null));
            assertThat(jmzmlChromatogramPrecursorRef(w.file())).isEqualTo("index=4");
            assertThat(w.warnings()).contains(MzMLWriter.A_3 + " (7)", MzMLWriter.A_28 + " (1)")
                    .noneMatch(line -> line.startsWith("A-23") || line.startsWith("A-18"));
        }
    }

    // ---- (c) spectrum precursors ------------------------------------------------------------------------------

    @Test
    void theIndexOfASpectrumPrecursorDecidesItsReference() {
        Precursor named = new Precursor(0L, "scan=1", new IsolationWindow(445.3, 0.5, 0.5),
                List.of(new SelectedIon(445.34, 2, 1.5e5, List.of())),
                new Activation(List.of(new Param(null, "MS:1000133", null, null))));
        List<Precursor> precursors = List.of(named,
                new Precursor(0L, "other", null, List.of(), null),
                new Precursor(7L, "scan=99", null, List.of(), null),
                new Precursor(-1L, null, null, List.of(), null),
                new Precursor(null, "scan=1", null, List.of(), null),
                new Precursor(null, null, null, List.of(), null),
                new Precursor(2L, "scan=3", null, List.of(), null));
        List<Spectrum> spectra = List.of(spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(),
                        List.of())),
                spectrum(description("scan=2", 2, List.of(scan(1.1)), precursors, List.of())),
                spectrum(description("scan=3", 1, List.of(scan(1.2)), List.of(), List.of())));
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1", "scan=2", "scan=3"),
                List.of(), List.of());

        for (Written w : write("precursors", contents, writer -> spectra.forEach(writer::write))) {
            List<Precursor> back = read(w.file()).spectra().get(1).description().precursors();
            assertThat(back).extracting(Precursor::precursorIndex).containsExactly(0L, 0L, null, null, null, null,
                    2L);
            assertThat(back).extracting(Precursor::precursorId).containsExactly("scan=1", "scan=1", "scan=99", null,
                    "scan=1", null, "scan=3");
            assertThat(back.get(0)).isEqualTo(new Precursor(0L, "scan=1", new IsolationWindow(445.3, 0.5, 0.5),
                    List.of(new SelectedIon(445.34, 2, 1.5e5, List.of())), new Activation(List.of(
                    new Param("collision-induced dissociation", "MS:1000133", null, null)))));
            List<uk.ac.ebi.jmzml.model.mzml.Precursor> raw = jmzmlSpectra(w.file()).get(1).getPrecursorList()
                    .getPrecursor();
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Precursor::getSpectrumRef).containsExactly(
                    "scan=1", "scan=1", null, null, null, null, "scan=3");
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Precursor::getExternalSpectrumID).containsExactly(
                    null, null, "scan=99", null, "scan=1", null, null);
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Precursor::getSourceFileRef).containsExactly(
                    null, null, "sf", null, "sf", null, null);
            assertThat(w.warnings()).contains(MzMLWriter.A_23 + " (1)", String.format(MzMLWriter.A_18,
                    "spectrum precursorIndex naming no spectrum written as externalSpectrumID") + " (2)");
        }
    }

    // ---- (d) scans ----------------------------------------------------------------------------------------------

    @Test
    void scansCarryTheirTermsWindowsAndReferences() {
        ScanEvent full = new ScanEvent(1.5, 2.0, "FTMS + p ESI Full ms [100.00-2000.00]", 3, 0, "uv=1",
                List.of(new ScanWindow(100, 2000)), List.of(new Param("increasing m/z scan", "MS:1000093", null,
                null), new Param("inverse reduced ion mobility", "MS:1002815", 1.1, "MS:1002814"),
                new Param("window group", null, 1L, null)));
        ScanEvent dangling = new ScanEvent(Double.NaN, null, null, null, 5, "elsewhere", List.of(), List.of());
        Param sum = new Param("sum of spectra", "MS:1000571", null, null);
        Param mean = new Param("mean of spectra", "MS:1000575", null, null);
        List<Spectrum> spectra = List.of(
                spectrum(description("scan=1", 1, List.of(full), List.of(), List.of())),
                spectrum(withTime(description("scan=2", 1, List.of(dangling, scan(2.0)), List.of(), List.of()), 2.5)),
                spectrum(withTime(description("scan=3", 1, List.of(), List.of(), List.of()), 3.0)),
                spectrum(withTime(description("scan=4", 1, List.of(), List.of(), List.of(sum)), Double.NaN)),
                spectrum(description("scan=5", 1, List.of(scan(1.0), scan(1.1)), List.of(), List.of(mean))),
                spectrum(withTime(description("scan=6", 1, List.of(), List.of(), List.of()),
                        Double.POSITIVE_INFINITY)),
                spectrum(description("scan=7", 1, List.of(scan(4.0)), List.of(), List.of(sum))));
        WavelengthSpectrum uv = new WavelengthSpectrum(0, "uv=1", 0.5, null, new double[]{200}, new double[]{0.1});
        Contents contents = new Contents(metadata(), List.of(), List.of(),
                List.of("scan=1", "scan=2", "scan=3", "scan=4", "scan=5", "scan=6", "scan=7"), List.of("uv=1"),
                List.of());

        for (Written w : write("scans", contents, writer -> {
            spectra.forEach(writer::write);
            writer.write(uv);
        })) {
            List<SpectrumDescription> back = read(w.file()).spectra().stream().map(Spectrum::description).toList();
            assertThat(back.get(0).scans()).containsExactly(new ScanEvent(1.5, 2.0,
                    "FTMS + p ESI Full ms [100.00-2000.00]", 3, 0, "uv=1", List.of(new ScanWindow(100, 2000)),
                    List.of(full.parameters().get(1), full.parameters().get(0), full.parameters().get(2))));
            assertThat(back.get(1).scans()).containsExactly(new ScanEvent(Double.NaN, null, null, null, null,
                    "elsewhere", List.of(), List.of()), scan(2.0));
            assertThat(back.get(1).retentionTime()).isNaN();
            assertThat(back.get(2).scans()).containsExactly(scan(3.0));
            assertThat(back.get(2).retentionTime()).isEqualTo(3.0);
            Param ms1 = new Param("MS1 spectrum", "MS:1000579", null, null); // A-16
            assertThat(back.get(3).scans()).isEmpty();
            assertThat(back.get(3).parameters()).containsExactly(ms1, sum);
            assertThat(back.get(4).parameters()).containsExactly(ms1, mean);
            assertThat(back.get(5).scans()).isEmpty();
            assertThat(back.get(5).retentionTime()).isNaN();
            assertThat(back.get(6).scans()).containsExactly(scan(4.0));
            assertThat(back.get(6).parameters()).containsExactly(ms1, sum); // its own term, no MS:1000795 added

            List<uk.ac.ebi.jmzml.model.mzml.Spectrum> raw = jmzmlSpectra(w.file());
            Scan first = raw.get(0).getScanList().getScan().get(0);
            assertThat(accessions(first)).containsExactly("MS:1000016", "MS:1000927", "MS:1000512", "MS:1000616",
                    "MS:1002815", "MS:1000093");
            assertThat(first.getCvParam().get(0).getUnitAccession()).isEqualTo("UO:0000031");
            assertThat(first.getCvParam().get(1).getUnitAccession()).isEqualTo("UO:0000028");
            assertThat(first.getInstrumentConfigurationRef()).isEqualTo("IC1");
            assertThat(first.getSpectrumRef()).isEqualTo("uv=1");
            assertThat(first.getExternalSpectrumID()).isNull();
            assertThat(accessions(first.getScanWindowList().getScanWindow().get(0)))
                    .containsExactly("MS:1000501", "MS:1000500");
            Scan second = raw.get(1).getScanList().getScan().get(0);
            assertThat(second.getCvParam()).isEmpty();
            assertThat(second.getInstrumentConfigurationRef()).isNull();
            assertThat(second.getSpectrumRef()).isNull();
            assertThat(second.getExternalSpectrumID()).isEqualTo("elsewhere");
            assertThat(second.getSourceFileRef()).isEqualTo("sf");
            assertThat(raw).extracting(s -> s.getScanList() == null ? null : accessions(s.getScanList()))
                    .containsExactly(List.of(NO_COMBINATION), List.of(), List.of(NO_COMBINATION), null,
                            List.of("MS:1000575"), null, List.of("MS:1000571"), List.of(NO_COMBINATION));
            assertThat(accessions(raw.get(3))).contains("MS:1000571");
            assertThat(w.warnings()).contains(MzMLWriter.A_7 + " (3)", MzMLWriter.A_9 + " (2)",
                    String.format(MzMLWriter.A_8, "scan holding the retention time of a spectrum without scans")
                            + " (1)",
                    String.format(MzMLWriter.A_18, "scan instrumentConfigurationRef dropped") + " (1)");
        }
    }

    // ---- (e) spectrum and wavelength spectrum params ------------------------------------------------------------

    @Test
    void spectrumParamsAreWrittenInTheCanonicalOrderWithATypeFromTheMsLevel() {
        List<Param> params = List.of(new Param("note", null, "n", null),
                new Param("total ion current", "MS:1000285", 10.0, "MS:1000131"),
                new Param("spectrum title", "MS:1000796", "t", null),
                new Param("lowest observed m/z", "MS:1000528", 100.0, "MS:1000040"),
                new Param(null, "MS:1000580", null, null),
                new Param("base peak m/z", "MS:1000504", 150.0, "MS:1000040"));
        SpectrumDescription typed = new SpectrumDescription(0, "scan=1", 2, 1.0, Polarity.NEGATIVE,
                SignalContinuity.CENTROID, 0, 2, List.of(scan(1.0)), List.of(), params);
        List<Spectrum> spectra = List.of(spectrum(typed),
                spectrum(bare("scan=2", 1)), spectrum(bare("scan=3", 3)), spectrum(bare("scan=4", 0)));
        Contents contents = new Contents(metadata(), List.of(), List.of(),
                List.of("scan=1", "scan=2", "scan=3", "scan=4"), List.of(), List.of());

        for (Written w : write("params", contents, writer -> spectra.forEach(writer::write))) {
            List<uk.ac.ebi.jmzml.model.mzml.Spectrum> raw = jmzmlSpectra(w.file());
            assertThat(raw).extracting(MzMLWriterSpectraTest::accessions).containsExactly(
                    List.of("MS:1000580", "MS:1000511", "MS:1000129", "MS:1000127", "MS:1000528", "MS:1000504",
                            "MS:1000285", "MS:1000796"),
                    List.of("MS:1000579", "MS:1000511"),
                    List.of("MS:1000580", "MS:1000511"),
                    List.of());
            assertThat(raw.get(0).getUserParam()).extracting(uk.ac.ebi.jmzml.model.mzml.UserParam::getName)
                    .containsExactly("note");
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Spectrum::getIndex).containsExactly(0, 1, 2, 3);
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Spectrum::getDefaultArrayLength)
                    .containsExactly(1, 1, 1, 1);
            List<SpectrumDescription> back = read(w.file()).spectra().stream().map(Spectrum::description).toList();
            assertThat(back.get(0).msLevel()).isEqualTo(2);
            assertThat(back.get(0).polarity()).isEqualTo(Polarity.NEGATIVE);
            assertThat(back.get(0).signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
            assertThat(back.get(0).parameters()).extracting(Param::accession, Param::value).containsExactly(
                    tuple("MS:1000580", null), tuple("MS:1000528", 100.0), tuple("MS:1000504", 150.0),
                    tuple("MS:1000285", 10.0), tuple("MS:1000796", "t"), tuple(null, "n"),
                    tuple(NO_COMBINATION, null));
            assertThat(back).extracting(SpectrumDescription::msLevel).containsExactly(2, 1, 3, 0);
            assertThat(back.get(3).parameters()).extracting(Param::accession).containsExactly(NO_COMBINATION);
            assertThat(back.get(3).polarity()).isEqualTo(Polarity.UNKNOWN);
            assertThat(back.get(3).signalContinuity()).isEqualTo(SignalContinuity.UNKNOWN);
            assertThat(w.warnings()).contains(MzMLWriter.A_16 + " (2)");
        }
    }

    @Test
    void wavelengthSpectraFollowTheMsSpectraWithOneScanAndLambdaMax() {
        List<Param> params = List.of(new Param("spectrum title", "MS:1000796", "t", null),
                new Param("total ion current", "MS:1000285", -1.1, "MS:1000131"),
                new Param(null, "MS:1000128", null, null),
                new Param("electromagnetic radiation spectrum", "MS:1000804", null, null),
                new Param("lowest observed wavelength", "MS:1000619", 210.0, "UO:0000018"),
                new Param("u", null, 1L, null),
                new Param("highest observed wavelength", "MS:1000618", 400.0, "UO:0000018"),
                new Param("base peak intensity", "MS:1000505", 0.3, "MS:1000131"));
        WavelengthSpectrum first = new WavelengthSpectrum(0, "uv=1", 0.5, 374.0, new double[]{200, 210, 220},
                new double[]{0.1, -0.2, 0.3}, params);
        WavelengthSpectrum second = new WavelengthSpectrum(1, "uv=2", Double.NaN, null, new double[0], new double[0]);
        Spectrum ms = spectrum(description("scan=1", 1, List.of(scan(0.4)), List.of(), List.of()));
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1"), List.of("uv=1", "uv=2"),
                List.of());

        for (Written w : write("wavelength", contents, writer -> {
            writer.write(ms);
            writer.write(first);
            writer.write(second);
        })) {
            List<uk.ac.ebi.jmzml.model.mzml.Spectrum> raw = jmzmlSpectra(w.file());
            assertThat(accessions(raw.get(1))).containsExactly("MS:1000804", "MS:1000128", "MS:1000619",
                    "MS:1000618", "MS:1000505", "MS:1000285", "MS:1003812", "MS:1000796");
            assertThat(raw.get(1).getCvParam().get(6).getUnitAccession()).isEqualTo("UO:0000018");
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Spectrum::getIndex).containsExactly(0, 1, 2);
            assertThat(raw).extracting(uk.ac.ebi.jmzml.model.mzml.Spectrum::getDefaultArrayLength)
                    .containsExactly(1, 3, 0);
            assertThat(raw.get(1).getBinaryDataArrayList().getBinaryDataArray()).extracting(a -> a.getCvParam()
                    .get(2).getAccession() + " " + a.getCvParam().get(2).getUnitAccession())
                    .containsExactly("MS:1000617 UO:0000018", "MS:1000515 MS:1000131");
            assertThat(raw.get(2).getScanList().getScan().get(0).getCvParam()).isEmpty();

            ReadBack back = read(w.file());
            assertThat(back.spectra()).hasSize(1);
            WavelengthSpectrum b = back.wavelengthSpectra().get(0);
            assertThat(List.of(b.index(), b.id(), b.time(), b.lambdaMax())).containsExactly(0L, "uv=1", 0.5, 374.0);
            assertThat(b.wavelength()).containsExactly(200, 210, 220);
            assertThat(b.intensity()).containsExactly(0.1, -0.2, 0.3);
            assertThat(b.parameters()).extracting(Param::accession, Param::value).containsExactlyInAnyOrder(
                    tuple("MS:1000796", "t"), tuple("MS:1000285", -1.1), tuple("MS:1000128", null),
                    tuple("MS:1000804", null), tuple("MS:1000619", 210.0), tuple(null, 1L),
                    tuple("MS:1000618", 400.0), tuple("MS:1000505", 0.3), tuple(NO_COMBINATION, null));
            WavelengthSpectrum empty = back.wavelengthSpectra().get(1);
            assertThat(List.of(empty.index(), empty.id(), empty.time())).containsExactly(1L, "uv=2", Double.NaN);
            assertThat(empty.lambdaMax()).isNull();
            assertThat(empty.parameters()).extracting(Param::accession).containsExactly(NO_COMBINATION);
            assertThat(w.warnings()).contains(MzMLWriter.A_7 + " (3)");
        }
    }

    // ---- (f) peak lists -----------------------------------------------------------------------------------------

    static Stream<Arguments> peakLists() {
        double[] mz = {100, 200};
        double[] in = {1, 2};
        List<CentroidPeak> both = List.of(peak(100, 1, 0), peak(200, 2, 1));
        Map<String, double[]> k78 = Map.of(CHARGE_ARRAY, new double[]{7, 8});
        return Stream.of(
                Arguments.of("no peaks", peaks(mz, in, List.of(), Map.of(), Map.of()), true),
                Arguments.of("a row copy", peaks(mz, in, both, Map.of(), Map.of()), true),
                Arguments.of("a row copy with arrays", peaks(mz, in, both, k78, k78), true),
                Arguments.of("unnamed rows of non-finite m/z", peaks(new double[]{100, Double.NaN}, in,
                        List.of(peak(100, 1, 0)), Map.of(), Map.of()), true),
                Arguments.of("other array keys", peaks(mz, in, both, k78, Map.of()), false),
                Arguments.of("a negative index", peaks(mz, in, List.of(peak(100, 1, -1)), Map.of(), Map.of()), false),
                Arguments.of("an index past the rows", peaks(mz, in, List.of(peak(100, 1, 0), peak(200, 2, 2)),
                        Map.of(), Map.of()), false),
                Arguments.of("a row named twice", peaks(new double[]{100, Double.NaN}, in,
                        List.of(peak(100, 1, 0), peak(100, 1, 0)), Map.of(), Map.of()), false),
                Arguments.of("another m/z", peaks(mz, in, List.of(peak(100.5, 1, 0), peak(200, 2, 1)), Map.of(),
                        Map.of()), false),
                Arguments.of("another intensity", peaks(mz, in, List.of(peak(100, 9, 0), peak(200, 2, 1)), Map.of(),
                        Map.of()), false),
                Arguments.of("a peak array shorter than the rows", peaks(new double[]{100, 200, 300},
                        new double[]{1, 2, 3}, List.of(peak(100, 1, 0), peak(300, 3, 2)),
                        Map.of(CHARGE_ARRAY, new double[]{7, 8, 9}), Map.of(CHARGE_ARRAY, new double[]{7, 9})), false),
                Arguments.of("another peak array value", peaks(mz, in, both, k78,
                        Map.of(CHARGE_ARRAY, new double[]{7, 99})), false),
                Arguments.of("an unnamed row of a finite m/z", peaks(mz, in, List.of(peak(100, 1, 0)), Map.of(),
                        Map.of()), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("peakLists")
    void aPeakListThatIsNoRowCopyOfTheArraysIsDropped(String name, Spectrum spectrum, boolean rowCopy) {
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1"), List.of(), List.of());
        try (MzMLWriter writer = MzMLWriter.open(dir.resolve("unused.mzML"), contents, true)) {
            writer.write(spectrum);
            if (rowCopy) {
                assertThat(writer.warnings()).noneMatch(line -> line.startsWith("A-1 "));
            } else {
                assertThat(writer.warnings()).contains(MzMLWriter.A_1 + " (1)");
            }
        }
    }

    @Test
    void theArraysOfASpectrumWithPeaksAreWrittenAndItsPeaksAreNot() {
        SpectrumDescription d = description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of());
        Spectrum profile = new Spectrum(d, new double[]{100, 101}, new double[]{1, 2}, List.of(peak(100.5, 5, 0)));
        SpectrumDescription c = new SpectrumDescription(1, "scan=2", 2, 1.1, Polarity.POSITIVE,
                SignalContinuity.CENTROID, 0, 2, List.of(scan(1.1)), List.of(), List.of());
        Spectrum centroid = new Spectrum(c, new double[]{150, 250}, new double[]{3, 4},
                List.of(peak(150, 3, 0), peak(250, 4, 1)));
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1", "scan=2"), List.of(),
                List.of());

        for (Written w : write("peaks", contents, writer -> {
            writer.write(profile);
            writer.write(centroid);
        })) {
            List<Spectrum> back = read(w.file()).spectra();
            assertThat(back.get(0).mz()).containsExactly(100, 101);
            assertThat(back.get(0).intensity()).containsExactly(1, 2);
            assertThat(back.get(1).mz()).containsExactly(150, 250);
            assertThat(back.get(1).intensity()).containsExactly(3, 4);
            assertThat(back).allSatisfy(s -> assertThat(s.peaks()).isEmpty());
            assertThat(w.warnings()).contains(MzMLWriter.A_1 + " (1)");
        }
    }

    // ---- (g) arrays ---------------------------------------------------------------------------------------------

    @Test
    void extraArrayKeysOutsideTheArrayTypesAreWrittenAsNonStandardDataArrays() throws IOException {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        arrays.put("MS:9999999", new double[]{0.1, Double.MIN_VALUE});
        arrays.put("XX:0000001", new double[]{-0.0, Double.MAX_VALUE});
        arrays.put("ms level", new double[]{1, 2});
        arrays.put("MS:1003006", new double[]{0.9, 1.1});
        Spectrum s = new Spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of()),
                new double[]{100, 200}, new double[]{1, 2}, null, arrays, null);
        Contents contents = new Contents(metadata(), List.of(), List.of(known("MS"), known("UO")), List.of("scan=1"),
                List.of(), List.of());

        for (Written w : write("keys", contents, writer -> writer.write(s))) {
            String xml = Files.readString(w.file(), UTF_8);
            for (String key : List.of("MS:9999999", "XX:0000001")) {
                assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1000786\" value=\"" + key
                        + "\" name=\"non-standard data array\"></cvParam>");
            }
            assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1000786\" value=\"ms level\" "
                    + "name=\"non-standard data array\" unitAccession=\"UO:0000186\" unitName=\"dimensionless unit\" "
                    + "unitCvRef=\"UO\"></cvParam>");
            assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1003006\" "
                    + "name=\"mean inverse reduced ion mobility array\" unitAccession=\"MS:1002814\"");
            ReadBack back = read(w.file());
            assertThat(back.header().cvList()).extracting(CvEntry::id).containsExactly("MS", "UO");
            Spectrum read = back.spectra().get(0);
            assertThat(read.arrays()).containsOnlyKeys(arrays.keySet());
            arrays.forEach((key, values) -> assertThat(bits(read.arrays().get(key))).as(key).isEqualTo(bits(values)));
        }
    }

    @Test
    void anEmptySpectrumIsWrittenWithoutCompression() throws IOException {
        Spectrum empty = new Spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of()),
                new double[0], new double[0], null);
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1"), List.of(), List.of());
        Path target = dir.resolve("empty.mzML");

        MzMLWriter writer = MzMLWriter.open(target, contents, true);
        writer.write(empty);
        assertTimeoutPreemptively(Duration.ofSeconds(10), writer::finish);

        MzMLAssert.valid(target);
        assertThat(jmzmlSpectra(target).get(0).getBinaryDataArrayList().getBinaryDataArray())
                .extracting(MzMLWriterSpectraTest::accessions).containsExactly(
                        List.of(NO_COMPRESSION, "MS:1000523", "MS:1000514"),
                        List.of(NO_COMPRESSION, "MS:1000523", "MS:1000515"));
        Spectrum back = read(target).spectra().get(0);
        assertThat(back.mz()).isEmpty();
        assertThat(back.intensity()).isEmpty();

        Path plain = dir.resolve("empty-plain.mzML");
        MzMLWriter plainWriter = MzMLWriter.open(plain, contents, false);
        plainWriter.write(empty);
        plainWriter.finish();
        MzMLAssert.valid(plain);
        assertThat(Files.mismatch(target, plain)).as("an empty array is never compressed").isEqualTo(-1L);
    }

    // ---- (h) the writer's contract and the cv-list check -------------------------------------------------------

    @Test
    void spectraAreWrittenInTheAnnouncedOrderMsSpectraFirst() {
        Spectrum one = spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of()));
        Spectrum two = spectrum(description("scan=2", 1, List.of(scan(1.1)), List.of(), List.of()));
        WavelengthSpectrum uv1 = new WavelengthSpectrum(0, "uv=1", 0.5, null, new double[]{200}, new double[]{1});
        WavelengthSpectrum uv2 = new WavelengthSpectrum(1, "uv=2", 0.6, null, new double[]{200}, new double[]{1});
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("scan=1"), List.of("uv=1"),
                List.of());

        try (MzMLWriter writer = MzMLWriter.open(dir.resolve("order.mzML"), contents, true)) {
            assertThatThrownBy(() -> writer.write(two)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MS spectrum #0 has the id 'scan=2'").hasMessageContaining("'scan=1'");
            assertThatThrownBy(() -> writer.write(uv1)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wavelength spectrum 'uv=1' after 0 of the 1 MS spectra");
            writer.write(one);
            assertThatThrownBy(() -> writer.write(two)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MS spectrum 'scan=2' after the 1 MS spectra announced");
            assertThatThrownBy(() -> writer.write(uv2)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wavelength spectrum #0 has the id 'uv=2'");
            writer.write(uv1);
            assertThatThrownBy(() -> writer.write(one)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MS spectrum 'scan=1' after the 1 MS spectra announced");
            assertThatThrownBy(() -> writer.write(uv2)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wavelength spectrum 'uv=2' after the 1 wavelength spectra announced");
            assertThatThrownBy(() -> writer.write((Spectrum) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.write((WavelengthSpectrum) null)).isInstanceOf(NullPointerException.class);
            writer.finish();
            assertThatThrownBy(() -> writer.write(one)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finished or closed");
            assertThatThrownBy(() -> writer.write(uv1)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finished or closed");
        }
        MzMLAssert.valid(dir.resolve("order.mzML"));
        assertThat(read(dir.resolve("order.mzML")).spectra()).hasSize(1);
    }

    static Stream<Arguments> undeclaredPrefixes() {
        Param bto = new Param("tissue", "BTO:0000142", null, null);
        Param btoUnit = new Param("thickness", null, 2.0, "BTO:0000001");
        Precursor window = new Precursor(null, null, new IsolationWindow(null, null, null, List.of(bto)), List.of(),
                null);
        Precursor ion = new Precursor(null, null, null, List.of(new SelectedIon(1, null, null, List.of(bto))), null);
        Precursor activation = new Precursor(null, null, null, List.of(), new Activation(List.of(bto)));
        ScanEvent scan = new ScanEvent(1.0, null, null, null, null, null, List.of(), List.of(btoUnit));
        return Stream.of(
                Arguments.of("a spectrum param", description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of(
                        new Param("note", null, "n", null), bto)), null, "BTO:0000142"),
                Arguments.of("a scan param's unit", description("scan=1", 1, List.of(scan), List.of(), List.of()),
                        null, "BTO:0000001"),
                Arguments.of("an isolation window param", description("scan=1", 1, List.of(scan(1.0)),
                        List.of(window), List.of()), null, "BTO:0000142"),
                Arguments.of("a selected ion param", description("scan=1", 1, List.of(scan(1.0)), List.of(ion),
                        List.of()), null, "BTO:0000142"),
                Arguments.of("an activation param", description("scan=1", 1, List.of(scan(1.0)), List.of(activation),
                        List.of()), null, "BTO:0000142"),
                Arguments.of("a wavelength spectrum param", null, new WavelengthSpectrum(0, "uv=1", 0.5, null,
                        new double[]{200}, new double[]{1}, List.of(bto)), "BTO:0000142"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undeclaredPrefixes")
    void aSpectrumThatUsesAnUndeclaredCvPrefixIsRejectedAndNothingOfItIsKept(String name, SpectrumDescription bad,
                                                                            WavelengthSpectrum badUv, String curie) {
        Spectrum good = spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of()));
        WavelengthSpectrum goodUv = new WavelengthSpectrum(0, "uv=1", 0.5, null, new double[]{200}, new double[]{1});
        Contents contents = new Contents(metadata(), List.of(), List.of(known("MS"), known("UO")), List.of("scan=1"),
                List.of("uv=1"), List.of());
        Path target = dir.resolve("prefix.mzML");

        try (MzMLWriter writer = MzMLWriter.open(target, contents, true)) {
            if (bad != null) {
                assertThatThrownBy(() -> writer.write(spectrum(bad))).isInstanceOf(MzPeakException.class)
                        .hasMessageContaining(target.toString()).hasMessageContaining("spectrum #0 (scan=1)")
                        .hasMessageContaining("the CV prefix 'BTO' of " + curie + " is not in the cv list");
            }
            writer.write(good);
            if (badUv != null) {
                assertThatThrownBy(() -> writer.write(badUv)).isInstanceOf(MzPeakException.class)
                        .hasMessageContaining("wavelength spectrum #0 (uv=1)")
                        .hasMessageContaining("the CV prefix 'BTO' of " + curie + " is not in the cv list");
            }
            writer.write(goodUv);
            writer.finish();
            // what the rejected spectrum held is neither written nor counted: the type of the good one only
            assertThat(writer.warnings()).contains(MzMLWriter.A_7 + " (2)", MzMLWriter.A_16 + " (1)");
        }
        MzMLAssert.valid(target);
        ReadBack back = read(target);
        assertThat(back.spectra()).hasSize(1);
        assertThat(back.wavelengthSpectra()).hasSize(1);
        assertThat(back.header().cvList()).extracting(CvEntry::id).containsExactly("MS", "UO");
    }

    @Test
    void aCvPrefixTheContentsDeclareIsWritten() throws IOException {
        Param bto = new Param("tissue", "BTO:0000142", null, null);
        Spectrum s = spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of(bto)));
        Contents contents = new Contents(metadata(), List.of(), List.of(known("MS"), known("UO"), known("BTO")),
                List.of("scan=1"), List.of(), List.of());

        for (Written w : write("bto", contents, writer -> writer.write(s))) {
            assertThat(Files.readString(w.file(), UTF_8)).contains("<cvParam cvRef=\"BTO\" accession=\"BTO:0000142\"");
            assertThat(read(w.file()).spectra().get(0).description().parameters()).contains(bto);
        }
    }

    // ---- (i) determinism ----------------------------------------------------------------------------------------

    @Test
    void writingSmallTwiceGivesTheSameBytes() throws IOException {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            Contents contents = contents(reader);
            List<Path> files = List.of(dir.resolve("first.mzML"), dir.resolve("second.mzML"));
            for (Path file : files) {
                MzMLWriter writer = MzMLWriter.open(file, contents, true);
                for (int i = 0; i < reader.size(); i++) {
                    writer.write(reader.getSpectrumAt(i));
                }
                writer.finish();
            }
            MzMLAssert.valid(files.get(0));
            assertThat(read(files.get(0)).spectra()).hasSize(48);
            assertThat(Files.mismatch(files.get(0), files.get(1))).isEqualTo(-1L);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A written document: its file, whether it is zlib-compressed, and its writer's warnings. */
    private record Written(Path file, boolean zlib, List<String> warnings) {
        @Override
        public String toString() {
            return zlib ? "zlib" : "no zlib";
        }
    }

    /** What {@link MzMLReader} reads from a document. */
    private record ReadBack(MzMLReader.Header header, List<Spectrum> spectra,
                            List<WavelengthSpectrum> wavelengthSpectra, List<Chromatogram> chromatograms) {
    }

    /**
     * Writes {@code contents} with and without zlib to {@code <name>.zlib.mzML} and {@code <name>.plain.mzML},
     * {@code body} writing the spectra; each must be valid, and both give the same warnings.
     */
    private List<Written> write(String name, Contents contents, Consumer<MzMLWriter> body) {
        List<Written> written = new ArrayList<>();
        for (boolean zlib : List.of(true, false)) {
            Path file = dir.resolve(name + (zlib ? ".zlib" : ".plain") + ".mzML");
            MzMLWriter writer = MzMLWriter.open(file, contents, zlib);
            body.accept(writer);
            writer.finish();
            MzMLAssert.valid(file);
            written.add(new Written(file, zlib, writer.warnings()));
        }
        assertThat(written.get(0).warnings()).isEqualTo(written.get(1).warnings());
        return written;
    }

    private static ReadBack read(Path file) {
        MzMLReader reader = new MzMLReader(file);
        List<Spectrum> spectra = new ArrayList<>();
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>();
        List<Chromatogram> chromatograms = new ArrayList<>();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
                spectra.add(spectrum);
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                wavelengthSpectra.add(spectrum);
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
                chromatograms.add(chromatogram);
            }
        });
        return new ReadBack(reader.header(), spectra, wavelengthSpectra, chromatograms);
    }

    /**
     * The contents of a fixture as design 8.1 asks the conversion to give them: its file metadata, scan settings,
     * spectrum and wavelength spectrum ids and chromatograms, and its manifest's cv list followed by MS, UO and every
     * other CV prefix of an accession or unit of the spectrum-level and wavelength-level params, as
     * {@link CvEntry#known()} declares it (else with the prefix only), in that list's order.
     */
    private static Contents contents(MzPeakReader reader) {
        List<List<Param>> lists = new ArrayList<>();
        for (SpectrumDescription d : reader.metadata()) {
            lists.add(d.parameters());
            d.scans().forEach(s -> lists.add(s.parameters()));
            for (Precursor p : d.precursors()) {
                if (p.isolationWindow() != null) {
                    lists.add(p.isolationWindow().parameters());
                }
                p.selectedIons().forEach(ion -> lists.add(ion.parameters()));
                lists.add(p.activation().parameters());
            }
        }
        reader.wavelengthSpectra().forEach(w -> lists.add(w.parameters()));
        Set<String> used = new LinkedHashSet<>(List.of("MS", "UO"));
        for (List<Param> params : lists) {
            for (Param p : params) {
                for (String curie : Arrays.asList(p.accession(), p.unit())) {
                    if (curie != null && !curie.isEmpty()) {
                        int colon = curie.indexOf(':');
                        used.add(colon > 0 ? curie.substring(0, colon) : curie);
                    }
                }
            }
        }
        List<CvEntry> cvList = new ArrayList<>(reader.manifest().cvList());
        cvList.forEach(cv -> used.remove(cv.id()));
        for (CvEntry cv : CvEntry.known()) {
            if (used.remove(cv.id())) {
                cvList.add(cv);
            }
        }
        assertThat(used).as("prefixes no known CV declares").isEmpty();
        return new Contents(reader.fileMetadata(), reader.scanSettings(), cvList,
                reader.metadata().stream().map(SpectrumDescription::id).toList(),
                reader.wavelengthSpectra().stream().map(WavelengthSpectrum::id).toList(), reader.chromatograms());
    }

    /** The spectra of {@code file} as jmzML reads them. */
    private static List<uk.ac.ebi.jmzml.model.mzml.Spectrum> jmzmlSpectra(Path file) {
        List<uk.ac.ebi.jmzml.model.mzml.Spectrum> spectra = new ArrayList<>();
        MzMLObjectIterator<uk.ac.ebi.jmzml.model.mzml.Spectrum> it = new MzMLUnmarshaller(file.toFile(), false, null)
                .unmarshalCollectionFromXpath("/run/spectrumList/spectrum", uk.ac.ebi.jmzml.model.mzml.Spectrum.class);
        while (it.hasNext()) {
            spectra.add(it.next());
        }
        return spectra;
    }

    /** The spectrumRef of the precursor of the first chromatogram of {@code file}, as jmzML reads it. */
    private static String jmzmlChromatogramPrecursorRef(Path file) {
        MzMLObjectIterator<uk.ac.ebi.jmzml.model.mzml.Chromatogram> it = new MzMLUnmarshaller(file.toFile(), false,
                null).unmarshalCollectionFromXpath("/run/chromatogramList/chromatogram",
                uk.ac.ebi.jmzml.model.mzml.Chromatogram.class);
        return it.next().getPrecursor().getSpectrumRef();
    }

    private static List<String> accessions(ParamGroup group) {
        return group.getCvParam().stream().map(CVParam::getAccession).toList();
    }

    private static Arguments fixture(String kind, String name, Map<Exclusion, Integer> hidden) {
        return Arguments.of(kind + " " + name, kind.equals("current") ? Fixtures.current(name)
                : Fixtures.legacy(name), hidden);
    }

    /** Alternating exclusions and counts, in order. */
    private static Map<Exclusion, Integer> counts(Object... pairs) {
        Map<Exclusion, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            counts.put((Exclusion) pairs[i], (Integer) pairs[i + 1]);
        }
        return counts;
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static long[] bits(double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private static CvEntry known(String id) {
        return CvEntry.known().stream().filter(cv -> cv.id().equals(id)).findFirst().orElseThrow();
    }

    private static ScanEvent scan(double time) {
        return new ScanEvent(time, null, null, null, null, null, List.of(), List.of());
    }

    /** A profile spectrum description of positive polarity whose retention time is its first scan's start time. */
    private static SpectrumDescription description(String id, int msLevel, List<ScanEvent> scans,
                                                   List<Precursor> precursors, List<Param> params) {
        return new SpectrumDescription(0, id, msLevel, scans.isEmpty() ? Double.NaN : scans.get(0).startTime(),
                Polarity.POSITIVE, SignalContinuity.PROFILE, 1, 0, scans, precursors, params);
    }

    private static SpectrumDescription withTime(SpectrumDescription d, double retentionTime) {
        return new SpectrumDescription(d.index(), d.id(), d.msLevel(), retentionTime, d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), d.precursors(),
                d.parameters());
    }

    /** A description of unknown polarity and representation without params, with one scan. */
    private static SpectrumDescription bare(String id, int msLevel) {
        return new SpectrumDescription(0, id, msLevel, 1.0, Polarity.UNKNOWN, SignalContinuity.UNKNOWN, 1, 0,
                List.of(scan(1.0)), List.of(), List.of());
    }

    /** A spectrum of one point. */
    private static Spectrum spectrum(SpectrumDescription d) {
        return new Spectrum(d, new double[]{100}, new double[]{1}, null);
    }

    private static Spectrum peaks(double[] mz, double[] intensity, List<CentroidPeak> peaks,
                                  Map<String, double[]> arrays, Map<String, double[]> peakArrays) {
        return new Spectrum(description("scan=1", 1, List.of(scan(1.0)), List.of(), List.of()), mz, intensity, peaks,
                arrays, peakArrays);
    }

    private static CentroidPeak peak(double mz, double intensity, int index) {
        return new CentroidPeak(mz, intensity, index);
    }

    /** A small header: source file sf (the run's default), software sw, IC 0, data processing dp, run run. */
    private static FileMetadata metadata() {
        InstrumentConfiguration ic = new InstrumentConfiguration(0, List.of(
                new Component(ComponentType.ION_SOURCE, 1, List.of(SOURCE)),
                new Component(ComponentType.ANALYZER, 2, List.of(ANALYZER)),
                new Component(ComponentType.DETECTOR, 3, List.of(DETECTOR))), "sw", List.of());
        return new FileMetadata(new FileDescription(List.of(), List.of(new SourceFile("sf", "a.raw", "file:///data",
                List.of()))), List.of(ic), List.of(new DataProcessing("dp", List.of(new ProcessingMethod(0, "sw",
                List.of(new Param("Conversion to mzML", "MS:1000544", null, null)))))), List.of(),
                List.of(new Software("sw", "1.0", List.of())), new MsRun("run", 0, "dp", "sf", null, List.of()));
    }
}
