package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
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
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Mode;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * MZ-17: {@link MzMLReader#survey()} and {@link MzMLReader#read(MzMLReader.Visitor)} (mzML design 5.2, 5.4-5.9, 6.1,
 * 6.3, 8.1). small is read against small.mzpeak (a), TOFsulfas against has_uv.mzpeak (b), diaPASEF against
 * diaPASEF.ref.mzpeak (c), all three made from the same mzML by the reference converter; tiny is pinned in full (d);
 * documents derived from tiny, or from TOFsulfas for wavelength spectra, by string edits pin each rule, loss and
 * rejection (e). Every read that completes is checked against its survey (f): the keys of {@code arrays()} over the MS
 * spectra, in first-seen order, are {@code survey().spectrumArrays()}, and ids and counts agree; its warnings start
 * with the limit lines {@link MzMLReader#LIMITS}. JZ-3 moved the run region onto jmzML: errors name the element as
 * {@code mzML <file>: spectrum #<n> (<id>)}, and the guards 2 and 3 and jmzML's codec are pinned in (e).
 */
class MzMLReaderRunTest {

    private static final String SCAN_19 = "id=\"scan=19\"";
    private static final String SCAN_20 = "id=\"scan=20\"";
    private static final String SCAN_21 = "id=\"scan=21\"";
    private static final String CYCLE_22 = "id=\"sample=1 period=1 cycle=22 experiment=1\"";
    private static final String UV_0 = "id=\"merged=212 row=0\"";
    private static final String UV_1 = "id=\"merged=213 row=1\"";
    private static final String UV_2 = "id=\"merged=214 row=2\"";
    private static final String F64 = cv("MS:1000523", "64-bit float", "", null);
    private static final String I64 = cv("MS:1000522", "64-bit integer", "", null);
    private static final String NO_COMPRESSION = cv("MS:1000576", "no compression", "", null);
    private static final String SN = "MS:1000517";
    private static final String SN_TYPE = cv(SN, "signal to noise array", "", null);
    private static final String TINY_MZ_UNIT = "accession=\"MS:1000514\" name=\"m/z array\" value=\"\" unitCvRef=\"MS\""
            + " unitAccession=\"MS:1000040\"";
    private static final String ARRAY_DP_LOST = "B-21 lost array dataProcessingRef";
    /** The Base64 text of tiny's scan=19 intensity array: 15, 14, ..., 1 as 64-bit floats. */
    private static final String TINY_INTENSITY_BINARY = "AAAAAAAALkAAAAAAAAAsQAAAAAAAACpAAAAAAAAAKEAAAAAAAAAmQAAAAAAAACRA"
            + "AAAAAAAAIkAAAAAAAAAgQAAAAAAAABxAAAAAAAAAGEAAAAAAAAAUQAAAAAAAABBAAAAAAAAACEAAAAAAAAAAQAAAAAAAAPA/";
    /** The combined terms of MS-Numpress followed by zlib, which jmzML cannot decode (guard 2). */
    private static final List<String> NUMPRESS_ZLIB = List.of("MS:1002746", "MS:1002747", "MS:1002748");
    private static final String NONE = "MS:1000576";
    private static final String ZLIB = "MS:1000574";
    private static final String LINEAR = "MS:1002312";
    private static final String PIC = "MS:1002313";
    private static final String SLOF = "MS:1002314";
    /** The data types jmzML decodes: 32-bit integer, 32-bit float, 64-bit integer, 64-bit float. */
    private static final List<String> NUMERIC_DATA_TYPES = List.of("MS:1000519", "MS:1000521", "MS:1000522",
            "MS:1000523");

    /** TOFsulfas, uncompressed, for the documents derived from it. */
    private static String tofSulfas;

    @BeforeAll
    static void loadTofSulfas(@TempDir Path dir) throws IOException {
        tofSulfas = Files.readString(MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir), StandardCharsets.UTF_8);
    }

    // ---- (a) small against small.mzpeak --------------------------------------------------------------------------

    private static final Pattern SUMMARY = Pattern.compile(
            "spectrum\\[\\d+]\\.parameters\\[(MS:1000528|MS:1000527|MS:1000504|MS:1000505|MS:1000285)]");
    private static final Pattern COMBINATION = Pattern.compile("spectrum\\[\\d+]\\.parameters\\[MS:1000795]");
    private static final Pattern FLOAT32_PATH = Pattern.compile("spectrum\\[\\d+]\\.(retentionTime|scans\\[\\d+]\\."
            + "(startTime|injectionTime)|precursors\\[\\d+]\\.(isolationWindow\\.(targetMz|lowerOffset|upperOffset)"
            + "|selectedIons\\[\\d+]\\.(mz|intensity)))");
    private static final Pattern SCAN_IC = Pattern.compile("spectrum\\[\\d+]\\.scans\\[\\d+]\\.instrumentConfigurationRef");
    private static final Pattern SCAN_PARAM = Pattern.compile("spectrum\\[\\d+]\\.scans\\[\\d+]\\.parameters\\[.*]");

    /** The five summary params, recomputed from the signal by the reference converter (and given a unit). */
    private static final Exclusion SUMMARIES = new Exclusion("SUMMARIES",
            "the summary params MS:1000528, MS:1000527, MS:1000504, MS:1000505, MS:1000285, which small.mzpeak"
                    + " recomputed from the signal: 167 in small",
            d -> SUMMARY.matcher(d.path()).matches());
    /** The scanList combination term, which the reference converter does not store. */
    private static final Exclusion NO_COMBINATION = new Exclusion("NO_COMBINATION",
            "MS:1000795 'no combination' of the mzML scanList, absent from small.mzpeak: 48 in small",
            d -> COMBINATION.matcher(d.path()).matches() && d.expected() instanceof List<?> e && !e.isEmpty()
                    && d.actual() instanceof List<?> a && a.isEmpty());
    /** Values small.mzpeak stores as float32. */
    private static final Exclusion FLOAT32 = new Exclusion("FLOAT32",
            "injection times, isolation window target m/z and selected-ion intensities that small.mzpeak stores as"
                    + " float32, within relative 1e-7: 46 + 25 + 34 in small",
            d -> FLOAT32_PATH.matcher(d.path()).matches() && d.expected() instanceof Double e
                    && d.actual() instanceof Double a && Math.abs(a - e) <= 1e-7 * Math.abs(e));
    /** A scan without instrumentConfigurationRef, which the reference converter gives the run default 0. */
    private static final Exclusion DEFAULT_IC = new Exclusion("DEFAULT_IC",
            "a scan instrumentConfigurationRef absent from the mzML, the run default 0 in small.mzpeak: 7 in small",
            d -> SCAN_IC.matcher(d.path()).matches() && d.expected() == null && Integer.valueOf(0).equals(d.actual()));
    /** A userParam typed xsd:float, which small.mzpeak holds as an integer of the same value. */
    private static final Exclusion XSD_FLOAT = new Exclusion("XSD_FLOAT",
            "the scan userParam '[Thermo Trailer Extra]Monoisotopic M/Z:' typed xsd:float (Double 0.0), a Long 0 in"
                    + " small.mzpeak: 34 in small",
            d -> SCAN_PARAM.matcher(d.path()).matches() && d.expected() instanceof List<?> e && e.size() == 1
                    && d.actual() instanceof List<?> a && a.size() == 1 && ((Param) e.get(0)).value() instanceof Double x
                    && ((Param) a.get(0)).value() instanceof Long y && x == y.doubleValue());

    @Test
    void smallReadsAsSmallMzPeakHoldsIt(@TempDir Path dir) throws IOException {
        Run run = read(MzMLFixtures.extract(MzMLFixtures.SMALL, dir));

        assertThat(run.spectra).hasSize(48);
        assertThat(run.spectra).extracting(Spectrum::msLevel).filteredOn(l -> l == 1).hasSize(14);
        assertThat(run.spectra).extracting(Spectrum::msLevel).filteredOn(l -> l == 2).hasSize(34);
        assertThat(run.spectra).allSatisfy(s -> {
            SpectrumDescription d = s.description();
            assertThat(d.polarity()).isEqualTo(Polarity.POSITIVE);
            assertThat(s.peaks()).isEmpty();
            assertThat(s.peakArrays()).isEmpty();
            boolean centroid = d.signalContinuity() == SignalContinuity.CENTROID;
            assertThat(d.numberOfPeaks()).isEqualTo(centroid ? s.mz().length : 0);
            assertThat(d.numberOfDataPoints()).isEqualTo(centroid ? 0 : s.mz().length);
        });
        assertThat(run.spectra).extracting(s -> s.description().signalContinuity())
                .containsOnly(SignalContinuity.PROFILE, SignalContinuity.CENTROID)
                .filteredOn(c -> c == SignalContinuity.PROFILE).hasSize(14);
        assertThat(run.reader.survey()).isEqualTo(new MzMLReader.Survey(ids(run.spectra), List.of(), Set.of(), 1));
        assertThat(run.reader.warnings()).containsExactlyElementsOf(withLimits(
                "B-3 mzML@id and mzML@accession lost (1)", "B-1 referenceableParamGroups expanded inline (1)"));

        Spectrum s2 = run.spectra.get(2);
        Precursor precursor = s2.description().primaryPrecursor();
        assertThat(precursor.precursorIndex()).isEqualTo(1L);
        assertThat(precursor.precursorId()).isEqualTo("controllerType=0 controllerNumber=1 scan=2");
        assertThat(precursor.isolationWindow()).isEqualTo(new IsolationWindow(810.789428710938, 1.0, 1.0,
                List.of(new Param("ms level", null, 1L, null))));
        assertThat(precursor.isolationWindow().parameters().get(0).value()).isInstanceOf(Long.class);
        assertThat(precursor.selectedIons()).containsExactly(new SelectedIon(810.789428710938, null, 1.994039182404E6,
                List.of()));
        assertThat(precursor.activation()).isEqualTo(new Activation(List.of(
                new Param("collision-induced dissociation", "MS:1000133", null, null),
                new Param("collision energy", "MS:1000045", 35.0, "UO:0000266"))));
        assertThat(s2.description().scans().get(0).parameters())
                .contains(new Param("[Thermo Trailer Extra]Monoisotopic M/Z:", null, 0.0, null));

        Chromatogram tic = run.chromatograms.get(0);
        assertThat(tic.size()).isEqualTo(48);
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
        assertThat(Arrays.stream(tic.arrays().get("ms level")).sum()).isEqualTo(82.0);

        try (MzPeakReader mzpeak = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            List<Spectrum> theirs = spectra(mzpeak);
            assertThat(ids(theirs)).isEqualTo(ids(run.spectra));
            ModelAssert.Result result = ModelAssert.assertEqual("small.mzML vs small.mzpeak",
                    descriptions(run.spectra, run.chromatograms), descriptions(theirs, mzpeak.chromatograms()),
                    Mode.MZML_VS_MZPEAK, SUMMARIES, NO_COMBINATION, FLOAT32, DEFAULT_IC, XSD_FLOAT);
            assertThat(result.hidden()).containsExactly(Map.entry("SUMMARIES", 167), Map.entry("NO_COMBINATION", 48),
                    Map.entry("FLOAT32", 105), Map.entry("DEFAULT_IC", 7), Map.entry("XSD_FLOAT", 34));

            for (int i = 0; i < 48; i++) {
                Spectrum ours = run.spectra.get(i);
                Spectrum stored = theirs.get(i);
                double rt = stored.description().retentionTime();
                assertThat(Math.abs(ours.description().retentionTime() - rt)).isLessThanOrEqualTo(1e-7 * rt);
                int[] kept = notInZeroRun(ours);
                assertThat(stored.mz()).as("spectrum %d", i).hasSize(kept.length);
                for (int k = 0; k < kept.length; k++) {
                    int j = kept[k];
                    assertThat(stored.intensity()[k]).isEqualTo(ours.intensity()[j]);
                    if (ours.intensity()[j] != 0) {
                        assertThat(stored.mz()[k]).isEqualTo(ours.mz()[j]);
                    } else {
                        assertThat(stored.mz()[k]).isCloseTo(ours.mz()[j], within(5e-5));
                    }
                }
            }
        }
    }

    /**
     * The positions the reference converter keeps of a profile spectrum (upstream {@code find_where_not_zero_run}):
     * every non-zero point, and of each run of zeros the first (unless the run starts the spectrum) and the last
     * (unless the run ends it); every position of a centroid spectrum.
     */
    private static int[] notInZeroRun(Spectrum s) {
        double[] y = s.intensity();
        if (s.description().signalContinuity() == SignalContinuity.CENTROID) {
            return java.util.stream.IntStream.range(0, y.length).toArray();
        }
        List<Integer> kept = new ArrayList<>();
        boolean wasZero = false;
        for (int i = 0; i < y.length; i++) {
            if (y[i] != 0) {
                kept.add(i);
                wasZero = false;
            } else {
                if (!((wasZero || kept.isEmpty()) && (i == y.length - 1 || y[i + 1] == 0))) {
                    kept.add(i);
                }
                wasZero = true;
            }
        }
        return kept.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- (b) TOFsulfas against has_uv.mzpeak ---------------------------------------------------------------------

    @Test
    void tofSulfasReadsAsHasUvHoldsItsArrays(@TempDir Path dir) throws IOException {
        Run run = read(MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir));

        assertThat(run.spectra).hasSize(212);
        assertThat(run.wavelengthSpectra).hasSize(520);
        assertThat(run.reader.survey().wavelengthSpectrumIds()).hasSize(520);
        assertThat(run.reader.survey().spectrumArrays()).isEmpty();
        assertThat(run.reader.warnings()).containsExactlyElementsOf(withLimits(
                "B-3 mzML@id and mzML@accession lost (1)", "B-1 referenceableParamGroups expanded inline (1)",
                "B-17 scan window params other than the limits lost (212)"));

        WavelengthSpectrum uv = run.wavelengthSpectra.get(0);
        assertThat(uv.index()).isZero();
        assertThat(uv.id()).isEqualTo("merged=212 row=0");
        assertThat(uv.time()).isEqualTo(0.001833333333);
        assertThat(uv.lambdaMax()).isNull();
        assertThat(uv.size()).isEqualTo(96);
        assertThat(uv.parameters()).extracting(Param::accession, Param::value, Param::unit).containsExactly(
                tuple("MS:1000804", null, null), tuple("MS:1000128", null, null),
                tuple("MS:1000619", 210.0, "UO:0000018"), tuple("MS:1000618", 400.0, "UO:0000018"),
                tuple("MS:1000796", "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.213.213. File:\""
                        + "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.d\", NativeID:\"merged=212 row=0\"",
                        null),
                tuple("MS:1000795", null, null));

        Chromatogram tic = run.chromatograms.get(0);
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(212);
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
        assertThat(tic.arrays().get("ms level")).hasSize(212);
        Chromatogram dad = run.chromatograms.get(1);
        assertThat(dad.typeCurie()).isEqualTo("MS:1000812");
        assertThat(dad.size()).isEqualTo(526);
        assertThat(dad.intensityUnit()).isEqualTo("UO:0000269");
        assertThat(Arrays.stream(dad.intensity()).sum()).isCloseTo(57.358742, within(1e-6));
        assertThat(run.chromatograms).extracting(Chromatogram::dataProcessingRef).containsOnlyNulls();

        try (MzPeakReader hasUv = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            List<Spectrum> theirs = spectra(hasUv);
            assertThat(ids(theirs)).isEqualTo(ids(run.spectra));
            for (int i = 0; i < theirs.size(); i++) {
                assertThat(run.spectra.get(i).mz()).isEqualTo(theirs.get(i).mz());
                assertThat(run.spectra.get(i).intensity()).isEqualTo(theirs.get(i).intensity());
            }
            List<WavelengthSpectrum> stored = hasUv.wavelengthSpectra();
            assertThat(stored).hasSize(520);
            for (int i = 0; i < stored.size(); i++) {
                WavelengthSpectrum ours = run.wavelengthSpectra.get(i);
                assertThat(ours.index()).isEqualTo(i);
                assertThat(ours.id()).isEqualTo(stored.get(i).id());
                assertThat(ours.time()).isEqualTo(stored.get(i).time());
                assertThat(ours.wavelength()).isEqualTo(stored.get(i).wavelength());
                assertThat(ours.intensity()).isEqualTo(stored.get(i).intensity());
            }
            for (int i = 0; i < 2; i++) {
                assertThat(run.chromatograms.get(i).time()).isEqualTo(hasUv.chromatograms().get(i).time());
                assertThat(run.chromatograms.get(i).intensity()).isEqualTo(hasUv.chromatograms().get(i).intensity());
            }
        }
    }

    // ---- (c) diaPASEF against diaPASEF.ref.mzpeak ----------------------------------------------------------------

    @Test
    void diaPasefReadsAsDiaPasefRefHoldsItsArrays(@TempDir Path dir) throws IOException {
        Run run = read(MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir));

        assertThat(run.spectra).hasSize(9);
        Spectrum first = run.spectra.get(0);
        assertThat(first.mz()).hasSize(205_921);
        assertThat(first.arrays()).containsOnlyKeys("MS:1003006");
        assertThat(first.arrays().get("MS:1003006")).hasSize(205_921);
        assertThat(run.reader.survey().spectrumArrays()).containsExactly("MS:1003006");
        // jmzML reads userParams without their unit MS:1002814 (L-4)
        assertThat(first.description().parameters()).filteredOn(p -> p.name().startsWith("ion mobility "))
                .extracting(Param::name, Param::value, Param::unit).containsExactly(
                        tuple("ion mobility lower limit", Double.parseDouble("0.60156745661641153"), null),
                        tuple("ion mobility upper limit", 1.637221297889762, null));

        SpectrumDescription d = run.spectra.get(1).description();
        assertThat(d.scans().get(0).startTime()).isEqualTo(64.43997164823703);
        assertThat(d.retentionTime()).isEqualTo(64.43997164823703);
        assertThat(d.parameters()).extracting(Param::accession).contains("MS:1000571");
        assertThat(d.precursors()).singleElement().satisfies(p -> {
            assertThat(p.precursorIndex()).isNull();
            assertThat(p.precursorId()).isNull();
            assertThat(p.selectedIons()).extracting(SelectedIon::mz).containsExactly(813.0);
        });

        try (MzPeakReader ref = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            List<Spectrum> theirs = spectra(ref);
            assertThat(theirs).hasSize(9);
            for (int i = 0; i < 9; i++) {
                double[][] ours = sortedRows(run.spectra.get(i));
                double[][] stored = sortedRows(theirs.get(i));
                assertThat(stored.length).as("spectrum %d", i).isEqualTo(ours.length);
                double mz = 0;
                double intensity = 0;
                double im = 0;
                for (int k = 0; k < ours.length; k++) {
                    mz = Math.max(mz, Math.abs(stored[k][0] - ours[k][0]) / ours[k][0]);
                    intensity = Math.max(intensity, Math.abs(stored[k][1] - ours[k][1]));
                    im = Math.max(im, Math.abs(stored[k][2] - ours[k][2]));
                }
                assertThat(mz).as("spectrum %d m/z, relative", i).isLessThanOrEqualTo(1.8e-12);
                assertThat(intensity).as("spectrum %d intensity", i).isLessThanOrEqualTo(1.0);
                assertThat(im).as("spectrum %d ion mobility", i).isLessThanOrEqualTo(0x1p-51); // 4.4e-16, 2 ulp at 1
            }
        }
    }

    /** The rows (m/z, intensity, MS:1003006) of {@code s}, sorted lexicographically. */
    private static double[][] sortedRows(Spectrum s) {
        double[] im = s.arrays().get("MS:1003006");
        double[][] rows = new double[s.mz().length][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new double[] {s.mz()[i], s.intensity()[i], im[i]};
        }
        Arrays.sort(rows, (x, y) -> Arrays.compare(x, y));
        return rows;
    }

    // ---- (d) tiny ------------------------------------------------------------------------------------------------

    @Test
    void tinyIsReadInFull(@TempDir Path dir) throws IOException {
        Run run = read(MzMLFixtures.extract(MzMLFixtures.TINY, dir));

        assertThat(run.reader.warnings()).containsExactlyElementsOf(withLimits(
                "B-3 mzML@id and mzML@accession lost (1)",
                "B-1 referenceableParamGroups expanded inline (2)",
                "B-5 instrumentConfiguration ids [LCQ_x0020_Deca] numbered by position (1)",
                "B-7 run@sampleRef lost (1)",
                ARRAY_DP_LOST + " (10)",
                "B-10 spectrum@spotID lost (1)",
                "B-10 spectrum@sourceFileRef lost (1)",
                "B-15 times converted to minutes and drift times to milliseconds (3)",
                "B-27 chromatogram products dropped (1)"));
        assertThat(run.reader.survey()).isEqualTo(new MzMLReader.Survey(
                List.of("scan=19", "scan=20", "scan=21", "sample=1 period=1 cycle=22 experiment=1"), List.of(),
                Set.of(), 2));
        assertThat(run.wavelengthSpectra).isEmpty();

        Spectrum s0 = run.spectra.get(0);
        assertThat(s0.mz()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(s0.intensity()).containsExactly(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        assertThat(s0.arrays()).isEmpty();
        assertThat(s0.description()).isEqualTo(new SpectrumDescription(0, "scan=19", 1, 5.8905, Polarity.POSITIVE,
                SignalContinuity.CENTROID, 0, 15,
                List.of(new ScanEvent(5.8905, null, "+ c NSI Full ms [ 400.00-1800.00]", 3, 0, null,
                        List.of(new ScanWindow(400, 1800)), List.of())),
                List.of(),
                List.of(new Param("lowest observed m/z", "MS:1000528", 400.39, "MS:1000040"),
                        new Param("highest observed m/z", "MS:1000527", 1795.56, "MS:1000040"),
                        new Param("base peak m/z", "MS:1000504", 445.347, "MS:1000040"),
                        new Param("base peak intensity", "MS:1000505", 120053L, "MS:1000131"),
                        new Param("total ion current", "MS:1000285", 16675500L, null),
                        new Param("MS1 spectrum", "MS:1000579", null, null),
                        new Param("no combination", "MS:1000795", null, null))));

        SpectrumDescription d1 = run.spectra.get(1).description();
        assertThat(d1.msLevel()).isEqualTo(2);
        assertThat(d1.signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
        assertThat(d1.numberOfDataPoints()).isEqualTo(10);
        assertThat(d1.numberOfPeaks()).isZero();
        assertThat(d1.precursors()).containsExactly(new Precursor(0L, "scan=19",
                new IsolationWindow(445.3, 0.5, 0.5),
                List.of(new SelectedIon(445.34, 2, 120053.0, List.of())),
                new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null),
                        new Param("collision energy", "MS:1000045", 35L, "UO:0000266")))));

        Spectrum s2 = run.spectra.get(2);
        assertThat(s2.mz()).isEmpty();
        assertThat(s2.intensity()).isEmpty();
        assertThat(s2.description().scans()).singleElement().satisfies(scan -> {
            assertThat(scan.startTime()).isNaN();
            assertThat(scan.parameters()).isEmpty();
            assertThat(scan.instrumentConfigurationRef()).isNull();
        });
        assertThat(s2.description().retentionTime()).isNaN();
        assertThat(s2.description().numberOfPeaks()).isZero();

        SpectrumDescription d3 = run.spectra.get(3).description();
        assertThat(d3.scans().get(0).startTime()).isEqualTo(0.7008333333333333);
        assertThat(d3.retentionTime()).isEqualTo(0.7008333333333333);
        assertThat(d3.parameters()).contains(new Param("alternate source file", null,
                "to test a different nativeID format", null));

        Chromatogram tic = run.chromatograms.get(0);
        assertThat(tic.index()).isZero();
        assertThat(tic.id()).isEqualTo("tic");
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.time()).isEqualTo(minutes(15));
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
        assertThat(tic.dataProcessingRef()).isEqualTo("CompassXtract_x0020_processing");
        assertThat(tic.polarity()).isEqualTo(Polarity.UNKNOWN);
        assertThat(tic.parameters()).isEmpty();

        Chromatogram sic = run.chromatograms.get(1);
        assertThat(sic.index()).isEqualTo(1);
        assertThat(sic.typeCurie()).isEqualTo("MS:1000627");
        assertThat(sic.time()).isEqualTo(minutes(10));
        assertThat(sic.dataProcessingRef()).isNull();
        assertThat(sic.precursors()).containsExactly(new Precursor(null, null, new IsolationWindow(456.7, null, null),
                List.of(), new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null,
                        null)))));
    }

    /** {@code 0 .. n-1} seconds in minutes. */
    private static double[] minutes(int n) {
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i / 60.0;
        }
        return t;
    }

    @Test
    void readTwiceGivesEqualModelsAndCountsTheLossesTwice(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.TINY, dir));
        Run first = new Run(reader);
        Run second = new Run(reader);

        reader.read(first);
        MzMLReader.Survey survey = reader.survey();
        reader.read(second);

        assertThat(reader.survey()).isSameAs(survey);
        ModelAssert.assertEqual("tiny read twice", first.model(), second.model(), Mode.STRICT);
        assertThat(reader.warnings()).contains(ARRAY_DP_LOST + " (20)", "B-27 chromatogram products dropped (2)",
                "B-1 referenceableParamGroups expanded inline (2)");
    }

    @Test
    void anExceptionOfTheVisitorPropagates(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.TINY, dir));
        IllegalStateException stop = new IllegalStateException("stop");
        List<String> seen = new ArrayList<>();
        Run visitor = new Run(reader) {
            @Override
            public void spectrum(Spectrum spectrum) {
                seen.add(spectrum.description().id());
                throw stop;
            }
        };

        assertThatThrownBy(() -> reader.read(visitor)).isSameAs(stop);
        assertThat(seen).containsExactly("scan=19");
        assertThatThrownBy(() -> reader.read(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theSurveyIsCachedAndNotesNothing(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.TINY, dir));

        MzMLReader.Survey survey = reader.survey();

        assertThat(reader.survey()).isSameAs(survey);
        assertThat(survey.spectrumIds()).isUnmodifiable();
        assertThat(survey.wavelengthSpectrumIds()).isUnmodifiable();
        assertThat(survey.spectrumArrays()).isUnmodifiable();
        List<String> warnings = reader.warnings();
        assertThat(warnings).hasSize(9).startsWith(MzMLReader.LIMITS.toArray(String[]::new));
        assertThat(MzMLReader.LIMITS).extracting(w -> w.substring(0, 4)).containsExactly("L-1 ", "L-3 ", "L-4 ",
                "L-5 ", "L-6 ");
        assertThat(warnings.subList(5, 9)).extracting(w -> w.substring(0, 4))
                .containsExactlyInAnyOrder("B-1 ", "B-3 ", "B-5 ", "B-7 ");
    }

    // ---- (e) rejected input --------------------------------------------------------------------------------------

    @Test
    void anUnsupportedCompressionIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "accession=\"MS:1000576\" name=\"no compression\"",
                "accession=\"MS:1003780\" name=\"zstd compression\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its MS:1000514 array with the terms [MS:1000523,"
                        + " MS:1003780, MS:1000514] cannot be decoded by jmzML 1.7.11: Required compression CV"
                        + " parameter not found in BinaryDataArray!")
                .cause().isInstanceOf(IllegalStateException.class)
                .hasMessage("Required compression CV parameter not found in BinaryDataArray!");
    }

    @Test
    void sixteenBitFloatIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "accession=\"MS:1000523\" name=\"64-bit float\"",
                "accession=\"MS:1000520\" name=\"16-bit float\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(prefix(file, "spectrum #1 (scan=20)") + "its MS:1000514 array with the terms"
                        + " [MS:1000520, MS:1000576, MS:1000514] cannot be decoded by jmzML 1.7.11: Required precision"
                        + " CV parameter")
                .cause().isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Required precision CV parameter");
    }

    @Test
    void anMzArrayInSecondsIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, TINY_MZ_UNIT,
                "accession=\"MS:1000514\" name=\"m/z array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000010\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its MS:1000514 array in unit UO:0000010 cannot be"
                        + " converted to MS:1000040")
                .hasNoCause();
    }

    @Test
    void anIntensityArrayInPercentOfBasePeakIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20,
                "name=\"intensity array\" value=\"\" unitCvRef=\"MS\" unitAccession=\"MS:1000131\"",
                "name=\"intensity array\" value=\"\" unitCvRef=\"MS\" unitAccession=\"MS:1000132\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #1 (scan=20)") + "its MS:1000515 array in unit MS:1000132 cannot be"
                        + " converted to MS:1000131");
    }

    @Test
    void aWavelengthArrayInMicrometersIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tofWith(dir, s -> replaceAfter(s, UV_0,
                "name=\"wavelength array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\"",
                "name=\"wavelength array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000017\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #212 (merged=212 row=0)") + "its MS:1000617 array in unit"
                        + " UO:0000017 cannot be converted to UO:0000018");
    }

    // A main array's unit is checked before B-9 drops the arrays of an entity that lacks the other main array.

    @Test
    void anMzOnlySpectrumInSecondsIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(replaceAfter(s, SCAN_19, TINY_MZ_UNIT,
                "accession=\"MS:1000514\" name=\"m/z array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000010\""),
                "unitAccession=\"UO:0000010\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its MS:1000514 array in unit UO:0000010 cannot be"
                        + " converted to MS:1000040");
    }

    @Test
    void anIntensityOnlySpectrumInPercentOfBasePeakIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(replaceAfter(s, SCAN_19,
                "name=\"intensity array\" value=\"\" unitCvRef=\"MS\" unitAccession=\"MS:1000131\"",
                "name=\"intensity array\" value=\"\" unitCvRef=\"MS\" unitAccession=\"MS:1000132\""), SCAN_19));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its MS:1000515 array in unit MS:1000132 cannot be"
                        + " converted to MS:1000131");
    }

    @Test
    void aWavelengthOnlySpectrumInMicrometersIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        String micrometers = "name=\"wavelength array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000017\"";
        Path file = tofWith(dir, s -> removeFirstArray(replaceAfter(s, UV_0,
                "name=\"wavelength array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\"", micrometers),
                micrometers));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #212 (merged=212 row=0)") + "its MS:1000617 array in unit"
                        + " UO:0000017 cannot be converted to UO:0000018");
    }

    @Test
    void aTimeOnlyChromatogramInNanometersIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(replaceAfter(s, "id=\"tic\"",
                "name=\"time array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000010\"",
                "name=\"time array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\""),
                "unitAccession=\"UO:0000018\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "chromatogram #0 (tic)") + "its MS:1000595 array in unit UO:0000018 cannot be"
                        + " converted to UO:0000031");
    }

    @Test
    void aScanStartTimeInNanometersIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "unitCvRef=\"UO\" unitAccession=\"UO:0000031\"",
                "unitCvRef=\"UO\" unitAccession=\"UO:0000018\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #1 (scan=20)") + "scan start time (MS:1000016) in unit UO:0000018"
                        + " cannot be converted to minutes (UO:0000031)");
    }

    @Test
    void mzAndIntensityArraysOfDifferentDeclaredLengthsAreRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "<binaryDataArray encodedLength=\"108\"",
                "<binaryDataArray arrayLength=\"9\" encodedLength=\"108\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #1 (scan=20)") + "its MS:1000514 and MS:1000515 arrays declare"
                        + " different lengths, 9 and 10");
    }

    /** A declared length other than the decoded one is not checked (L-6): scan=19 declares 14 and holds 15 points. */
    @Test
    void aDeclaredLengthOtherThanTheDecodedOneIsNotChecked(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, SCAN_19 + " defaultArrayLength=\"15\"",
                SCAN_19 + " defaultArrayLength=\"14\""));

        Spectrum s0 = read(file).spectra.get(0);

        assertThat(s0.mz()).hasSize(15);
        assertThat(s0.intensity()).hasSize(15);
    }

    /** Main arrays that decode to different lengths: the model's constructor rejects them, wrapped by read(). */
    @Test
    void mainArraysThatDecodeToDifferentLengthsAreRejectedByRead(@TempDir Path dir) throws IOException {
        double[] intensity = new double[14];
        for (int i = 0; i < intensity.length; i++) {
            intensity[i] = 15 - i;
        }
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, TINY_INTENSITY_BINARY, floats(intensity)));
        MzMLReader reader = new MzMLReader(file);

        assertThat(reader.survey().spectrumIds()).hasSize(4);
        assertThatThrownBy(() -> reader.read(new Run(reader)))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(prefix(file, "spectrum #0 (scan=19)"))
                .cause().isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getStackTrace()[0].getClassName()).startsWith("org.mzpeak.model."));
    }

    @Test
    void anArrayLengthThatIsNoIntegerIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "<binaryDataArray encodedLength=\"108\"",
                "<binaryDataArray arrayLength=\"ten\" encodedLength=\"108\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(prefix(file, "spectrum #1"))
                .hasRootCauseInstanceOf(NumberFormatException.class);
    }

    /** What xxindex cannot index fails while jmzML builds its index, before any element: no element is named. */
    @Test
    void malformedXmlInTheRunIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "</scanList>\n          <precursorList",
                "</scanLis>\n          <precursorList"));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + file + ": Internal stack of XML tags was corrupted!")
                .cause().isInstanceOf(IllegalStateException.class);
    }

    /**
     * A UTF-16 document cannot be indexed (xxindex reads it byte by byte): header() reads it, survey() fails while
     * jmzML builds its index. A UTF-32 document fails in header() already: the JDK parser does not know that encoding.
     */
    @Test
    void aUtf16DocumentIsRejectedBySurveyAndAUtf32OneByHeader(@TempDir Path dir) throws IOException {
        String tiny = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, dir), StandardCharsets.ISO_8859_1);
        Path utf16 = Files.writeString(dir.resolve("tiny-utf16.mzML"),
                replaceOnce(tiny, "encoding=\"ISO-8859-1\"", "encoding=\"UTF-16\""), StandardCharsets.UTF_16);
        Path utf32 = Files.writeString(dir.resolve("tiny-utf32.mzML"),
                replaceOnce(tiny, "encoding=\"ISO-8859-1\"", "encoding=\"UTF-32\""), Charset.forName("UTF-32"));

        assertThat(new MzMLReader(utf16).header().fileMetadata().run().id()).isEqualTo("Experiment_x0020_1");
        assertThatThrownBy(() -> new MzMLReader(utf16).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("mzML " + utf16 + ": Invalid ID in xml: ")
                .cause().isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MzMLReader(utf32).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("mzML " + utf32 + " line 1 column ")
                .hasMessageEndingWith(": Invalid encoding name \"UTF-32\".");
    }

    /** A spectrum JAXB cannot parse (a duplicated attribute) is named by its position only: jmzML returned none. */
    @Test
    void aSpectrumJaxbCannotParseIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        String msLevel2 = "<cvParam cvRef=\"MS\" accession=\"MS:1000511\" name=\"ms level\" value=\"2\"/>";
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, msLevel2,
                msLevel2.replace("cvRef=\"MS\"", "cvRef=\"MS\" cvRef=\"MS\"")));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + file + ": spectrum #1: Could not unmarshal object at"
                        + " xpath:/run/spectrumList/spectrum")
                .cause().isInstanceOf(IllegalStateException.class);
    }

    /** Guard 2: MS-Numpress and zlib in one term, which jmzML cannot decode, in a spectrum and in a chromatogram. */
    @Test
    void numpressAndZlibInOneTermAreRejectedBySurvey(@TempDir Path dir) throws IOException {
        String noCompression = "accession=\"MS:1000576\" name=\"no compression\"";
        for (String term : NUMPRESS_ZLIB) {
            String combined = "accession=\"" + term + "\" name=\"" + MzMLTerms.COMPRESSIONS.get(term) + "\"";
            Path spectrum = tinyWith(dir, s -> replaceAfter(s, SCAN_19, noCompression, combined));
            Path chromatogram = tinyWith(dir, s -> replaceAfter(s,
                    "name=\"time array\" value=\"\" unitCvRef=\"UO\" unitAccession=\"UO:0000010\"", noCompression,
                    combined));

            assertThatThrownBy(() -> new MzMLReader(spectrum).survey()).as(term)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(prefix(spectrum, "spectrum #0 (scan=19)") + "its MS:1000514 array is compressed with "
                            + term + " (MS-Numpress and zlib in one term), which jmzML 1.7.11 cannot decode");
            assertThatThrownBy(() -> new MzMLReader(chromatogram).survey()).as(term)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(prefix(chromatogram, "chromatogram #0 (tic)") + "its MS:1000515 array is compressed"
                            + " with " + term + " (MS-Numpress and zlib in one term), which jmzML 1.7.11 cannot decode");
        }
    }

    @Test
    void aChromatogramArrayInZstdIsRejectedBySurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "id=\"tic\"", "accession=\"MS:1000576\" name=\"no compression\"",
                "accession=\"MS:1003780\" name=\"zstd compression\""));

        assertThatThrownBy(() -> new MzMLReader(file).survey())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(prefix(file, "chromatogram #0 (tic)") + "its MS:1000595 array with the terms"
                        + " [MS:1000523, MS:1003780, MS:1000595] cannot be decoded by jmzML 1.7.11: ")
                .cause().isInstanceOf(IllegalStateException.class)
                .hasMessage("Required compression CV parameter not found in BinaryDataArray!");
    }

    // ---- (e) arrays ----------------------------------------------------------------------------------------------

    /**
     * The empty arrays jmzML 1.7.11 decodes to 0 values (design 5.7): no compression, zlib (an empty or a deflated
     * empty payload), MS-Numpress PIC (alone or with zlib) and an MS-Numpress SLOF payload that is its 8-byte header
     * only (alone or deflated).
     */
    static Stream<Arguments> emptyArraysJmzmlDecodes() {
        byte[] empty = new byte[0];
        byte[] slofHeader = slof(new double[0]);
        return Stream.of(
                arguments("no compression", List.of(NONE), empty),
                arguments("zlib, empty payload", List.of(ZLIB), empty),
                arguments("zlib, deflated empty payload", List.of(ZLIB), deflate(empty)),
                arguments("PIC, empty payload", List.of(PIC), empty),
                arguments("PIC and zlib, empty payload", List.of(PIC, ZLIB), empty),
                arguments("PIC and zlib, deflated empty payload", List.of(PIC, ZLIB), deflate(empty)),
                arguments("SLOF, header-only payload", List.of(SLOF), slofHeader),
                arguments("SLOF and zlib, deflated header-only payload", List.of(SLOF, ZLIB), deflate(slofHeader)));
    }

    /** tiny's scan=21 (defaultArrayLength 0) with both arrays empty as below, under every data type jmzML decodes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyArraysJmzmlDecodes")
    void anEmptyArrayIsReadAsAnEmptyArray(String name, List<String> compression, byte[] payload, @TempDir Path dir)
            throws IOException {
        for (String dataType : NUMERIC_DATA_TYPES) {
            Path file = tinyWith(dir, s -> spectrum21With(s, terms(dataType, compression), payload));

            Spectrum s2 = read(file).spectra.get(2);

            assertThat(s2.mz()).as(dataType).isEmpty();
            assertThat(s2.intensity()).as(dataType).isEmpty();
        }
    }

    /**
     * The empty arrays jmzML 1.7.11 cannot decode (its decoder throws NegativeArraySizeException): an MS-Numpress
     * linear payload that is empty or its 8-byte header only, and an empty MS-Numpress SLOF payload, each alone or
     * with zlib.
     */
    static Stream<Arguments> emptyArraysJmzmlCannotDecode() {
        byte[] empty = new byte[0];
        byte[] linearHeader = linear(new double[0]);
        return Stream.of(
                arguments("linear, empty payload", List.of(LINEAR), empty),
                arguments("linear, header-only payload", List.of(LINEAR), linearHeader),
                arguments("linear and zlib, empty payload", List.of(LINEAR, ZLIB), empty),
                arguments("linear and zlib, deflated empty payload", List.of(LINEAR, ZLIB), deflate(empty)),
                arguments("linear and zlib, deflated header-only payload", List.of(LINEAR, ZLIB),
                        deflate(linearHeader)),
                arguments("SLOF, empty payload", List.of(SLOF), empty),
                arguments("SLOF and zlib, empty payload", List.of(SLOF, ZLIB), empty),
                arguments("SLOF and zlib, deflated empty payload", List.of(SLOF, ZLIB), deflate(empty)));
    }

    /**
     * tiny's scan=21 with both arrays empty as below, under every data type jmzML decodes: survey() passes, read()
     * rejects the m/z array, which it decodes first, with jmzML's exception as the cause.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyArraysJmzmlCannotDecode")
    void anEmptyArrayJmzmlCannotDecodeIsRejectedByRead(String name, List<String> compression, byte[] payload,
                                                        @TempDir Path dir) throws IOException {
        for (String dataType : NUMERIC_DATA_TYPES) {
            List<String> terms = terms(dataType, compression);
            Path file = tinyWith(dir, s -> spectrum21With(s, terms, payload));
            MzMLReader reader = new MzMLReader(file);
            List<String> mzTerms = new ArrayList<>(terms);
            mzTerms.add("MS:1000514");

            assertThat(reader.survey().spectrumIds()).as(dataType).hasSize(4);
            assertThatThrownBy(() -> reader.read(new Run(reader))).as(dataType)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(prefix(file, "spectrum #2 (scan=21)") + "its MS:1000514 array with the terms "
                            + mzTerms + " cannot be decoded by jmzML 1.7.11: -1")
                    .cause().isInstanceOf(NegativeArraySizeException.class);
        }
    }

    /**
     * Of two compression terms, or two data type terms, jmzML decodes by the one it knows; the reader neither checks
     * nor warns about the other (design 5.7, 6.3). zstd is a compression term, so next to no compression it is not even
     * counted; of 16-bit and 64-bit float, the second data type term, the 64-bit one jmzML decodes by, counts as a lost
     * array param (B-21). Pinned on scan=19's m/z array, so that a later guard has to change this test on purpose.
     */
    @Test
    void ofTwoCompressionOrDataTypeTermsJmzmlDecodesByTheOneItKnows(@TempDir Path dir) throws IOException {
        Path zstd = tinyWith(dir, s -> replaceAfter(s, SCAN_19, NO_COMPRESSION,
                cv("MS:1003780", "zstd compression", "", null) + "\n" + NO_COMPRESSION));
        Path float16 = tinyWith(dir, s -> replaceAfter(s, SCAN_19, F64,
                cv("MS:1000520", "16-bit float", "", null) + "\n" + F64));
        List<String> tinyWarnings = read(MzMLFixtures.extract(MzMLFixtures.TINY, dir)).reader.warnings();

        Run zstdRun = read(zstd);
        Run float16Run = read(float16);

        for (Run run : List.of(zstdRun, float16Run)) {
            assertThat(run.spectra.get(0).mz()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            assertThat(run.spectra.get(0).intensity()).containsExactly(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2,
                    1);
        }
        assertThat(zstdRun.reader.warnings()).isEqualTo(tinyWarnings);
        assertThat(float16Run.reader.warnings()).containsExactlyElementsOf(withLimits(
                "B-3 mzML@id and mzML@accession lost (1)",
                "B-1 referenceableParamGroups expanded inline (2)",
                "B-5 instrumentConfiguration ids [LCQ_x0020_Deca] numbered by position (1)",
                "B-7 run@sampleRef lost (1)",
                ARRAY_DP_LOST + " (10)",
                "B-21 lost array params other than array type, data type and compression (1)",
                "B-10 spectrum@spotID lost (1)",
                "B-10 spectrum@sourceFileRef lost (1)",
                "B-15 times converted to minutes and drift times to milliseconds (3)",
                "B-27 chromatogram products dropped (1)"));
    }

    @Test
    void anAsciiArrayIsDroppedAndWarned(@TempDir Path dir) throws IOException {
        String ascii = "<binaryDataArray encodedLength=\"8\">\n"
                + cv("MS:1001479", "null-terminated ASCII string", "", null) + "\n" + NO_COMPRESSION + "\n"
                + cv("MS:1000786", "non-standard data array", "comment", null) + "\n"
                + "<binary>aGVsbG8A</binary>\n</binaryDataArray>\n";
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, ascii));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays()).isEmpty();
        assertThat(run.spectra.get(0).mz()).hasSize(15);
        assertThat(run.reader.warnings()).contains("B-21 dropped null-terminated ASCII array (1)");
    }

    @Test
    void anIntensityOnlySpectrumLosesItsArrays(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(s, SCAN_19));

        Run run = read(file);

        Spectrum s0 = run.spectra.get(0);
        assertThat(s0.mz()).isEmpty();
        assertThat(s0.intensity()).isEmpty();
        assertThat(s0.description().numberOfPeaks()).isZero();
        assertThat(run.reader.warnings())
                .contains("B-9 arrays of spectra and chromatograms without an axis or intensity array dropped (1)");
    }

    @Test
    void anMzOnlySpectrumLosesItsArrays(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(s, "MS:1000514\" name=\"m/z array\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).isEmpty();
        assertThat(run.reader.warnings())
                .contains("B-9 arrays of spectra and chromatograms without an axis or intensity array dropped (1)");
    }

    @Test
    void driftTimeArraysInSecondsOrMinutesAreReadInMilliseconds(@TempDir Path dir) throws IOException {
        for (String unit : List.of("UO:0000010", "UO:0000031")) {
            String drift = array(cv("MS:1002477", "mean ion mobility drift time array", "", unit), 0.0015, 0.0025);
            Path file = tinyWith(dir, s -> twoPointSpectrum19(s, drift));

            Run run = read(file);

            double[] expected = unit.equals("UO:0000010") ? new double[] {1.5, 2.5} : new double[] {90.0, 150.0};
            assertThat(run.spectra.get(0).arrays().get("MS:1002477")).as(unit).containsExactly(expected);
            assertThat(run.reader.survey().spectrumArrays()).containsExactly("MS:1002477");
            assertThat(run.reader.warnings())
                    .contains("B-15 times converted to minutes and drift times to milliseconds (4)");
        }
    }

    @Test
    void anInverseMobilityArrayInMillisecondsIsDropped(@TempDir Path dir) throws IOException {
        String im = array(cv("MS:1003006", "mean inverse reduced ion mobility array", "", "UO:0000028"), 0.8, 0.9);
        Path file = tinyWith(dir, s -> twoPointSpectrum19(s, im));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays()).isEmpty();
        assertThat(run.reader.survey().spectrumArrays()).isEmpty();
        assertThat(run.reader.warnings()).contains("B-21 dropped array in a unit other than its default (1)");
    }

    @Test
    void aSignalToNoiseArrayKeepsItsValuesAndLosesItsUnit(@TempDir Path dir) throws IOException {
        String sn = array(cv(SN, "signal to noise array", "", "UO:0000186"), ramp(15));
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, sn));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays().get(SN)).containsExactly(ramp(15));
        assertThat(run.reader.survey().spectrumArrays()).containsExactly(SN);
        assertThat(run.reader.warnings()).contains("B-21 lost declared unit of an array without default unit (1)");
    }

    @Test
    void aSecondArrayOfAKeyIsDropped(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withArray(withArray(s, SCAN_19, array(SN_TYPE, ramp(15))), SCAN_19,
                array(SN_TYPE, new double[15])));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays().get(SN)).containsExactly(ramp(15));
        assertThat(run.reader.warnings()).contains("B-29 second arrays of a type already present dropped (1)");
    }

    /**
     * A non-standard data array named with an array type's accession would stand for that array type, here the m/z
     * array, which the real m/z array after it would then lose as a second one (B-29); such a name is rejected.
     */
    @Test
    void aNonStandardArrayNamedWithAnArrayTypeAccessionIsRejected(@TempDir Path dir) throws IOException {
        for (String name : List.of("MS:1000514", "MS:1000515", "MS:1000516", "MS:1000786")) {
            Path file = tinyWith(dir, s -> spectrum19With(s, 1,
                    array(cv("MS:1000786", "non-standard data array", name, null), 999),
                    array(cv("MS:1000514", "m/z array", "", "MS:1000040"), 100),
                    array(cv("MS:1000515", "intensity array", "", "MS:1000131"), 5)));

            assertThatThrownBy(() -> new MzMLReader(file).survey())
                    .as(name)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its non-standard data array (MS:1000786) is"
                            + " named '" + name + "', the accession of an array type");
        }
    }

    /**
     * Three extra keys first seen in three different spectra, in an order that is neither sorted nor that of a hash
     * set, and in the reverse order: {@code survey()} declares them in first-seen order, as {@code read()} emits them
     * (the {@link #read} helper compares the two). A copy into a hash-ordered set iterates the same three keys in one
     * order in a JVM, so it cannot give both.
     */
    @Test
    void theSurveyDeclaresExtraKeysOfDifferentSpectraInFirstSeenOrder(@TempDir Path dir) throws IOException {
        List<String> anchors = List.of(SCAN_19, SCAN_20, CYCLE_22);
        int[] lengths = {15, 10, 15};
        List<String> keys = List.of("ms level", "MS:1003006", "MS:1000516");
        for (List<String> order : List.of(keys, List.of(keys.get(2), keys.get(1), keys.get(0)))) {
            Path file = tinyWith(dir, s -> {
                for (int i = 0; i < anchors.size(); i++) {
                    s = withArray(s, anchors.get(i), extraArray(order.get(i), lengths[i]));
                }
                return s;
            });

            Run run = read(file);

            assertThat(run.reader.survey().spectrumArrays()).containsExactlyElementsOf(order);
            assertThat(run.spectra.get(0).arrays().keySet()).containsExactly(order.get(0));
            assertThat(run.spectra.get(1).arrays().keySet()).containsExactly(order.get(1));
            assertThat(run.spectra.get(3).arrays().keySet()).containsExactly(order.get(2));
        }
    }

    @Test
    void secondAxisAndIntensityArraysAreDropped(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withArray(withArray(s, SCAN_19,
                array(cv("MS:1000514", "m/z array", "", "MS:1000040"), ramp(15))), SCAN_19,
                array(cv("MS:1000515", "intensity array", "", null), ramp(15))));

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(run.spectra.get(0).intensity()).startsWith(15, 14);
        assertThat(run.reader.warnings()).contains("B-29 second arrays of a type already present dropped (2)");
    }

    @Test
    void anExtraArrayOfAnotherDeclaredLengthIsDropped(@TempDir Path dir) throws IOException {
        String sn = "<binaryDataArray arrayLength=\"3\" encodedLength=\"32\">\n" + F64 + "\n" + NO_COMPRESSION + "\n"
                + SN_TYPE + "\n<binary>" + floats(1, 2, 3) + "</binary>\n</binaryDataArray>\n";
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, sn));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays()).isEmpty();
        assertThat(run.reader.survey().spectrumArrays()).isEmpty();
        assertThat(run.reader.warnings())
                .contains("B-21 dropped array whose declared length differs from its axis array's (1)");
    }

    @Test
    void anArrayWithoutArrayTypeIsDroppedAndItsSpectrumLosesItsArrays(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceCvParam(s, SCAN_19, TINY_MZ_UNIT, ""));

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).isEmpty();
        assertThat(run.reader.warnings()).contains("B-21 dropped array without array type (1)",
                "B-9 arrays of spectra and chromatograms without an axis or intensity array dropped (1)");
    }

    @Test
    void sixtyFourBitIntegersBeyondTwoToThe53RoundAndAreCounted(@TempDir Path dir) throws IOException {
        long[] counts = new long[15];
        counts[3] = 9007199254740993L;
        String big = "<binaryDataArray encodedLength=\"160\">\n" + I64 + "\n" + NO_COMPRESSION + "\n"
                + cv("MS:1000786", "non-standard data array", "counts", null) + "\n<binary>" + longs(counts)
                + "</binary>\n</binaryDataArray>\n";
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, big));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays().get("counts")[3]).isEqualTo(9007199254740992.0);
        assertThat(run.reader.survey().spectrumArrays()).containsExactly("counts");
        assertThat(run.reader.warnings()).contains("B-31 64-bit integer array values rounded to the nearest double (1)");
    }

    @Test
    void int64ValuesRoundOnlyWhereTheirDoubleDiffers(@TempDir Path dir) throws IOException {
        long[] counts = new long[15];
        counts[0] = Long.MAX_VALUE;
        counts[1] = Long.MIN_VALUE;
        counts[2] = 9007199254740992L;
        counts[3] = -9007199254740993L;
        String big = "<binaryDataArray encodedLength=\"160\">\n" + I64 + "\n" + NO_COMPRESSION + "\n"
                + cv("MS:1000786", "non-standard data array", "counts", null) + "\n<binary>" + longs(counts)
                + "</binary>\n</binaryDataArray>\n";
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, big));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays().get("counts")).startsWith(0x1p63, -0x1p63, 0x1p53, -0x1p53);
        assertThat(run.reader.warnings())
                .contains("B-31 64-bit integer array values rounded to the nearest double (2)");
    }

    @Test
    void arrayParamsAndDataProcessingRefsOfKeptArraysAreLost(@TempDir Path dir) throws IOException {
        String sn = "<binaryDataArray encodedLength=\"160\" dataProcessingRef=\"pwiz_processing\">\n"
                + cv("MS:9999999", "unknown term", "", null) + "\n" + F64 + "\n" + NO_COMPRESSION + "\n" + SN_TYPE
                + "\n<userParam name=\"note\" value=\"x\"/>\n<binary>" + floats(ramp(15))
                + "</binary>\n</binaryDataArray>\n";
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19, sn));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays()).containsOnlyKeys(SN);
        assertThat(run.reader.warnings()).contains(ARRAY_DP_LOST + " (11)",
                "B-21 lost array params other than array type, data type and compression (2)");
    }

    /** The legacy pair: an MS-Numpress linear payload deflated with zlib, under MS:1002312 and MS:1000574. */
    @Test
    void aLegacyNumpressLinearAndZlibPairIsDecoded(@TempDir Path dir) throws IOException {
        double[] mz = new double[15];
        for (int i = 0; i < mz.length; i++) {
            mz[i] = 400.123 + 13.371 * i;
        }
        double fixedPoint = MSNumpress.optimalLinearFixedPoint(mz, mz.length);
        byte[] linear = linear(mz);
        String terms = F64 + "\n" + cv("MS:1002312", "MS-Numpress linear prediction compression", "", null) + "\n"
                + cv("MS:1000574", "zlib compression", "", null) + "\n"
                + cv("MS:1000514", "m/z array", "", "MS:1000040");
        Path file = tinyWith(dir, s -> spectrum19With(s, 15, binaryArray(terms, deflate(linear)),
                array(cv("MS:1000515", "intensity array", "", "MS:1000131"), ramp(15))));

        double[] read = read(file).spectra.get(0).mz();

        assertThat(read).hasSize(15);
        for (int i = 0; i < mz.length; i++) {
            assertThat(read[i]).as("m/z %d", i).isCloseTo(mz[i], within(0.5 / fixedPoint));
        }
    }

    /** MS-Numpress PIC counts up to 2147483647 (2^31 - 1) read exactly. */
    @Test
    void picCountsUpTo2147483647AreReadExactly(@TempDir Path dir) throws IOException {
        double[] counts = picCounts(2147483647.0);
        Path file = tinyWith(dir, s -> spectrum19With(s, 15, array(cv("MS:1000514", "m/z array", "", "MS:1000040"),
                ramp(15)), picArray(counts)));

        assertThat(read(file).spectra.get(0).intensity()).containsExactly(counts);
    }

    /** Guard 3: jmzML decodes a PIC count of 2^31 or more as a negative number; read() rejects it, survey() cannot. */
    @Test
    void picCountsOf2To31AndAboveAreRejectedByRead(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> spectrum19With(s, 15, array(cv("MS:1000514", "m/z array", "", "MS:1000040"),
                ramp(15)), picArray(picCounts(3e9))));
        MzMLReader reader = new MzMLReader(file);

        assertThat(reader.survey().spectrumIds()).hasSize(4);
        assertThatThrownBy(() -> reader.read(new Run(reader)))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(prefix(file, "spectrum #0 (scan=19)"))
                .hasMessageContainingAll("MS:1000515", "-1294967296", "3000000000")
                .hasNoCause();
    }

    /**
     * jmzML's MS-Numpress decoding overwrites an array's declared length with the decoded one: every array of a
     * spectrum is classified before any is decoded, so the charge array, declared 14 like the m/z array that decodes to
     * 15 values, is kept by read() as survey() declared it.
     */
    @Test
    void arraysAreClassifiedBeforeNumpressDecodingRewritesTheirLength(@TempDir Path dir) throws IOException {
        double[] mz = ramp(15);
        double[] intensity = picCounts(1);
        String linear = cv("MS:1002312", "MS-Numpress linear prediction compression", "", null);
        Path file = tinyWith(dir, s -> spectrum19With(s, 14,
                binaryArray(F64 + "\n" + linear + "\n" + cv("MS:1000514", "m/z array", "", "MS:1000040"), linear(mz)),
                binaryArray(F64 + "\n" + linear + "\n" + cv("MS:1000515", "intensity array", "", "MS:1000131"),
                        linear(intensity)),
                array(cv("MS:1000516", "charge array", "", null), ramp(15))));

        Run run = read(file);

        Spectrum s0 = run.spectra.get(0);
        assertThat(run.reader.survey().spectrumArrays()).containsExactly("MS:1000516");
        assertThat(s0.arrays().get("MS:1000516")).containsExactly(ramp(15));
        assertThat(s0.mz()).hasSize(15);
        assertThat(s0.intensity()).hasSize(15);
        for (int i = 0; i < 15; i++) {
            assertThat(s0.mz()[i]).isCloseTo(mz[i], within(0.5 / MSNumpress.optimalLinearFixedPoint(mz, 15)));
            assertThat(s0.intensity()[i])
                    .isCloseTo(intensity[i], within(0.5 / MSNumpress.optimalLinearFixedPoint(intensity, 15)));
        }
    }

    /** An array jmzML cannot decode (zlib declared, raw bytes given) passes survey() and fails read(), named. */
    @Test
    void anArrayJmzmlCannotDecodeIsRejectedByRead(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> spectrum19With(s, 15, array(cv("MS:1000514", "m/z array", "", "MS:1000040"),
                ramp(15)), binaryArray(F64 + "\n" + cv("MS:1000574", "zlib compression", "", null) + "\n"
                + cv("MS:1000515", "intensity array", "", "MS:1000131"), Base64.getDecoder().decode(floats(ramp(15))))));
        MzMLReader reader = new MzMLReader(file);

        assertThat(reader.survey().spectrumIds()).hasSize(4);
        assertThatThrownBy(() -> reader.read(new Run(reader)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(prefix(file, "spectrum #0 (scan=19)") + "its MS:1000515 array with the terms [MS:1000523,"
                        + " MS:1000574, MS:1000515] cannot be decoded by jmzML 1.7.11: Encountered wrong data format"
                        + " while trying to decompress binary data!")
                .cause().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSurveyCountsTheChromatogramsOfEachFixture(@TempDir Path dir) throws IOException {
        Map<String, Integer> chromatograms = Map.of(MzMLFixtures.TINY, 2, MzMLFixtures.SMALL, 1,
                MzMLFixtures.TOFSULFAS, 2, MzMLFixtures.DIAPASEF, 2);
        for (String fixture : MzMLFixtures.NAMES) {
            MzMLReader reader = new MzMLReader(MzMLFixtures.extract(fixture, dir));

            assertThat(reader.survey().chromatograms()).as(fixture).isEqualTo(chromatograms.get(fixture));
        }
    }

    @Test
    void aRunWithoutChromatogramListHasSpectraOnly(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> cut(s, "<chromatogramList ", "</chromatogramList>"));

        Run run = read(file);

        assertThat(run.spectra).hasSize(4);
        assertThat(run.chromatograms).isEmpty();
        assertThat(run.reader.survey().chromatograms()).isZero();
    }

    @Test
    void aRunWithoutSpectrumListHasChromatogramsOnly(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> cut(s, "<spectrumList ", "</spectrumList>"));

        Run run = read(file);

        assertThat(run.spectra).isEmpty();
        assertThat(run.reader.survey().spectrumIds()).isEmpty();
        assertThat(run.chromatograms).extracting(Chromatogram::id).containsExactly("tic", "sic");
    }

    @Test
    void anEmptySpectrumListHasNoSpectra(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceRegion(s, "<spectrumList ", "</spectrumList>",
                "<spectrumList count=\"0\" defaultDataProcessingRef=\"pwiz_processing\">\n</spectrumList>"));

        Run run = read(file);

        assertThat(run.spectra).isEmpty();
        assertThat(run.chromatograms).hasSize(2);
        assertThat(run.chromatograms.get(1).dataProcessingRef()).isNull();
    }

    /** The first array type term keys an array; a second one is an array param like any other, lost (B-21). */
    @Test
    void anArrayWithTwoArrayTypesIsKeyedByTheFirst(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19,
                array(SN_TYPE + "\n" + cv("MS:1000516", "charge array", "", null), ramp(15))));

        Run run = read(file);

        assertThat(run.spectra.get(0).arrays()).containsOnlyKeys(SN);
        assertThat(run.reader.warnings())
                .contains("B-21 lost array params other than array type, data type and compression (1)");
    }

    @Test
    void aWavelengthArrayOfAnMsSpectrumIsAnExtraArray(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withArray(s, SCAN_19,
                array(cv("MS:1000617", "wavelength array", "", "UO:0000018"), ramp(15))));

        Run run = read(file);

        assertThat(run.wavelengthSpectra).isEmpty();
        assertThat(run.spectra.get(0).arrays()).containsOnlyKeys("MS:1000617");
        assertThat(run.reader.survey().spectrumArrays()).containsExactly("MS:1000617");
    }

    // ---- (e) spectra, scans and precursors -----------------------------------------------------------------------

    @Test
    void bothPolaritiesGiveUnknownAndAWarning(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<cvParam cvRef=\"MS\" accession=\"MS:1000511\"",
                cv("MS:1000129", "negative scan", "", null) + "\n<cvParam cvRef=\"MS\" accession=\"MS:1000511\""));

        Run run = read(file);

        SpectrumDescription d = run.spectra.get(0).description();
        assertThat(d.polarity()).isEqualTo(Polarity.UNKNOWN);
        assertThat(d.parameters()).extracting(Param::accession).contains("MS:1000130", "MS:1000129");
        assertThat(run.spectra.get(1).description().polarity()).isEqualTo(Polarity.POSITIVE);
        assertThat(run.reader.warnings()).contains(MzMLReader.BOTH_POLARITIES + " (1)");
    }

    @Test
    void bothRepresentationsGiveUnknownAndAWarning(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<cvParam cvRef=\"MS\" accession=\"MS:1000511\"",
                cv("MS:1000128", "profile spectrum", "", null) + "\n<cvParam cvRef=\"MS\" accession=\"MS:1000511\""));

        Run run = read(file);

        SpectrumDescription d = run.spectra.get(0).description();
        assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.UNKNOWN);
        assertThat(d.parameters()).extracting(Param::accession).contains("MS:1000127", "MS:1000128");
        assertThat(d.numberOfDataPoints()).isEqualTo(15);
        assertThat(d.numberOfPeaks()).isZero();
        assertThat(run.reader.warnings()).contains(MzMLReader.BOTH_REPRESENTATIONS + " (1)");
    }

    /**
     * jmzML puts a referenced group's params after the element's own (Q41): scan=19's params end with its group's
     * MS:1000579 and MS:1000130 (kept as a param here, since a second polarity makes the polarity unknown), followed
     * only by its scanList's MS:1000795.
     */
    @Test
    void groupParamsComeAfterTheSpectrumsOwn(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<cvParam cvRef=\"MS\" accession=\"MS:1000511\"",
                cv("MS:1000129", "negative scan", "", null) + "\n<cvParam cvRef=\"MS\" accession=\"MS:1000511\""));

        assertThat(read(file).spectra.get(0).description().parameters()).extracting(Param::accession)
                .containsExactly("MS:1000129", "MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505", "MS:1000285",
                        "MS:1000579", "MS:1000130", "MS:1000795");
    }

    @Test
    void aCvRefAliasInTheRunIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<cvParam cvRef=\"MS\" accession=\"MS:1000511\"",
                "<cvParam cvRef=\"PSI-MS\" accession=\"MS:1000511\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).msLevel()).isEqualTo(1);
        assertThat(run.reader.warnings()).contains("B-30 cvRef aliases replaced by the accession prefix (1)");
    }

    /** An array's params are read as every element's: a cvRef alias on its array type term is noted (B-30). */
    @Test
    void aCvRefAliasOnAnArrayTermIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<cvParam cvRef=\"MS\" accession=\"MS:1000514\"",
                "<cvParam cvRef=\"PSI-MS\" accession=\"MS:1000514\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).hasSize(15);
        assertThat(run.reader.warnings()).contains("B-30 cvRef aliases replaced by the accession prefix (1)");
    }

    /** An array's params are read as every element's: a userParam value that does not fit its type is noted (B-14). */
    @Test
    void anArrayUserParamOfAnotherTypeIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<binary>",
                "<userParam name=\"note\" type=\"xsd:int\" value=\"abc\"/>\n<binary>")); // on its m/z array

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).hasSize(15);
        assertThat(run.reader.warnings()).contains(
                "B-14 userParam values that do not match their type kept as text (1)",
                "B-21 lost array params other than array type, data type and compression (1)");
    }

    /**
     * A group's cvRef alias (B-30) and userParam type mismatch (B-14) are noted once, when {@code header()} reads the
     * group, not again for each of the 3 spectra jmzML copies CommonMS1SpectrumParams into; each still carries them.
     */
    @Test
    void aSpectrumGroupsAliasAndTypeMismatchAreNotedOnce(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceOnce(s, "<cvParam cvRef=\"MS\" accession=\"MS:1000579\"",
                "<cvParam cvRef=\"PSI-MS\" accession=\"MS:1000579\""), "id=\"CommonMS1SpectrumParams\"",
                "</referenceableParamGroup>",
                "<userParam name=\"note\" type=\"xsd:int\" value=\"abc\"/>\n</referenceableParamGroup>"));

        Run run = read(file);

        assertThat(ids(run.spectra)).containsExactly("scan=19", "scan=20", "scan=21",
                "sample=1 period=1 cycle=22 experiment=1");
        for (int i : new int[] {0, 2, 3}) {
            assertThat(run.spectra.get(i).description().parameters()).as(ids(run.spectra).get(i))
                    .contains(new Param("MS1 spectrum", "MS:1000579", null, null), new Param("note", null, "abc", null));
        }
        assertThat(run.reader.warnings()).contains("B-30 cvRef aliases replaced by the accession prefix (1)",
                "B-14 userParam values that do not match their type kept as text (1)");
    }

    /**
     * A group a binaryDataArray references is noted once too: its cvRef alias (on the m/z array's data type, which
     * the array takes from it) and its userParam type mismatch, each once; the userParam is a lost array param (B-21).
     */
    @Test
    void anArrayGroupsAliasAndTypeMismatchAreNotedOnce(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceOnce(s, "</referenceableParamGroupList>",
                "<referenceableParamGroup id=\"MzArrayParams\">\n"
                        + "<cvParam cvRef=\"PSI-MS\" accession=\"MS:1000523\" name=\"64-bit float\" value=\"\"/>\n"
                        + "<userParam name=\"note\" type=\"xsd:int\" value=\"abc\"/>\n"
                        + "</referenceableParamGroup>\n</referenceableParamGroupList>"),
                SCAN_19, F64, "<referenceableParamGroupRef ref=\"MzArrayParams\"/>")); // in its m/z array

        Run run = read(file);

        assertThat(run.spectra.get(0).mz()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(run.reader.warnings()).contains("B-30 cvRef aliases replaced by the accession prefix (1)",
                "B-14 userParam values that do not match their type kept as text (1)",
                "B-21 lost array params other than array type, data type and compression (1)");
    }

    @Test
    void aRepeatedSpectrumIdResolvesToItsFirstOccurrence(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<spectrum index=\"2\" id=\"scan=21\"",
                "<spectrum index=\"2\" id=\"scan=19\""));

        Run run = read(file);

        assertThat(run.reader.survey().spectrumIds()).containsExactly("scan=19", "scan=20", "scan=19",
                "sample=1 period=1 cycle=22 experiment=1");
        assertThat(run.spectra.get(1).description().primaryPrecursor().precursorIndex()).isZero();
    }

    @Test
    void aScanInstrumentConfigurationRefNamingNoneReadsAsNullWithAWarning(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "instrumentConfigurationRef=\"LCQ_x0020_Deca\"",
                "instrumentConfigurationRef=\"missing_IC\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().scans()).extracting(ScanEvent::instrumentConfigurationRef)
                .containsExactly((Integer) null);
        assertThat(run.spectra.get(1).description().scans()).extracting(ScanEvent::instrumentConfigurationRef)
                .containsExactly(0);
        assertThat(run.reader.warnings()).contains(
                "B-39 instrument configuration refs that name no instrument configuration read as null (1)");
    }

    @Test
    void anInjectionTimeInSecondsStaysAScanParam(@TempDir Path dir) throws IOException {
        String injection = cv("MS:1000927", "ion injection time", "0.05", "UO:0000010");
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "<scanWindowList", injection + "\n<scanWindowList"));

        Run run = read(file);

        ScanEvent scan = run.spectra.get(0).description().scans().get(0);
        assertThat(scan.injectionTime()).isNull();
        assertThat(scan.parameters()).containsExactly(new Param("ion injection time", "MS:1000927", 0.05,
                "UO:0000010"));
        assertThat(run.reader.warnings()).contains(MzMLReader.INJECTION_TIME_KEPT + " (1)");
    }

    @Test
    void startTimesInMillisecondsAndHoursAreReadInMinutes(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceAfter(s, SCAN_19,
                        "value=\"5.8905000000000003\" unitCvRef=\"UO\" unitAccession=\"UO:0000031\"",
                        "value=\"90000\" unitCvRef=\"UO\" unitAccession=\"UO:0000028\""), SCAN_20,
                "value=\"5.9904999999999999\" unitCvRef=\"UO\" unitAccession=\"UO:0000031\"",
                "value=\"0.5\" unitCvRef=\"UO\" unitAccession=\"UO:0000032\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().retentionTime()).isEqualTo(1.5);
        assertThat(run.spectra.get(1).description().scans().get(0).startTime()).isEqualTo(30.0);
    }

    @Test
    void aWindowWithOneLimitIsDropped(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceCvParam(s, SCAN_19, "accession=\"MS:1000500\"", ""));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().scans().get(0).scanWindows()).isEmpty();
        assertThat(run.spectra.get(1).description().scans().get(0).scanWindows()).hasSize(1);
        assertThat(run.reader.warnings()).contains("B-17 scan windows without both limits in m/z dropped (1)");
    }

    @Test
    void aWindowWithALimitInNanometersIsDropped(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19,
                "value=\"400\" unitCvRef=\"MS\" unitAccession=\"MS:1000040\"",
                "value=\"400\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\""));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().scans().get(0).scanWindows()).isEmpty();
        assertThat(run.reader.warnings()).contains("B-17 scan windows without both limits in m/z dropped (1)",
                "B-17 scan window params other than the limits lost (1)");
    }

    @Test
    void anIsolationWindowTargetInNanometersStaysAWindowParam(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20,
                "value=\"445.30000000000001\" unitCvRef=\"MS\" unitAccession=\"MS:1000040\"",
                "value=\"445.30000000000001\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\""));

        IsolationWindow window = read(file).spectra.get(1).description().primaryPrecursor().isolationWindow();

        assertThat(window.targetMz()).isNull();
        assertThat(window.lowerOffset()).isEqualTo(0.5);
        assertThat(window.parameters()).containsExactly(new Param("isolation window target m/z", "MS:1000827", 445.3,
                "UO:0000018"));
    }

    @Test
    void aSelectedIonMzInNanometersStaysAParamAndIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20,
                "value=\"445.33999999999997\" unitCvRef=\"MS\" unitAccession=\"MS:1000040\"",
                "value=\"445.33999999999997\" unitCvRef=\"UO\" unitAccession=\"UO:0000018\""));

        Run run = read(file);

        SelectedIon ion = run.spectra.get(1).description().primaryPrecursor().primaryIon();
        assertThat(ion.mz()).isNaN();
        assertThat(ion.charge()).isEqualTo(2);
        assertThat(ion.parameters()).containsExactly(new Param("selected ion m/z", "MS:1000744", 445.34,
                "UO:0000018"));
        assertThat(run.reader.warnings())
                .contains("B-18 selected ions without an m/z in unit MS:1000040, which the mzPeak read drops (1)");
    }

    @Test
    void aSelectedIonWithoutMzIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceCvParam(s, SCAN_20, "accession=\"MS:1000744\"", ""));

        Run run = read(file);

        SelectedIon ion = run.spectra.get(1).description().primaryPrecursor().primaryIon();
        assertThat(ion.mz()).isNaN();
        assertThat(ion.intensity()).isEqualTo(120053.0);
        assertThat(run.reader.warnings())
                .contains("B-18 selected ions without an m/z in unit MS:1000040, which the mzPeak read drops (1)");
    }

    @Test
    void aProductListIsLost(@TempDir Path dir) throws IOException {
        String products = "<productList count=\"1\">\n<product>\n<isolationWindow>\n"
                + cv("MS:1000827", "isolation window target m/z", "200", "MS:1000040")
                + "\n</isolationWindow>\n</product>\n</productList>\n";
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "<binaryDataArrayList",
                products + "<binaryDataArrayList"));

        Run run = read(file);

        assertThat(run.spectra.get(1).description().precursors()).hasSize(1);
        assertThat(run.reader.warnings()).contains("B-20 spectrum productList lost (1)");
    }

    @Test
    void anExternalPrecursorIdStaysExternalAndItsSourceFileRefIsLost(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<precursor spectrumRef=\"scan=19\">",
                "<precursor externalSpectrumID=\"scan=19\" sourceFileRef=\"tiny1.yep\">"));

        Run run = read(file);

        Precursor precursor = run.spectra.get(1).description().primaryPrecursor();
        assertThat(precursor.precursorIndex()).isNull();
        assertThat(precursor.precursorId()).isEqualTo("scan=19");
        assertThat(run.reader.warnings()).noneMatch(w -> w.startsWith("B-33"))
                .contains("B-16 sourceFileRef of scans and precursors lost (1)");
    }

    @Test
    void aScanExternalIdEqualToALocalIdIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_20, "<scan instrumentConfigurationRef",
                "<scan externalSpectrumID=\"scan=19\" instrumentConfigurationRef"));

        Run run = read(file);

        assertThat(run.spectra.get(1).description().scans().get(0).spectrumReference()).isEqualTo("scan=19");
        assertThat(run.reader.warnings()).filteredOn(w -> w.startsWith("B-33")).containsExactly(
                "B-33 spectrum references whose kind (local or external) changes on the way back (1)");
    }

    @Test
    void scanReferencesPreferTheSpectrumRefAndNoteOnlyLocalExternalIds(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceAfter(s, SCAN_19, "<scan instrumentConfigurationRef",
                        "<scan spectrumRef=\"scan=21\" externalSpectrumID=\"scan=20\" sourceFileRef=\"tiny1.yep\""
                                + " instrumentConfigurationRef"), SCAN_20, "<scan instrumentConfigurationRef",
                "<scan externalSpectrumID=\"elsewhere\" instrumentConfigurationRef"));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().scans().get(0).spectrumReference()).isEqualTo("scan=21");
        assertThat(run.spectra.get(1).description().scans().get(0).spectrumReference()).isEqualTo("elsewhere");
        assertThat(run.reader.warnings()).noneMatch(w -> w.startsWith("B-33"))
                .contains("B-16 sourceFileRef of scans and precursors lost (1)");
    }

    @Test
    void aForwardPrecursorRefIsResolvedThroughTheSurvey(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<precursor spectrumRef=\"scan=19\">",
                "<precursor spectrumRef=\"sample=1 period=1 cycle=22 experiment=1\">"));

        Precursor precursor = read(file).spectra.get(1).description().primaryPrecursor();

        assertThat(precursor.precursorIndex()).isEqualTo(3L);
        assertThat(precursor.precursorId()).isEqualTo("sample=1 period=1 cycle=22 experiment=1");
    }

    @Test
    void aPrecursorRefToNoSpectrumHasNoIndex(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<precursor spectrumRef=\"scan=19\">",
                "<precursor spectrumRef=\"scan=1\">"));

        Run run = read(file);

        Precursor precursor = run.spectra.get(1).description().primaryPrecursor();
        assertThat(precursor.precursorIndex()).isNull();
        assertThat(precursor.precursorId()).isEqualTo("scan=1");
        assertThat(run.reader.warnings()).noneMatch(w -> w.startsWith("B-33"));
    }

    @Test
    void aPrecursorRefToAWavelengthSpectrumHasNoIndexAndIsNoted(@TempDir Path dir) throws IOException {
        String precursors = "<precursorList count=\"1\">\n<precursor spectrumRef=\"merged=212 row=0\">\n<activation>\n"
                + cv("MS:1000133", "collision-induced dissociation", "", null)
                + "\n</activation>\n</precursor>\n</precursorList>\n";
        Path file = tofWith(dir, s -> replaceAfter(s, "id=\"scanId=639\"", "<binaryDataArrayList",
                precursors + "<binaryDataArrayList"));

        Run run = read(file);

        Precursor precursor = run.spectra.get(0).description().primaryPrecursor();
        assertThat(precursor.precursorIndex()).isNull();
        assertThat(precursor.precursorId()).isEqualTo("merged=212 row=0");
        assertThat(precursor.isolationWindow()).isNull();
        assertThat(run.reader.warnings()).filteredOn(w -> w.startsWith("B-33")).containsExactly(
                "B-33 spectrum references whose kind (local or external) changes on the way back (1)");
    }

    @Test
    void aScanExternalIdEqualToAWavelengthSpectrumIdIsNoted(@TempDir Path dir) throws IOException {
        Path file = tofWith(dir, s -> replaceAfter(s, "id=\"scanId=639\"", "<scan>",
                "<scan externalSpectrumID=\"merged=213 row=1\">"));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().scans().get(0).spectrumReference()).isEqualTo("merged=213 row=1");
        assertThat(run.reader.warnings()).filteredOn(w -> w.startsWith("B-33")).hasSize(1);
    }

    @Test
    void valuesOfTheWrongTypeStayParams(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceAfter(replaceAfter(replaceAfter(s,
                                SCAN_19, "name=\"preset scan configuration\" value=\"3\"",
                                "name=\"preset scan configuration\" value=\"third\""),
                        SCAN_20, "name=\"charge state\" value=\"2\"", "name=\"charge state\" value=\"3000000000\""),
                SCAN_20, "name=\"isolation window lower offset\" value=\"0.5\"",
                "name=\"isolation window lower offset\" value=\"half\""),
                SCAN_20, "value=\"5.9904999999999999\"", "value=\"soon\""));

        Run run = read(file);

        ScanEvent scan19 = run.spectra.get(0).description().scans().get(0);
        assertThat(scan19.presetScanConfiguration()).isNull();
        assertThat(scan19.parameters()).containsExactly(new Param("preset scan configuration", "MS:1000616", "third",
                null));
        SpectrumDescription d1 = run.spectra.get(1).description();
        assertThat(d1.retentionTime()).isNaN();
        assertThat(d1.scans().get(0).parameters()).contains(new Param("scan start time", "MS:1000016", "soon",
                "UO:0000031"));
        Precursor precursor = d1.primaryPrecursor();
        assertThat(precursor.isolationWindow().lowerOffset()).isNull();
        assertThat(precursor.isolationWindow().parameters()).containsExactly(
                new Param("isolation window lower offset", "MS:1000828", "half", "MS:1000040"));
        assertThat(precursor.primaryIon().charge()).isNull();
        assertThat(precursor.primaryIon().parameters()).containsExactly(
                new Param("charge state", "MS:1000041", 3000000000L, null));
    }

    @Test
    void aFilterStringIsKeptVerbatim(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, "value=\"+ c NSI Full ms [ 400.00-1800.00]\"",
                "value=\"1.50\""));

        assertThat(read(file).spectra.get(0).description().scans().get(0).filterString()).isEqualTo("1.50");
    }

    @Test
    void spectrumAttributesAndScanListParamsTheModelLacksAreNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceOnce(s, SCAN_19 + " defaultArrayLength=\"15\"",
                        SCAN_19 + " defaultArrayLength=\"15\" dataProcessingRef=\"pwiz_processing\""), SCAN_19,
                "<scan instrumentConfigurationRef", "<userParam name=\"averaged\" value=\"no\"/>\n"
                        + "<scan instrumentConfigurationRef"));

        Run run = read(file);

        assertThat(run.spectra.get(0).description().parameters()).endsWith(
                new Param("no combination", "MS:1000795", null, null), new Param("averaged", null, "no", null));
        assertThat(run.reader.warnings()).contains("B-10 spectrum@dataProcessingRef lost (1)",
                "B-13 scanList params other than the combination term moved to the spectrum (1)");
    }

    @Test
    void aSpectrumWithoutArraysScanListOrMsLevelIsAnEmptyMsSpectrum(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> {
            int start = s.indexOf(SCAN_21);
            int end = s.indexOf("</spectrum>", start);
            String spectrum = replaceCvParam(s.substring(start, end), "", "accession=\"MS:1000511\"", "")
                    .replaceAll("(?s)\\s*<scanList .*</scanList>", "")
                    .replaceAll("(?s)\\s*<binaryDataArrayList .*</binaryDataArrayList>", "");
            return s.substring(0, start) + spectrum + s.substring(end);
        });

        Run run = read(file);

        assertThat(run.wavelengthSpectra).isEmpty();
        SpectrumDescription d = run.spectra.get(2).description();
        assertThat(d.msLevel()).isZero();
        assertThat(d.scans()).isEmpty();
        assertThat(d.retentionTime()).isNaN();
        assertThat(run.spectra.get(2).mz()).isEmpty();
    }

    // ---- (e) wavelength spectra ----------------------------------------------------------------------------------

    @Test
    void lambdaMaxInNanometersIsPromoted(@TempDir Path dir) throws IOException {
        for (String unit : List.of("UO:0000018", "UO:0000017")) {
            Path file = tofWith(dir, s -> replaceAfter(s, UV_0, "<scanList ",
                    cv("MS:1003812", "lambda max", "374.0", unit) + "\n<scanList "));

            WavelengthSpectrum uv = read(file).wavelengthSpectra.get(0);

            if (unit.equals("UO:0000018")) {
                assertThat(uv.lambdaMax()).isEqualTo(374.0);
                assertThat(uv.parameters()).extracting(Param::accession).doesNotContain("MS:1003812");
            } else {
                assertThat(uv.lambdaMax()).isNull();
                assertThat(uv.parameters()).contains(new Param("lambda max", "MS:1003812", 374.0, "UO:0000017"));
            }
        }
    }

    @Test
    void anExtraArrayOfAWavelengthSpectrumIsDropped(@TempDir Path dir) throws IOException {
        Path file = tofWith(dir, s -> withArray(s, UV_0, array(SN_TYPE, new double[96])));

        Run run = read(file);

        assertThat(run.wavelengthSpectra.get(0).size()).isEqualTo(96);
        assertThat(run.reader.survey().spectrumArrays()).isEmpty();
        assertThat(run.reader.warnings()).contains("B-28 extra arrays of wavelength spectra dropped (1)");
    }

    @Test
    void wavelengthSpectrumContentBesidesTheStartTimeIsLost(@TempDir Path dir) throws IOException {
        String scan = "<scan>\n" + cv("MS:1000016", "scan start time", "0.5", "UO:0000031") + "\n</scan>\n";
        String precursors = "<precursorList count=\"1\">\n<precursor>\n<activation/>\n</precursor>\n</precursorList>\n";
        Path file = tofWith(dir, s -> {
            String edited = replaceAfter(s, UV_0, "</scanList>", scan + "</scanList>");
            int start = edited.indexOf(UV_1);
            int end = edited.indexOf("</spectrum>", start);
            edited = edited.substring(0, start)
                    + edited.substring(start, end).replaceAll("(?s)\\s*<scanList .*</scanList>", "")
                    + edited.substring(end);
            edited = replaceAfter(edited, UV_2, "<binaryDataArrayList", precursors + "<binaryDataArrayList");
            return replaceAfter(edited, "id=\"merged=215 row=3\"", "<scan>", "<scan instrumentConfigurationRef=\"IC1\">");
        });

        Run run = read(file);

        assertThat(run.wavelengthSpectra.get(0).time()).isEqualTo(0.001833333333);
        assertThat(run.wavelengthSpectra.get(1).time()).isNaN();
        // a second scan, a precursor list and a scan's configuration ref; a wavelength spectrum without scans loses none
        assertThat(run.reader.warnings()).contains(
                "B-23 wavelength spectrum content other than the first scan's start time lost (3)");
    }

    @Test
    void aSpectrumWithoutArraysAndAWavelengthTypeIsAWavelengthSpectrum(@TempDir Path dir) throws IOException {
        Path file = tofWith(dir, s -> {
            int start = s.indexOf(UV_0);
            int end = s.indexOf("</spectrum>", start);
            return s.substring(0, start) + s.substring(start, end)
                    .replaceAll("(?s)\\s*<binaryDataArrayList .*</binaryDataArrayList>", "") + s.substring(end);
        });

        Run run = read(file);

        assertThat(run.wavelengthSpectra).hasSize(520);
        assertThat(run.wavelengthSpectra.get(0).size()).isZero();
        assertThat(run.reader.warnings()).noneMatch(w -> w.startsWith("B-9"));
    }

    @Test
    void msSpectraAfterAWavelengthSpectrumLoseTheInterleaving(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, SCAN_19, TINY_MZ_UNIT,
                "accession=\"MS:1000617\" name=\"wavelength array\" value=\"\" unitCvRef=\"UO\""
                        + " unitAccession=\"UO:0000018\""));

        Run run = read(file);

        assertThat(run.wavelengthSpectra).extracting(WavelengthSpectrum::id).containsExactly("scan=19");
        assertThat(run.wavelengthSpectra.get(0).time()).isEqualTo(5.8905);
        assertThat(run.spectra).extracting(Spectrum::index).containsExactly(0L, 1L, 2L);
        assertThat(run.spectra.get(0).description().primaryPrecursor().precursorIndex()).isNull();
        assertThat(run.reader.warnings()).contains(
                "B-11 MS spectra after wavelength spectra, their interleaving lost (3)",
                "B-23 wavelength spectrum content other than the first scan's start time lost (1)",
                "B-33 spectrum references whose kind (local or external) changes on the way back (1)");
    }

    // ---- (e) chromatograms ---------------------------------------------------------------------------------------

    @Test
    void aChromatogramPrecursorSpectrumRefIsItsIdWithoutIndex(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "id=\"sic\"", "<precursor>",
                "<precursor spectrumRef=\"scan=19\">"));

        Precursor precursor = read(file).chromatograms.get(1).precursors().get(0);

        assertThat(precursor.precursorIndex()).isNull();
        assertThat(precursor.precursorId()).isEqualTo("scan=19");
    }

    @Test
    void aChromatogramPrecursorExternalIdEqualToALocalIdIsNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "id=\"sic\"", "<precursor>",
                "<precursor externalSpectrumID=\"scan=20\">"));

        Run run = read(file);

        assertThat(run.chromatograms.get(1).precursors().get(0).precursorId()).isEqualTo("scan=20");
        assertThat(run.reader.warnings()).filteredOn(w -> w.startsWith("B-33")).hasSize(1);
    }

    @Test
    void aChromatogramWithoutTypeAndArraysAndANegativePolarity(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> {
            String edited = replaceCvParam(s, "id=\"tic\"", "accession=\"MS:1000235\"",
                    cv("MS:1000129", "negative scan", "", null));
            int start = edited.indexOf("id=\"sic\"");
            int end = edited.indexOf("</chromatogram>", start);
            return edited.substring(0, start) + edited.substring(start, end)
                    .replaceAll("(?s)\\s*<binaryDataArrayList .*</binaryDataArrayList>", "") + edited.substring(end);
        });

        Run run = read(file);

        Chromatogram tic = run.chromatograms.get(0);
        assertThat(tic.typeCurie()).isNull();
        assertThat(tic.polarity()).isEqualTo(Polarity.NEGATIVE);
        assertThat(tic.parameters()).isEmpty();
        Chromatogram sic = run.chromatograms.get(1);
        assertThat(sic.size()).isZero();
        assertThat(sic.intensityUnit()).isNull();
    }

    @Test
    void aChromatogramWithoutTimeArrayLosesItsArrays(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> removeFirstArray(s, "id=\"tic\""));

        Run run = read(file);

        assertThat(run.chromatograms.get(0).size()).isZero();
        assertThat(run.chromatograms.get(0).intensityUnit()).isNull();
        assertThat(run.reader.warnings())
                .contains("B-9 arrays of spectra and chromatograms without an axis or intensity array dropped (1)");
    }

    @Test
    void aChromatogramDataProcessingEqualToTheSpectrumListDefaultIsNull(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s,
                        "id=\"tic\" defaultArrayLength=\"15\" dataProcessingRef=\"CompassXtract_x0020_processing\"",
                        "id=\"tic\" defaultArrayLength=\"15\""),
                "<chromatogramList count=\"2\" defaultDataProcessingRef=\"pwiz_processing\">",
                "<chromatogramList count=\"2\" defaultDataProcessingRef=\"CompassXtract_x0020_processing\">"));

        Run run = read(file);

        assertThat(run.chromatograms).extracting(Chromatogram::dataProcessingRef)
                .containsExactly("CompassXtract_x0020_processing", null);
    }

    /**
     * B-40: the chromatogramList's default data processing is kept as written in its start tag, a character reference
     * included, and noted once per read (survey() notes nothing); sic, given no ref of its own, takes it.
     */
    @Test
    void aChromatogramListDefaultWithAnAmpersandIsKeptAsWrittenAndNoted(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s,
                        "<chromatogramList count=\"2\" defaultDataProcessingRef=\"pwiz_processing\">",
                        "<chromatogramList count=\"2\" defaultDataProcessingRef=\"pwiz&#x5F;processing\">"),
                "id=\"sic\" defaultArrayLength=\"10\" dataProcessingRef=\"pwiz_processing\"",
                "id=\"sic\" defaultArrayLength=\"10\""));
        MzMLReader reader = new MzMLReader(file);
        Run run = new Run(reader);

        reader.survey();
        reader.read(run);

        assertThat(run.chromatograms.get(1).dataProcessingRef()).isEqualTo("pwiz&#x5F;processing");
        assertThat(reader.warnings()).filteredOn(w -> w.startsWith("B-40")).containsExactly(
                "B-40 run, spectrumList and chromatogramList attribute values containing & kept as written (1)");
        assertThat(read(MzMLFixtures.extract(MzMLFixtures.TINY, dir)).reader.warnings())
                .noneMatch(w -> w.startsWith("B-40"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** What a read gives: the visitor collects it in document order. */
    private static class Run implements MzMLReader.Visitor {
        final MzMLReader reader;
        final List<Spectrum> spectra = new ArrayList<>();
        final List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>();
        final List<Chromatogram> chromatograms = new ArrayList<>();

        Run(MzMLReader reader) {
            this.reader = reader;
        }

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

        Model model() {
            MzMLReader.Header header = reader.header();
            return new Model(spectra, chromatograms, wavelengthSpectra, header.fileMetadata(), header.scanSettings(),
                    header.cvList());
        }
    }

    /**
     * Reads {@code file} and checks (f): {@code survey()} agrees with what {@code read()} emitted: the keys of
     * {@code arrays()} over the MS spectra in first-seen order are {@code spectrumArrays()} as a list, and the ids,
     * indices and counts match.
     */
    private static Run read(Path file) {
        MzMLReader reader = new MzMLReader(file);
        Run run = new Run(reader);
        reader.read(run);
        MzMLReader.Survey survey = reader.survey();
        Set<String> emitted = new LinkedHashSet<>();
        for (Spectrum s : run.spectra) {
            emitted.addAll(s.arrays().keySet());
        }
        assertThat(new ArrayList<>(emitted)).as("keys read() emits").isEqualTo(new ArrayList<>(survey.spectrumArrays()));
        assertThat(survey.spectrumIds()).isEqualTo(ids(run.spectra));
        assertThat(survey.wavelengthSpectrumIds()).isEqualTo(run.wavelengthSpectra.stream()
                .map(WavelengthSpectrum::id).toList());
        assertThat(survey.chromatograms()).isEqualTo(run.chromatograms.size());
        assertThat(run.spectra).extracting(Spectrum::index).isEqualTo(indices(run.spectra.size()));
        assertThat(run.wavelengthSpectra).extracting(WavelengthSpectrum::index)
                .isEqualTo(indices(run.wavelengthSpectra.size()));
        assertThat(run.chromatograms).extracting(Chromatogram::index).isEqualTo(indices(run.chromatograms.size()));
        assertThat(reader.warnings()).startsWith(MzMLReader.LIMITS.toArray(String[]::new));
        return run;
    }

    /** How an error of {@code file} names {@code element}: {@code mzML <file>: <element>: }. */
    private static String prefix(Path file, String element) {
        return "mzML " + file + ": " + element + ": ";
    }

    /** The limit lines every reader notes, then {@code losses}. */
    private static List<String> withLimits(String... losses) {
        List<String> lines = new ArrayList<>(MzMLReader.LIMITS);
        lines.addAll(List.of(losses));
        return lines;
    }

    private static List<Long> indices(int n) {
        return java.util.stream.LongStream.range(0, n).boxed().toList();
    }

    private static List<String> ids(List<Spectrum> spectra) {
        return spectra.stream().map(s -> s.description().id()).toList();
    }

    private static List<Spectrum> spectra(MzPeakReader reader) {
        List<Spectrum> spectra = new ArrayList<>();
        reader.forEach(spectra::add);
        return spectra;
    }

    /** A model of the spectrum descriptions (no arrays, no peaks) and the chromatograms only. */
    private static Model descriptions(List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        List<Spectrum> descriptions = spectra.stream().map(s -> new Spectrum(s.description(), null, null, null))
                .toList();
        return new Model(descriptions, chromatograms, List.of(), null, List.of(), List.of());
    }

    private static Path tinyWith(Path dir, UnaryOperator<String> edit) throws IOException {
        String original = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, dir), StandardCharsets.ISO_8859_1);
        return derived(dir, "tiny-", original, edit, StandardCharsets.ISO_8859_1);
    }

    private static Path tofWith(Path dir, UnaryOperator<String> edit) throws IOException {
        return derived(dir, "tof-", tofSulfas, edit, StandardCharsets.UTF_8);
    }

    /** {@code original} after {@code edit}, which must change it, in a new file in {@code dir}. */
    private static Path derived(Path dir, String prefix, String original, UnaryOperator<String> edit,
                                Charset charset) throws IOException {
        String edited = edit.apply(original);
        assertThat(edited).as("the edit changes the document").isNotEqualTo(original);
        return Files.writeString(Files.createTempFile(dir, prefix, ".mzML"), edited, charset);
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

    /**
     * {@code s} with the first cvParam element after {@code anchor} that contains {@code part} replaced by
     * {@code replacement}.
     */
    private static String replaceCvParam(String s, String anchor, String part, String replacement) {
        int from = s.indexOf(anchor);
        assertThat(from).as("'%s' occurs", anchor).isNotNegative();
        int at = s.indexOf(part, from);
        assertThat(at).as("'%s' occurs after '%s'", part, anchor).isNotNegative();
        int start = s.lastIndexOf("<cvParam ", at);
        int end = s.indexOf("/>", at) + 2;
        return s.substring(0, start) + replacement + s.substring(end);
    }

    /** {@code s} with {@code array} added as the last binaryDataArray of the element at {@code anchor}. */
    private static String withArray(String s, String anchor, String array) {
        return replaceAfter(s, anchor, "</binaryDataArrayList>", array + "</binaryDataArrayList>");
    }

    /** {@code s} without the first binaryDataArray after {@code anchor}. */
    private static String removeFirstArray(String s, String anchor) {
        int from = s.indexOf(anchor);
        int start = s.indexOf("<binaryDataArray ", from);
        int end = s.indexOf("</binaryDataArray>", start) + "</binaryDataArray>".length();
        return s.substring(0, start) + s.substring(end);
    }

    /** {@code s} with scan=19 holding two points, m/z 100 and 200, intensity 1 and 2, and {@code extra}. */
    private static String twoPointSpectrum19(String s, String extra) {
        int start = s.indexOf(SCAN_19);
        int from = s.indexOf("<binaryDataArrayList", start);
        int end = s.indexOf("</binaryDataArrayList>", from) + "</binaryDataArrayList>".length();
        String arrays = "<binaryDataArrayList count=\"3\">\n"
                + array(cv("MS:1000514", "m/z array", "", "MS:1000040"), 100, 200)
                + array(cv("MS:1000515", "intensity array", "", "MS:1000131"), 1, 2)
                + extra + "</binaryDataArrayList>";
        String spectrum = s.substring(start, from).replace("defaultArrayLength=\"15\"", "defaultArrayLength=\"2\"");
        return s.substring(0, start) + spectrum + arrays + s.substring(end);
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

    /**
     * {@code s} with scan=21 holding an m/z and an intensity array, each with the cvParam elements of {@code terms}
     * (named as {@link MzMLTerms#name} names them) before its array type, and {@code payload} as its binary.
     */
    private static String spectrum21With(String s, List<String> terms, byte[] payload) {
        StringBuilder cvParams = new StringBuilder();
        for (String term : terms) {
            cvParams.append(cv(term, MzMLTerms.name(term), "", null)).append('\n');
        }
        int start = s.indexOf(SCAN_21);
        int from = s.indexOf("<binaryDataArrayList", start);
        int end = s.indexOf("</binaryDataArrayList>", from) + "</binaryDataArrayList>".length();
        return s.substring(0, from) + "<binaryDataArrayList count=\"2\">\n"
                + binaryArray(cvParams + cv("MS:1000514", "m/z array", "", "MS:1000040"), payload)
                + binaryArray(cvParams + cv("MS:1000515", "intensity array", "", "MS:1000131"), payload)
                + "</binaryDataArrayList>" + s.substring(end);
    }

    /** {@code dataType}, then {@code compression}. */
    private static List<String> terms(String dataType, List<String> compression) {
        List<String> terms = new ArrayList<>(List.of(dataType));
        terms.addAll(compression);
        return terms;
    }

    /** {@code s} with the region from the first {@code start} to the next {@code end}, inclusive, replaced. */
    private static String replaceRegion(String s, String start, String end, String by) {
        int from = s.indexOf(start);
        assertThat(from).as("'%s' occurs", start).isNotNegative();
        int to = s.indexOf(end, from);
        assertThat(to).as("'%s' occurs after '%s'", end, start).isNotNegative();
        return s.substring(0, from) + by + s.substring(to + end.length());
    }

    /** {@code s} without the region from the first {@code start} to the next {@code end}, inclusive. */
    private static String cut(String s, String start, String end) {
        return replaceRegion(s, start, end, "");
    }

    /** A binaryDataArray of 64-bit floats without compression, with the array type term {@code type}. */
    private static String array(String type, double... values) {
        String base64 = floats(values);
        return "<binaryDataArray encodedLength=\"" + base64.length() + "\">\n" + F64 + "\n" + NO_COMPRESSION + "\n"
                + type + "\n<binary>" + base64 + "</binary>\n</binaryDataArray>\n";
    }

    /** A binaryDataArray with the cvParam elements {@code terms} and {@code binary} as its Base64 text. */
    private static String binaryArray(String terms, byte[] binary) {
        String base64 = Base64.getEncoder().encodeToString(binary);
        return "<binaryDataArray encodedLength=\"" + base64.length() + "\">\n" + terms + "\n<binary>" + base64
                + "</binary>\n</binaryDataArray>\n";
    }

    /** An intensity array of 64-bit floats holding {@code counts} as an MS-Numpress PIC payload (MS:1002313). */
    private static String picArray(double[] counts) {
        byte[] buffer = new byte[5 * counts.length];
        byte[] pic = Arrays.copyOf(buffer, MSNumpress.encodePic(counts, counts.length, buffer));
        return binaryArray(F64 + "\n" + cv("MS:1002313", "MS-Numpress positive integer compression", "", null) + "\n"
                + cv("MS:1000515", "intensity array", "", "MS:1000131"), pic);
    }

    /** 15 counts: 15, 14, ..., 2 and {@code last}. */
    private static double[] picCounts(double last) {
        double[] counts = new double[15];
        for (int i = 0; i < 14; i++) {
            counts[i] = 15 - i;
        }
        counts[14] = last;
        return counts;
    }

    /** {@code values} as an MS-Numpress linear payload at the optimal fixed point. */
    private static byte[] linear(double[] values) {
        byte[] buffer = new byte[8 + 5 * values.length];
        return Arrays.copyOf(buffer, MSNumpress.encodeLinear(values, values.length, buffer,
                MSNumpress.optimalLinearFixedPoint(values, values.length)));
    }

    /** {@code values} as an MS-Numpress SLOF payload at the optimal fixed point. */
    private static byte[] slof(double[] values) {
        byte[] buffer = new byte[8 + 2 * values.length];
        return Arrays.copyOf(buffer, MSNumpress.encodeSlof(values, values.length, buffer,
                MSNumpress.optimalSlofFixedPoint(values, values.length)));
    }

    /** {@code bytes} as a zlib stream. */
    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        return out.toByteArray();
    }

    /**
     * An extra binaryDataArray of {@code n} values 1, 2, ..., n: of the array type {@code key} when it is a CURIE,
     * else a non-standard data array named {@code key}.
     */
    private static String extraArray(String key, int n) {
        return array(key.startsWith("MS:") ? cv(key, CvTerms.ARRAY_TYPES.get(key), "", null)
                : cv("MS:1000786", "non-standard data array", key, null), ramp(n));
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

    private static String longs(long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            buffer.putLong(v);
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
