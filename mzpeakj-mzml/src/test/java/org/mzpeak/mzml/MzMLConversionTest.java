package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Model;
import org.mzpeak.mzml.ModelAssert.Result;
import uk.ac.ebi.jmzml.model.mzml.Scan;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-7: {@link MzMLConversion} in both directions (mzML design 3, 5.6, 5.10, 6.1, 6.2, 8.1, 12.4), under the default
 * time zone UTC, restored after each test. (a) Smoke round trips B: tiny and small, each read by {@link MzMLReader}
 * before and after mzML -> mzPeak -> mzML, equal up to A-26 for tiny, every output valid and the warnings pinned.
 * (b) Smoke round trips A: small.mzpeak, Example_Processed and legacy small.mzpeak through mzML and back, equal up to
 * the A exclusions of design 12.4, with the toMzML warnings and the cv lists pinned (A-17). (c) Branch documents derived from tiny, and mzPeak inputs written with {@link MzPeakWriter}: refs whose
 * kind is kept or changes (B-33), the losses the conversion counts (B-19, B-26, B-34, B-35, B-36), A-26, and the
 * removal of the mzPeak writer's stubs. (d) Failures: the clean-up after a failed conversion, the early target check of
 * {@code toMzML} and gzip input.
 */
class MzMLConversionTest {

    private static final String SCAN_19 = "id=\"scan=19\"";
    private static final String SCAN_20 = "id=\"scan=20\"";
    private static final String SCAN_21 = "id=\"scan=21\"";
    private static final String TINY_START = "startTimeStamp=\"2007-06-27T15:23:45.00035\"";
    /** The Base64 text of tiny's scan=19 m/z array: 0, 1, ..., 14 as 64-bit floats. */
    private static final String TINY_MZ_BINARY = "AAAAAAAAAAAAAAAAAADwPwAAAAAAAABAAAAAAAAACEAAAAAAAAAQQAAAAAAAABRA"
            + "AAAAAAAAGEAAAAAAAAAcQAAAAAAAACBAAAAAAAAAIkAAAAAAAAAkQAAAAAAAACZAAAAAAAAAKEAAAAAAAAAqQAAAAAAAACxA";
    private static final String F64 = cv("MS:1000523", "64-bit float", "", null);
    private static final String NO_COMPRESSION = cv("MS:1000576", "no compression", "", null);
    private static final String MZ_TYPE = cv("MS:1000514", "m/z array", "", "MS:1000040");
    private static final String INTENSITY_TYPE = cv("MS:1000515", "intensity array", "", "MS:1000131");
    private static final String SN = "MS:1000517";
    private static final String SN_TYPE = cv(SN, "signal to noise array", "", null);
    /** The loss lines of tiny's read() as JZ-4 pinned them (MzMLReaderRunTest.tinyIsReadInFull). */
    private static final List<String> TINY_LOSSES = List.of(
            "B-3 mzML@id and mzML@accession lost (1)",
            "B-1 referenceableParamGroups expanded inline (2)",
            "B-5 instrumentConfiguration ids [LCQ_x0020_Deca] numbered by position (1)",
            "B-7 run@sampleRef lost (1)",
            "B-21 lost array dataProcessingRef (10)",
            "B-10 spectrum@spotID lost (1)",
            "B-10 spectrum@sourceFileRef lost (1)",
            "B-15 times converted to minutes and drift times to milliseconds (3)",
            "B-27 chromatogram products dropped (1)");
    private static final String TINY_A_26 = String.format(MzMLWriter.A_26, "2007-06-27T15:23:45.00035",
            "2007-06-27T15:23:45Z") + " (1)";
    private static final SourceFile SOURCE_STUB = new SourceFile("mzpeakj_source", "unknown", "file:///unknown",
            List.of());
    private static final DataProcessing PROCESSING_STUB = new DataProcessing("mzpeakj_export", List.of());

    @TempDir
    Path dir;

    private TimeZone zone;

    @BeforeEach
    void utc() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    // ---- (a) smoke round trips B: mzML -> mzPeak -> mzML ---------------------------------------------------------

    @Test
    void tinyComesBackEqualUpToItsRewrittenRunStartTime() throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        B b = b(tiny, false);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(TINY_LOSSES));
        assertThat(b.toMzML.warnings()).containsExactlyElementsOf(writerLimits(TINY_A_26));
        assertThat(List.of(b.toMzPeak.spectra(), b.toMzPeak.wavelengthSpectra(), b.toMzPeak.chromatograms()))
                .containsExactly(4L, 0L, 2L);
        assertThat(List.of(b.toMzML.spectra(), b.toMzML.wavelengthSpectra(), b.toMzML.chromatograms()))
                .containsExactly(4L, 0L, 2L);
        assertThat(Files.isRegularFile(b.mzpeak)).isTrue();
        Model m3 = model(b.mzml);
        assertThat(m3.fileMetadata().run().startTime()).isEqualTo("2007-06-27T15:23:45Z");
        assertThat(ModelAssert.assertEqual("tiny B", model(tiny), m3, STRICT, ModelAssert.A_26).hidden())
                .containsExactly(Map.entry("A_26", 1));
    }

    @Test
    void smallComesBackEqual() throws IOException {
        Path small = MzMLFixtures.extract(MzMLFixtures.SMALL, dir);
        B b = b(small, true);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(List.of(
                "B-3 mzML@id and mzML@accession lost (1)", "B-1 referenceableParamGroups expanded inline (1)")));
        assertThat(b.toMzML.warnings()).containsExactlyElementsOf(writerLimits());
        assertThat(List.of(b.toMzPeak.spectra(), b.toMzPeak.wavelengthSpectra(), b.toMzPeak.chromatograms()))
                .containsExactly(48L, 0L, 1L);
        assertThat(Files.isDirectory(b.mzpeak)).isTrue();
        assertThat(ModelAssert.assertEqual("small B", model(small), model(b.mzml), STRICT).hidden()).isEmpty();
    }

    // ---- (b) smoke round trips A: mzPeak -> mzML -> mzPeak -------------------------------------------------------

    /**
     * Name, dataset, the exclusions of design 12.4 with the differences each hides, the number of spectra, the loss
     * lines of toMzML, the CVs of the mzML and of the mzPeak written from it, and whether the mzML of that mzPeak is
     * byte-identical to the first (design 12.5: the current fixtures are normal; the legacy ones gain the summary params
     * in the mzPeak). Legacy small declares no CV, so the mzML declares MS and UO (A-17, 2); Example_Processed uses
     * IMS too (A-17, 3), declared after UO as CvEntry.known() orders them.
     */
    static Stream<Arguments> mzPeakFixtures() {
        Map<Exclusion, Integer> example = new LinkedHashMap<>();
        example.put(ModelAssert.A_7, 9);
        example.put(ModelAssert.A_17, 1);
        Map<Exclusion, Integer> legacy = new LinkedHashMap<>();
        legacy.put(ModelAssert.A_7, 48);
        legacy.put(ModelAssert.A_9, 48);
        legacy.put(ModelAssert.A_16, 48 + 5 * 48); // a type and five summary params per spectrum
        legacy.put(ModelAssert.A_17, 1);
        return Stream.of(
                arguments("small.mzpeak", Fixtures.current("small.mzpeak"), Map.of(ModelAssert.A_7, 48), 48L,
                        List.of(MzMLWriter.A_7 + " (48)"), List.of("MS", "UO"), true),
                arguments("Example_Processed.img.mzpeak", Fixtures.current("Example_Processed.img.mzpeak"), example,
                        9L, List.of(MzMLWriter.A_17 + " (3)", MzMLWriter.A_7 + " (9)"), List.of("MS", "UO", "IMS"),
                        true),
                arguments("legacy small.mzpeak", Fixtures.legacy("small.mzpeak"), legacy, 48L,
                        List.of(MzMLWriter.A_17 + " (2)", MzMLWriter.A_16 + " (48)", MzMLWriter.A_9 + " (48)",
                                MzMLWriter.A_7 + " (48)"), List.of("MS", "UO"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzPeakFixtures")
    void mzPeakFixtureComesBackEqualUpToTheExclusionsOfA(String name, Path fixture, Map<Exclusion, Integer> hidden,
                                                         long spectra, List<String> losses, List<String> cvs,
                                                         boolean normal) throws IOException {
        Path mzml = dir.resolve("a.mzML");
        Path back = dir.resolve("back.mzpeak");

        MzMLConversion.Result toMzML = MzMLConversion.toMzML(fixture, mzml, true);
        MzMLAssert.valid(mzml);
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(mzml, back, false, MzPeakWriter.Options.defaults());

        assertThat(toMzML.spectra()).isEqualTo(spectra);
        assertThat(toMzPeak.spectra()).isEqualTo(spectra);
        assertThat(toMzML.warnings()).containsExactlyElementsOf(writerLimits(losses.toArray(String[]::new)));
        assertThat(toMzPeak.warnings()).startsWith(MzMLReader.LIMITS.toArray(String[]::new));
        assertThat(new MzMLReader(mzml).header().cvList()).extracting(CvEntry::id).containsExactlyElementsOf(cvs);
        Map<String, Integer> counts = new LinkedHashMap<>();
        hidden.forEach((exclusion, n) -> counts.put(exclusion.id(), n));
        Model a = Model.load(back);
        assertThat(a.cvList()).extracting(CvEntry::id).containsExactlyElementsOf(cvs);
        assertThat(ModelAssert.assertEqual(name + " A", Model.load(fixture), a, STRICT,
                hidden.keySet().toArray(Exclusion[]::new)).hidden()).isEqualTo(counts);
        Path again = dir.resolve("again.mzML");
        MzMLConversion.toMzML(back, again, true);
        assertThat(Files.mismatch(mzml, again) == -1L).as("the mzML of the mzPeak is the first").isEqualTo(normal);
    }

    /**
     * Wavelength spectra and chromatograms go both ways; a dataset without file description and run, whose spectra use
     * a CV that no cv list declares, is written with that CV completed as the prefix with URI 'unknown'.
     */
    @Test
    void aDatasetWithoutFileMetadataAndWithWavelengthSpectraAndAnUndeclaredCvGoesBothWays() throws IOException {
        Path input = dir.resolve("in.mzpeak");
        Precursor withoutWindow = new Precursor(0L, "scan=1", null, List.of(new SelectedIon(150, 2, null, List.of())),
                new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null))));
        Spectrum ms2 = spectrum(1, "scan=2", 2, List.of(withoutWindow),
                List.of(new Param("custom term", "XX:0000001", 1.5, null)));
        WavelengthSpectrum uv = new WavelengthSpectrum(0, "uv=1", 1.5, null, new double[] {200, 210},
                new double[] {0.5, 0.25}, List.of(new Param("absorption spectrum", "MS:1000806", null, null)));
        Chromatogram tic = new Chromatogram(0, "tic", "MS:1000235", new double[] {1, 2}, new double[] {3, 4},
                "MS:1000131", Map.of());
        MzPeakWriter.writeArchive(input, List.of(spectrum(0, "scan=1", 1, List.of(), List.of()), ms2), List.of(tic),
                List.of(uv), List.of(), FileMetadata.EMPTY);
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            assertThat(reader.fileMetadata().fileDescription()).isNull();
            assertThat(reader.fileMetadata().run()).isNull();
            assertThat(reader.manifest().cvList()).extracting(CvEntry::id).doesNotContain("XX");
            assertThat(reader.metadataAt(1).precursors().get(0).isolationWindow()).isNull();
        }

        Path mzml = dir.resolve("out.mzML");
        MzMLConversion.Result toMzML = MzMLConversion.toMzML(input, mzml, false);
        MzMLAssert.valid(mzml);
        assertThat(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms()))
                .containsExactly(2L, 1L, 1L);
        assertThat(toMzML.warnings()).contains(MzMLWriter.A_17 + " (1)", MzMLWriter.CV_COMPLETED + " (1)");
        assertThat(model(mzml).cvList()).containsExactly(CvEntry.known().get(0), CvEntry.known().get(1),
                new CvEntry("XX", "XX", "unknown", null));

        Path back = dir.resolve("back");
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(mzml, back, true, MzPeakWriter.Options.defaults());
        assertThat(List.of(toMzPeak.spectra(), toMzPeak.wavelengthSpectra(), toMzPeak.chromatograms()))
                .containsExactly(2L, 1L, 1L);
        try (MzPeakReader reader = MzPeakReader.open(back)) {
            WavelengthSpectrum w = reader.wavelengthSpectra().get(0);
            assertThat(w.id()).isEqualTo("uv=1");
            assertThat(w.wavelength()).containsExactly(200, 210);
            assertThat(w.intensity()).containsExactly(0.5, 0.25);
            assertThat(reader.chromatograms().get(0).intensity()).containsExactly(3, 4);
            assertThat(reader.metadataAt(1).parameters()).contains(new Param("custom term", "XX:0000001", 1.5, null));
        }
    }

    // ---- (c) branch documents ---------------------------------------------------------------------------------------

    @Test
    void anExternalPrecursorIdEqualToALocalIdStaysExternal() throws IOException {
        Path file = tinyWith(s -> replaceOnce(s, "<precursor spectrumRef=\"scan=19\">",
                "<precursor externalSpectrumID=\"scan=19\">"));
        Model m1 = model(file);
        Precursor precursor = m1.spectrumList().get(1).description().precursors().get(0);
        assertThat(precursor.precursorIndex()).isNull();
        assertThat(precursor.precursorId()).isEqualTo("scan=19");

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).noneMatch(w -> w.startsWith("B-33"));
        uk.ac.ebi.jmzml.model.mzml.Precursor written = jmzmlSpectra(b.mzml).get(1).getPrecursorList().getPrecursor()
                .get(0);
        assertThat(written.getExternalSpectrumID()).isEqualTo("scan=19");
        assertThat(written.getSpectrumRef()).isNull();
        ModelAssert.assertEqual("external precursor", m1, model(b.mzml), STRICT, ModelAssert.A_26);
    }

    @Test
    void aScanExternalSpectrumIdEqualToALocalIdComesBackAsSpectrumRef() throws IOException {
        Path file = tinyWith(s -> replaceAfter(s, SCAN_20, "<scan instrumentConfigurationRef=\"LCQ_x0020_Deca\">",
                "<scan instrumentConfigurationRef=\"LCQ_x0020_Deca\" externalSpectrumID=\"scan=19\">"));
        Model m1 = model(file);
        assertThat(m1.spectrumList().get(1).description().scans().get(0).spectrumReference()).isEqualTo("scan=19");

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).contains(MzMLReader.B_33 + " (1)");
        Scan scan = jmzmlSpectra(b.mzml).get(1).getScanList().getScan().get(0);
        assertThat(scan.getSpectrumRef()).isEqualTo("scan=19");
        assertThat(scan.getExternalSpectrumID()).isNull();
        ModelAssert.assertEqual("scan external id", m1, model(b.mzml), STRICT, ModelAssert.A_26);
    }

    @Test
    void aNaNMzIsCountedAndInterpolatedBetweenItsNeighbours() throws IOException {
        double[] mz = new double[15];
        for (int i = 0; i < mz.length; i++) {
            mz[i] = i;
        }
        mz[5] = Double.NaN;
        Path file = tinyWith(s -> replaceAfter(s, SCAN_19, TINY_MZ_BINARY, floats(mz)));
        Model m1 = model(file);
        assertThat(m1.spectrumList().get(0).mz()[5]).isNaN();

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(TINY_LOSSES,
                MzMLConversion.B_34 + " (1)"));
        Model m3 = model(b.mzml);
        double filled = m3.spectrumList().get(0).mz()[5];
        assertThat(filled).isBetween(4.0, 6.0);
        assertThat(ModelAssert.assertEqual("NaN m/z", m1, m3, STRICT, ModelAssert.A_26, ModelAssert.B_34).hidden())
                .containsExactly(Map.entry("A_26", 1), Map.entry("B_34", 1));
    }

    @Test
    void aNonFiniteFileLevelParamComesBackAsAString() throws IOException {
        Path file = tinyWith(s -> replaceAfter(s, "<fileContent>", "name=\"centroid spectrum\" value=\"\"",
                "name=\"centroid spectrum\" value=\"INF\""));
        Model m1 = model(file);
        assertThat(Param.find(m1.fileMetadata().fileDescription().contents(), "MS:1000127").orElseThrow().value())
                .isEqualTo(Double.POSITIVE_INFINITY);

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(TINY_LOSSES,
                MzMLConversion.B_35 + " (1)"));
        try (MzPeakReader reader = MzPeakReader.open(b.mzpeak)) {
            assertThat(Param.find(reader.fileMetadata().fileDescription().contents(), "MS:1000127").orElseThrow()
                    .value()).isEqualTo("Infinity");
        }
        Result result = ModelAssert.compare(m1, model(b.mzml), STRICT, ModelAssert.A_26);
        assertThat(result.paths()).containsExactly("fileMetadata.fileDescription.contents[MS:1000127]");
    }

    /** B-36 for the tic, emptied with its intensity unit; the sic, emptied without one, is not counted. */
    @Test
    void anEmptyChromatogramWithAnIntensityUnitLosesIt() throws IOException {
        String time = cv("MS:1000595", "time array", "", "UO:0000010");
        Path file = tinyWith(s -> replaceRegion(replaceRegion(s, "<chromatogram index=\"0\" id=\"tic\"",
                "</chromatogram>", chromatogram(0, "tic", "MS:1000235", array(time), array(INTENSITY_TYPE))),
                "<chromatogram index=\"1\" id=\"sic\"", "</chromatogram>", chromatogram(1, "sic", "MS:1000627",
                        array(time), array(cv("MS:1000515", "intensity array", "", null)))));
        Model m1 = model(file);
        assertThat(m1.chromatograms()).extracting(Chromatogram::size).containsExactly(0, 0);
        assertThat(m1.chromatograms()).extracting(Chromatogram::intensityUnit).containsExactly("MS:1000131", null);

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).contains(MzMLConversion.B_36 + " (1)");
        try (MzPeakReader reader = MzPeakReader.open(b.mzpeak)) {
            assertThat(reader.chromatograms()).extracting(Chromatogram::intensityUnit).containsExactly(null, null);
        }
        Result result = ModelAssert.compare(m1, model(b.mzml), STRICT, ModelAssert.A_26);
        assertThat(result.paths()).containsExactly("chromatogram[0].intensityUnit");
    }

    @Test
    void anExtraArrayOfNaNOnlyIsCountedAndAbsentAfterB() throws IOException {
        Path file = tinyWith(s -> spectrum19With(s, 2, array(MZ_TYPE, 100, 200), array(INTENSITY_TYPE, 1, 2),
                array(SN_TYPE, Double.NaN, Double.NaN)));
        MzMLReader reader = new MzMLReader(file);
        assertThat(reader.survey().spectrumArrays()).containsExactly(SN);
        Model m1 = model(file);
        assertThat(m1.spectrumList().get(0).arrays()).containsOnlyKeys(SN);

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).contains(MzMLConversion.B_26 + " (1)");
        try (MzPeakReader mzpeak = MzPeakReader.open(b.mzpeak)) {
            assertThat(mzpeak.getSpectrumAt(0).arrays()).isEmpty();
        }
        Model m3 = model(b.mzml);
        assertThat(m3.spectrumList().get(0).arrays()).isEmpty();
        assertThat(ModelAssert.compare(m1, m3, STRICT, ModelAssert.A_26).paths())
                .containsExactly("spectrum[0].arrays[" + SN + "]");
    }

    /**
     * B-26 counts an empty extra array of a spectrum and a NaN-only one of a chromatogram, not one with a number; the
     * mzPeak read drops exactly the counted ones.
     */
    @Test
    void emptyExtraArraysAndThoseOfChromatogramsAreCountedToo() throws IOException {
        double[] ramp = new double[15];
        double[] nan = new double[15];
        for (int i = 0; i < ramp.length; i++) {
            ramp[i] = i + 1;
            nan[i] = Double.NaN;
        }
        String msLevel = cv("MS:1000786", "non-standard data array", "ms level", "UO:0000186");
        Path file = tinyWith(s -> withArray(withArray(withArray(s, SCAN_19, array(SN_TYPE, ramp)), SCAN_21,
                array(SN_TYPE)), "id=\"tic\"", array(msLevel, nan)));

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).contains(MzMLConversion.B_26 + " (2)");
        try (MzPeakReader mzpeak = MzPeakReader.open(b.mzpeak)) {
            assertThat(mzpeak.getSpectrumAt(0).arrays()).containsOnlyKeys(SN);
            assertThat(mzpeak.getSpectrumAt(2).arrays()).isEmpty();
            assertThat(mzpeak.chromatograms().get(0).arrays()).isEmpty();
        }
    }

    @Test
    void aRunStartTimeJmzmlRewritesIsCountedAndHiddenByA26() throws IOException {
        Path file = tinyWith(s -> replaceOnce(s, TINY_START, "startTimeStamp=\"2005-07-20T19:44:22.1234Z\""));

        B b = b(file, false);

        assertThat(b.toMzML.warnings()).containsExactlyElementsOf(writerLimits(String.format(MzMLWriter.A_26,
                "2005-07-20T19:44:22.1234Z", "2005-07-20T19:44:22.123Z") + " (1)"));
        Model m3 = model(b.mzml);
        assertThat(m3.fileMetadata().run().startTime()).isEqualTo("2005-07-20T19:44:22.123Z");
        assertThat(ModelAssert.assertEqual("A-26", model(file), m3, STRICT, ModelAssert.A_26).hidden())
                .containsExactly(Map.entry("A_26", 1));
    }

    /**
     * tiny without source files: the mzPeak writer adds its source file stub and names it the run's default, and
     * toMzML removes both, so the mzML comes back without source file and run defaultSourceFileRef.
     */
    @Test
    void theSourceFileStubOfTheMzPeakWriterIsRemoved() throws IOException {
        Path file = tinyWith(s -> replaceOnce(replaceOnce(cut(cut(s, "<sourceFileList", "</sourceFileList>"),
                "<sourceFileRefList", "</sourceFileRefList>"), " defaultSourceFileRef=\"tiny1.yep\"", ""),
                " sourceFileRef=\"tiny.wiff\"", ""));
        Model m1 = model(file);
        assertThat(m1.fileMetadata().fileDescription().sourceFiles()).isEmpty();
        assertThat(m1.fileMetadata().run().defaultSourceFileId()).isNull();

        B b = b(file, false);

        try (MzPeakReader reader = MzPeakReader.open(b.mzpeak)) {
            assertThat(reader.fileMetadata().fileDescription().sourceFiles()).containsExactly(SOURCE_STUB);
            assertThat(reader.fileMetadata().run().defaultSourceFileId()).isEqualTo("mzpeakj_source");
        }
        String mzml = Files.readString(b.mzml, StandardCharsets.UTF_8);
        assertThat(mzml).doesNotContain("<sourceFile", "defaultSourceFileRef", "mzpeakj_source");
        assertThat(b.toMzML.warnings()).containsExactlyElementsOf(writerLimits(TINY_A_26));
        ModelAssert.assertEqual("without source files", m1, model(b.mzml), STRICT, ModelAssert.A_26);
    }

    /**
     * A run without source files and data processings: the mzPeak writer adds both stubs and names them the run's
     * defaults; toMzML removes them and the defaults, and the mzML writer synthesizes the data processing mzML
     * requires.
     */
    @Test
    void bothStubsOfTheMzPeakWriterAndTheRunDefaultsNamingThemAreRemoved() throws IOException {
        Path input = dir.resolve("in.mzpeak");
        MzPeakWriter.writeArchive(input, List.of(spectrum(0, "scan=1", 1, List.of(), List.of())), List.of(),
                metadata(List.of(), List.of(), new MsRun("run1", null, null, null, null, List.of())));
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            FileMetadata m = reader.fileMetadata();
            assertThat(m.fileDescription().sourceFiles()).containsExactly(SOURCE_STUB);
            assertThat(m.dataProcessingMethods()).containsExactly(PROCESSING_STUB);
            assertThat(List.of(m.run().defaultSourceFileId(), m.run().defaultDataProcessingId()))
                    .containsExactly("mzpeakj_source", "mzpeakj_export");
        }
        Path mzml = dir.resolve("out.mzML");

        MzMLConversion.toMzML(input, mzml, true);

        MzMLAssert.valid(mzml);
        assertThat(Files.readString(mzml, StandardCharsets.UTF_8)).doesNotContain("mzpeakj_source", "mzpeakj_export",
                "<sourceFile", "defaultSourceFileRef");
        FileMetadata m = model(mzml).fileMetadata();
        assertThat(m.fileDescription().sourceFiles()).isEmpty();
        assertThat(m.dataProcessingMethods()).extracting(DataProcessing::id).containsExactly("mzpeakj_conversion");
        assertThat(m.run().id()).isEqualTo("run1");
        assertThat(m.run().defaultSourceFileId()).isNull();
    }

    /** The exact stubs are removed also next to other entries, and a run default that names another one is kept. */
    @Test
    void stubsAreRemovedNextToOtherEntriesAndOtherRunDefaultsKept() throws IOException {
        SourceFile source = new SourceFile("sf1", "a.raw", "file:///data/a.raw", List.of());
        DataProcessing processing = new DataProcessing("dp1", List.of(new ProcessingMethod(0, null,
                List.of(new Param("Conversion to mzML", "MS:1000544", null, null)))));
        Path input = dir.resolve("in.mzpeak");
        MzPeakWriter.writeArchive(input, List.of(spectrum(0, "scan=1", 1, List.of(), List.of())), List.of(),
                metadata(List.of(SOURCE_STUB, source), List.of(PROCESSING_STUB, processing),
                        new MsRun("run1", null, "dp1", "sf1", null, List.of())));
        Path mzml = dir.resolve("out.mzML");

        MzMLConversion.toMzML(input, mzml, true);

        MzMLAssert.valid(mzml);
        FileMetadata m = model(mzml).fileMetadata();
        assertThat(m.fileDescription().sourceFiles()).containsExactly(source);
        assertThat(m.dataProcessingMethods()).extracting(DataProcessing::id).containsExactly("dp1");
        assertThat(List.of(m.run().defaultSourceFileId(), m.run().defaultDataProcessingId()))
                .containsExactly("sf1", "dp1");
    }

    /** Only the exact stubs are removed: a source file or data processing of their id with content stays. */
    @Test
    void entriesWithTheIdsOfTheStubsButWithContentAreKept() throws IOException {
        Param sha1 = new Param("SHA-1", "MS:1000569", "1234567890123456789012345678901234567890", null);
        SourceFile source = new SourceFile("mzpeakj_source", "unknown", "file:///unknown", List.of(sha1));
        DataProcessing processing = new DataProcessing("mzpeakj_export", List.of(new ProcessingMethod(0, null,
                List.of(new Param("Conversion to mzML", "MS:1000544", null, null)))));
        Path input = dir.resolve("in.mzpeak");
        MzPeakWriter.writeArchive(input, List.of(spectrum(0, "scan=1", 1, List.of(), List.of())), List.of(),
                metadata(List.of(source), List.of(processing),
                        new MsRun("run1", null, "mzpeakj_export", "mzpeakj_source", null, List.of())));
        Path mzml = dir.resolve("out.mzML");

        MzMLConversion.toMzML(input, mzml, true);

        MzMLAssert.valid(mzml);
        FileMetadata m = model(mzml).fileMetadata();
        assertThat(m.fileDescription().sourceFiles()).containsExactly(source);
        assertThat(m.dataProcessingMethods()).extracting(DataProcessing::id).containsExactly("mzpeakj_export");
        assertThat(List.of(m.run().defaultSourceFileId(), m.run().defaultDataProcessingId()))
                .containsExactly("mzpeakj_source", "mzpeakj_export");
    }

    @Test
    void theSelectedIonsOfPrecursorsWithTheSameParentMoveToTheFirst() throws IOException {
        Path file = tinyWith(s -> {
            String precursor = region(s, "<precursor spectrumRef=\"scan=19\">", "</precursor>");
            String second = precursor.replace("value=\"445.33999999999997\"", "value=\"446.5\"");
            return replaceOnce(replaceOnce(s, "<precursorList count=\"1\">", "<precursorList count=\"2\">"),
                    precursor, precursor + "\n" + second);
        });
        List<Precursor> given = model(file).spectrumList().get(1).description().precursors();
        assertThat(given).extracting(Precursor::precursorIndex).containsExactly(0L, 0L);

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(TINY_LOSSES,
                MzMLConversion.B_19 + " (1)"));
        try (MzPeakReader reader = MzPeakReader.open(b.mzpeak)) {
            List<Precursor> precursors = reader.metadataAt(1).precursors();
            assertThat(precursors).hasSize(2);
            assertThat(precursors.get(0).selectedIons()).extracting(SelectedIon::mz)
                    .containsExactly(445.33999999999997, 446.5);
            assertThat(precursors.get(1).selectedIons()).isEmpty();
            assertThat(precursors).extracting(p -> p.isolationWindow().targetMz()).containsExactly(445.3, 445.3);
            assertThat(precursors).allSatisfy(p -> assertThat(p.activation().parameters()).extracting(Param::accession)
                    .contains("MS:1000133", "MS:1000045"));
        }
    }

    /** Precursors with other parents, and a later one with the same parent but without ions, move nothing. */
    @Test
    void precursorsWithOtherParentsOrWithoutIonsMoveNothing() throws IOException {
        Path file = tinyWith(s -> {
            String precursor = region(s, "<precursor spectrumRef=\"scan=19\">", "</precursor>");
            String other = precursor.replace("spectrumRef=\"scan=19\"", "spectrumRef=\"scan=21\"");
            String withoutIons = cut(precursor, "<selectedIonList", "</selectedIonList>");
            return replaceOnce(replaceOnce(s, "<precursorList count=\"1\">", "<precursorList count=\"3\">"),
                    precursor, precursor + "\n" + other + "\n" + withoutIons);
        });

        B b = b(file, false);

        assertThat(b.toMzPeak.warnings()).containsExactlyElementsOf(withLimits(TINY_LOSSES));
        try (MzPeakReader reader = MzPeakReader.open(b.mzpeak)) {
            List<Precursor> precursors = reader.metadataAt(1).precursors();
            assertThat(precursors).extracting(Precursor::precursorIndex).containsExactly(0L, 2L, 0L);
            assertThat(precursors).extracting(p -> p.selectedIons().size()).containsExactly(1, 1, 0);
        }
    }

    // ---- (d) failures ---------------------------------------------------------------------------------------------

    /** A PIC count of 3e9 fails the read (guard 3); the session is closed and nothing remains next to the target. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedConversionLeavesNoTargetAndNoTempFile(boolean directory) throws IOException {
        double[] counts = new double[15];
        for (int i = 0; i < 14; i++) {
            counts[i] = 15 - i;
        }
        counts[14] = 3e9;
        Path file = tinyWith(s -> spectrum19With(s, 15, array(MZ_TYPE, ramp(15)), picArray(counts)));
        Path out = Files.createDirectory(dir.resolve("out"));
        Path target = out.resolve("pic.mzpeak");

        assertThatThrownBy(() -> MzMLConversion.toMzPeak(file, target, directory, MzPeakWriter.Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("mzML " + file + ": spectrum #0 (scan=19): ")
                .hasMessageContaining("3000000000");
        try (Stream<Path> left = Files.list(out)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void toMzMLRefusesAnExistingTargetBeforeItOpensTheInput() throws IOException {
        Path target = Files.writeString(dir.resolve("exists.mzML"), "kept");
        Path missing = dir.resolve("missing.mzpeak");

        assertThatThrownBy(() -> MzMLConversion.toMzML(missing, target, true))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + target + ": the target exists; it is never replaced");
        assertThat(Files.readString(target)).isEqualTo("kept");
        assertThatThrownBy(() -> MzMLConversion.toMzML(missing, dir.resolve("new.mzML"), true))
                .isInstanceOf(MzPeakException.class)
                .hasMessageNotContaining("the target exists");
    }

    @Test
    void toMzPeakRefusesAnExistingTarget() throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path target = Files.writeString(dir.resolve("exists.mzpeak"), "kept");

        assertThatThrownBy(() -> MzMLConversion.toMzPeak(tiny, target, false, MzPeakWriter.Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining(target.toString());
        assertThat(Files.readString(target)).isEqualTo("kept");
    }

    @Test
    void toMzPeakRejectsGzipInput() throws IOException {
        Path gz = Files.copy(MzMLFixtures.extract(MzMLFixtures.TINY, dir), dir.resolve("tiny.mzML.gz"));
        Path target = dir.resolve("tiny.mzpeak");

        assertThatThrownBy(() -> MzMLConversion.toMzPeak(gz, target, false, MzPeakWriter.Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("gunzip it first");
        assertThat(target).doesNotExist();
    }

    @Test
    void nullArgumentsAreRejected() {
        Path p = dir.resolve("x");
        MzPeakWriter.Options options = MzPeakWriter.Options.defaults();
        assertThatThrownBy(() -> MzMLConversion.toMzPeak(null, p, false, options))
                .isInstanceOf(NullPointerException.class).hasMessage("mzml");
        assertThatThrownBy(() -> MzMLConversion.toMzPeak(p, null, false, options))
                .isInstanceOf(NullPointerException.class).hasMessage("target");
        assertThatThrownBy(() -> MzMLConversion.toMzPeak(p, p, false, null))
                .isInstanceOf(NullPointerException.class).hasMessage("options");
        assertThatThrownBy(() -> MzMLConversion.toMzML(null, p, true))
                .isInstanceOf(NullPointerException.class).hasMessage("mzpeak");
        assertThatThrownBy(() -> MzMLConversion.toMzML(p, null, true))
                .isInstanceOf(NullPointerException.class).hasMessage("target");
        List<String> warnings = new ArrayList<>(List.of("w"));
        MzMLConversion.Result result = new MzMLConversion.Result(1, 2, 3, warnings);
        warnings.add("later");
        assertThat(result.warnings()).containsExactly("w").isUnmodifiable();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The results and outputs of direction B. */
    private record B(MzMLConversion.Result toMzPeak, Path mzpeak, MzMLConversion.Result toMzML, Path mzml) {
    }

    /**
     * {@code file} -> mzPeak (a directory if {@code directory}, else an archive) -> mzML with zlib, the output
     * checked by {@link MzMLAssert#valid}.
     */
    private B b(Path file, boolean directory) {
        Path mzpeak = dir.resolve("b.mzpeak");
        Path mzml = dir.resolve("b.mzML");
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(file, mzpeak, directory,
                MzPeakWriter.Options.defaults());
        MzMLConversion.Result toMzML = MzMLConversion.toMzML(mzpeak, mzml, true);
        MzMLAssert.valid(mzml);
        return new B(toMzPeak, mzpeak, toMzML, mzml);
    }

    /** The model {@link MzMLReader} reads from {@code file}. */
    private static Model model(Path file) {
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
        MzMLReader.Header header = reader.header();
        return new Model(spectra, chromatograms, wavelengthSpectra, header.fileMetadata(), header.scanSettings(),
                header.cvList());
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

    /** The limit lines every reader notes, then {@code losses}, then {@code more}. */
    private static List<String> withLimits(List<String> losses, String... more) {
        List<String> lines = new ArrayList<>(MzMLReader.LIMITS);
        lines.addAll(losses);
        lines.addAll(List.of(more));
        return lines;
    }

    /** The limit lines every writer notes, then {@code losses}. */
    private static List<String> writerLimits(String... losses) {
        List<String> lines = new ArrayList<>(MzMLWriter.LIMITS);
        lines.addAll(List.of(losses));
        return lines;
    }

    /**
     * An MS spectrum of three centroids with one scan at its index plus one minute, its type by its MS level, and
     * {@code precursors} and {@code params}.
     */
    private static Spectrum spectrum(long index, String id, int msLevel, List<Precursor> precursors,
                                     List<Param> params) {
        List<Param> parameters = new ArrayList<>(List.of(msLevel == 1
                ? new Param("MS1 spectrum", "MS:1000579", null, null)
                : new Param("MSn spectrum", "MS:1000580", null, null)));
        parameters.addAll(params);
        SpectrumDescription d = new SpectrumDescription(index, id, msLevel, index + 1.0, Polarity.POSITIVE,
                SignalContinuity.CENTROID, 0, 3,
                List.of(new ScanEvent(index + 1.0, null, null, null, null, null, List.of(), List.of())), precursors,
                parameters);
        return new Spectrum(d, new double[] {100, 200, 300}, new double[] {1, 2, 3}, List.of());
    }

    /** File metadata with {@code sources}, {@code processing} and {@code run}, and nothing else. */
    private static FileMetadata metadata(List<SourceFile> sources, List<DataProcessing> processing, MsRun run) {
        return new FileMetadata(new FileDescription(List.of(), sources), List.of(), processing, List.of(), List.of(),
                run);
    }

    private Path tinyWith(UnaryOperator<String> edit) throws IOException {
        Path source = dir.resolve("source");
        Files.createDirectories(source);
        String original = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, source),
                StandardCharsets.ISO_8859_1);
        String edited = edit.apply(original);
        assertThat(edited).as("the edit changes the document").isNotEqualTo(original);
        return Files.writeString(dir.resolve("tiny-edited.mzML"), edited, StandardCharsets.ISO_8859_1);
    }

    /** {@code s} with its single occurrence of {@code target} replaced. */
    private static String replaceOnce(String s, String target, String replacement) {
        int at = s.indexOf(target);
        assertThat(at).as("'%s' occurs", target).isNotNegative();
        assertThat(s.indexOf(target, at + 1)).as("'%s' occurs once", target).isNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** {@code s} with the first occurrence of {@code target} after {@code anchor} replaced. */
    private static String replaceAfter(String s, String anchor, String target, String replacement) {
        int from = s.indexOf(anchor);
        assertThat(from).as("'%s' occurs", anchor).isNotNegative();
        int at = s.indexOf(target, from);
        assertThat(at).as("'%s' occurs after '%s'", target, anchor).isNotNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** The region of {@code s} from the first {@code start} to the next {@code end}, inclusive. */
    private static String region(String s, String start, String end) {
        int from = s.indexOf(start);
        assertThat(from).as("'%s' occurs", start).isNotNegative();
        int to = s.indexOf(end, from);
        assertThat(to).as("'%s' occurs after '%s'", end, start).isNotNegative();
        return s.substring(from, to + end.length());
    }

    /** {@code s} with the region from the first {@code start} to the next {@code end}, inclusive, replaced. */
    private static String replaceRegion(String s, String start, String end, String by) {
        String region = region(s, start, end);
        int from = s.indexOf(region);
        return s.substring(0, from) + by + s.substring(from + region.length());
    }

    /** {@code s} without the region from the first {@code start} to the next {@code end}, inclusive. */
    private static String cut(String s, String start, String end) {
        return replaceRegion(s, start, end, "");
    }

    /** {@code s} with {@code array} added as the last binaryDataArray of the element at {@code anchor}. */
    private static String withArray(String s, String anchor, String array) {
        String region = region(s, anchor, "</binaryDataArrayList>");
        int count = region.split("<binaryDataArray ", -1).length - 1;
        String edited = region.replace("<binaryDataArrayList count=\"" + count + "\">",
                "<binaryDataArrayList count=\"" + (count + 1) + "\">")
                .replace("</binaryDataArrayList>", array + "</binaryDataArrayList>");
        return replaceOnce(s, region, edited);
    }

    /** {@code s} with scan=19 of default array length {@code length} holding the binaryDataArrays {@code arrays}. */
    private static String spectrum19With(String s, int length, String... arrays) {
        int start = s.indexOf(SCAN_19);
        int from = s.indexOf("<binaryDataArrayList", start);
        int end = s.indexOf("</binaryDataArrayList>", from) + "</binaryDataArrayList>".length();
        String spectrum = s.substring(start, from).replace("defaultArrayLength=\"15\"",
                "defaultArrayLength=\"" + length + "\"");
        return s.substring(0, start) + spectrum + "<binaryDataArrayList count=\"" + arrays.length + "\">\n"
                + String.join("", arrays) + "</binaryDataArrayList>" + s.substring(end);
    }

    /** A chromatogram without precursor of the type {@code type}, holding the binaryDataArrays {@code arrays}. */
    private static String chromatogram(int index, String id, String type, String... arrays) {
        return "<chromatogram index=\"" + index + "\" id=\"" + id + "\" defaultArrayLength=\"0\">\n"
                + cv(type, MzMLTerms.name(type), "", null) + "\n<binaryDataArrayList count=\"" + arrays.length
                + "\">\n" + String.join("", arrays) + "</binaryDataArrayList>\n</chromatogram>";
    }

    /** A binaryDataArray of 64-bit floats without compression, with the array type term {@code type}. */
    private static String array(String type, double... values) {
        String base64 = floats(values);
        return "<binaryDataArray encodedLength=\"" + base64.length() + "\">\n" + F64 + "\n" + NO_COMPRESSION + "\n"
                + type + "\n<binary>" + base64 + "</binary>\n</binaryDataArray>\n";
    }

    /** An intensity array of 64-bit floats holding {@code counts} as an MS-Numpress PIC payload (MS:1002313). */
    private static String picArray(double[] counts) {
        byte[] buffer = new byte[5 * counts.length];
        String base64 = Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,
                MSNumpress.encodePic(counts, counts.length, buffer)));
        return "<binaryDataArray encodedLength=\"" + base64.length() + "\">\n" + F64 + "\n"
                + cv("MS:1002313", "MS-Numpress positive integer compression", "", null) + "\n" + INTENSITY_TYPE
                + "\n<binary>" + base64 + "</binary>\n</binaryDataArray>\n";
    }

    /** A cvParam element; the cvRef and unitCvRef are the prefixes of the accessions. */
    private static String cv(String accession, String name, String value, String unit) {
        String units = unit == null ? ""
                : " unitCvRef=\"" + unit.substring(0, unit.indexOf(':')) + "\" unitAccession=\"" + unit + "\"";
        return "<cvParam cvRef=\"" + accession.substring(0, accession.indexOf(':')) + "\" accession=\"" + accession
                + "\" name=\"" + name + "\" value=\"" + value + "\"" + units + "/>";
    }

    private static String floats(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            buffer.putDouble(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /** 1, 2, ..., n. */
    private static double[] ramp(int n) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = i + 1;
        }
        return values;
    }
}
