package org.mzpeak.mzml;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Mode;
import org.mzpeak.mzml.ModelAssert.Model;
import org.mzpeak.mzml.ModelAssert.Result;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mzpeak.mzml.ModelAssert.Mode.MZML_VS_MZPEAK;
import static org.mzpeak.mzml.ModelAssert.Mode.MZPEAK_VS_MZML;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * MZ-13: {@link ModelAssert} on the vendored mzPeak fixtures (mzML design 12.3). Each fixture equals itself; one
 * changed value per rule kind, and in each component added in this round (MZ-4, MZ-5, MZ-7), is reported with its
 * path; every exclusion hides what it names and nothing else, and is stale on a pair without it; mixed mode leaves out
 * the declared counts and peak lists that are a row copy of the arrays; and legacy small.unpacked differs from current
 * small.unpacked exactly by the exclusions it needs and the known fixture differences pinned here. MJ-4 (MZ-13 notes):
 * in mixed mode the mzML side's own peaks and peak arrays are a difference, also where the mzPeak side has no peaks,
 * so the mzML side of a mixed-mode pair here carries none, as {@code MzMLReader}'s models do; A_7 keeps an added 'no
 * combination' next to another combination term, and A_7 and A_16 look past a user param; A_17 hides only a cv list
 * filled with entries of {@code CvEntry.known()}.
 */
class ModelAssertTest {

    /** current small.unpacked: 14 MS1 profile spectra without peaks, 34 MS2 CENTROID ones with row-copy peaks. */
    private static Model small;
    /** current small.chunked: the 14 profile spectra also carry peaks that are not a row copy. */
    private static Model chunked;
    private static Model hasUv;
    private static Model dia;
    private static Model example;

    @BeforeAll
    static void load() {
        small = Model.load(Fixtures.current("small.unpacked.mzpeak"));
        chunked = Model.load(Fixtures.current("small.chunked.mzpeak"));
        hasUv = Model.load(Fixtures.current("has_uv.mzpeak"));
        dia = Model.load(Fixtures.current("diaPASEF.ref.mzpeak"));
        example = Model.load(Fixtures.current("Example_Processed.img.mzpeak"));
    }

    // ---- (a) every fixture equals itself ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak", "Example_Processed.img.mzpeak"})
    void currentFixtureEqualsItselfReadTwice(String name) {
        try (MzPeakReader first = MzPeakReader.open(Fixtures.current(name));
             MzPeakReader second = MzPeakReader.open(Fixtures.current(name))) {
            Result result = ModelAssert.assertEqual(name, Model.of(first), Model.of(second), STRICT);

            assertThat(result.differences()).isEmpty();
            assertThat(result.hidden()).isEmpty();
            assertThat(result.report()).isEqualTo("0 differences");
        }
    }

    @Test
    void modelOfAReaderReadsTheSpectraThroughItsIterator() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            Model model = Model.of(reader);

            assertThat(model.spectra()).isSameAs(reader);
            assertThat(model.spectrumList()).hasSize(48).isUnmodifiable();
            assertThat(model.loaded().spectra()).isInstanceOf(List.class);
            assertThat(model.chromatograms()).hasSize(1);
            assertThat(model.cvList()).extracting(MzPeakManifest.CvEntry::id).containsExactly("MS", "UO");
        }
        assertThat(small.spectrumList()).isSameAs(small.spectra());
        assertThat(example.scanSettings()).hasSize(1);
        assertThat(hasUv.wavelengthSpectra()).hasSize(520);
        assertThat(dia.fileMetadata().run().defaultInstrumentId()).isEqualTo(1);
    }

    // ---- (b) one changed value per rule kind ------------------------------------------------------------------------

    @Test
    void exactRuleReportsAChangedId() {
        Model changed = withSpectrum(small, 3, s -> withDescription(s, d -> with(d, "id", "scan=x")));

        assertOnly(small, changed, "spectrum[3].id",
                "spectrum[3].id: expected \"controllerType=0 controllerNumber=1 scan=4\", got \"scan=x\"");
    }

    @Test
    void doubleRuleReportsAChangedStartTimeAndTakesNaNAsEqualAndZeroSigned() {
        Model changed = withSpectrum(small, 3, s -> withDescription(s,
                d -> with(d, "scans", List.of(with(d.scans().get(0), "startTime", 1.5)))));
        assertOnly(small, changed, "spectrum[3].scans[0].startTime",
                "spectrum[3].scans[0].startTime: expected 0.022838333333, got 1.5");

        Model nan = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "retentionTime", Double.NaN)));
        assertThat(ModelAssert.compare(nan, nan.loaded(), STRICT).equal()).isTrue();
        Model zero = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "retentionTime", 0.0)));
        Model negativeZero = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "retentionTime", -0.0)));
        assertOnly(zero, negativeZero, "spectrum[0].retentionTime",
                "spectrum[0].retentionTime: expected 0.0, got -0.0");
    }

    @Test
    void doubleRuleComparesBoxedDoublesWithNull() {
        Model changed = withSpectrum(small, 2, s -> withDescription(s, d -> with(d, "precursors",
                List.of(with(d.precursors().get(0), "isolationWindow",
                        with(d.precursors().get(0).isolationWindow(), "lowerOffset", null))))));

        assertOnly(small, changed, "spectrum[2].precursors[0].isolationWindow.lowerOffset",
                "spectrum[2].precursors[0].isolationWindow.lowerOffset: expected 1.0, got null");
    }

    @Test
    void doubleArrayRuleReportsTheFirstChangedValueAndItsCount() {
        Model changed = withSpectrum(small, 0, s -> {
            double[] mz = s.mz().clone();
            mz[5] = 1.0;
            mz[7] = 2.0;
            return new Spectrum(s.description(), mz, s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
        });
        assertOnly(small, changed, "spectrum[0].mz",
                "spectrum[0].mz: 2 of 13589 values differ, first [5]: expected " + small.spectrumList().get(0).mz()[5]
                        + ", got 1.0");

        Model shorter = withSpectrum(small, 0, s -> new Spectrum(s.description(),
                Arrays.copyOf(s.mz(), 10), Arrays.copyOf(s.intensity(), 10), s.peaks(), s.arrays(), s.peakArrays()));
        assertThat(ModelAssert.compare(small, shorter, STRICT).differences()).containsExactly(
                "spectrum[0].mz: expected 13589 values, got 10",
                "spectrum[0].intensity: expected 13589 values, got 10");
    }

    @Test
    void doubleArrayRuleTakesNaNAsEqualToNaN() {
        Model nan = withSpectrum(small, 0, s -> {
            double[] intensity = s.intensity().clone();
            intensity[0] = Double.NaN;
            return new Spectrum(s.description(), s.mz(), intensity, s.peaks(), s.arrays(), s.peakArrays());
        });
        Model otherNaN = withSpectrum(small, 0, s -> {
            double[] intensity = s.intensity().clone();
            intensity[0] = Double.longBitsToDouble(0x7ff8000000000001L);
            return new Spectrum(s.description(), s.mz(), intensity, s.peaks(), s.arrays(), s.peakArrays());
        });

        assertThat(ModelAssert.compare(nan, otherNaN, STRICT).equal()).isTrue();
    }

    @Test
    void paramsRuleIsAMultisetOfAccessionValueAndUnit() {
        Model changed = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                replaceParam(d.parameters(), "MS:1000504", p -> new Param(p.name(), p.accession(), 1.0, p.unit())))));
        assertOnly(small, changed, "spectrum[0].parameters[MS:1000504]",
                "spectrum[0].parameters[MS:1000504]: expected [MS:1000504=810.4154747204038 (Double) unit MS:1000040],"
                        + " got [MS:1000504=1.0 (Double) unit MS:1000040]");

        Model unit = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                replaceParam(d.parameters(), "MS:1000504", p -> new Param(p.name(), p.accession(), p.value(), null)))));
        assertOnly(small, unit, "spectrum[0].parameters[MS:1000504]",
                "spectrum[0].parameters[MS:1000504]: expected [MS:1000504=810.4154747204038 (Double) unit MS:1000040],"
                        + " got [MS:1000504=810.4154747204038 (Double)]");

        Model typed = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "scans",
                List.of(with(d.scans().get(0), "parameters", List.of(new Param("mass resolving power", "MS:1000800",
                        "100000", null)))))));
        assertOnly(small, typed, "spectrum[0].scans[0].parameters[MS:1000800]",
                "spectrum[0].scans[0].parameters[MS:1000800]: expected [MS:1000800=100000 (Long)],"
                        + " got [MS:1000800=\"100000\" (String)]");
    }

    @Test
    void paramsRuleIgnoresOrderAndTheNameNextToAnAccessionAndCountsDuplicates() {
        Model reordered = withSpectrum(small, 0, s -> withDescription(s, d -> {
            List<Param> params = new ArrayList<>(d.parameters());
            Collections.reverse(params);
            params.set(params.size() - 1, new Param("MS1 spectrum", "MS:1000579", null, null));
            return with(d, "parameters", params);
        }));
        for (Mode mode : Mode.values()) {
            // the mzML side of a mixed mode has no peaks (design 5.4)
            Model expected = mode == MZML_VS_MZPEAK ? withoutPeaks(small) : small;
            Model actual = mode == MZPEAK_VS_MZML ? withoutPeaks(reordered) : reordered;
            assertThat(ModelAssert.compare(expected, actual, mode).equal()).as("%s", mode).isTrue();
        }

        Model duplicated = withSpectrum(small, 0, s -> withDescription(s, d -> {
            List<Param> params = new ArrayList<>(d.parameters());
            params.add(d.parameters().get(0));
            return with(d, "parameters", params);
        }));
        assertOnly(small, duplicated, "spectrum[0].parameters[MS:1000579]",
                "spectrum[0].parameters[MS:1000579]: expected [MS:1000579], got [MS:1000579, MS:1000579]");

        Model userParam = withSpectrum(small, 2, s -> withDescription(s, d -> with(d, "precursors",
                List.of(with(d.precursors().get(0), "isolationWindow", with(d.precursors().get(0).isolationWindow(),
                        "parameters", List.of(new Param("ms level", null, 2L, null))))))));
        assertOnly(small, userParam, "spectrum[2].precursors[0].isolationWindow.parameters[ms level]",
                "spectrum[2].precursors[0].isolationWindow.parameters[ms level]: expected [\"ms level\"=1 (Long)],"
                        + " got [\"ms level\"=2 (Long)]");
    }

    @Test
    void listRuleReportsASizeOnceAndElementsPairwise() {
        Model noScans = withSpectrum(small, 3, s -> withDescription(s, d -> with(d, "scans", List.of())));
        assertOnly(small, noScans, "spectrum[3].scans.size", "spectrum[3].scans.size: expected 1 elements, got 0");

        Model window = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "scans",
                List.of(with(d.scans().get(0), "scanWindows",
                        List.of(with(d.scans().get(0).scanWindows().get(0), "upperLimit", 1999.0)))))));
        assertOnly(small, window, "spectrum[0].scans[0].scanWindows[0].upperLimit",
                "spectrum[0].scans[0].scanWindows[0].upperLimit: expected 2000.0, got 1999.0");
    }

    @Test
    void recordRuleReportsANullRecordAndRecursesIntoComponents() {
        Precursor p = small.spectrumList().get(2).description().precursors().get(0);
        Model noWindow = withSpectrum(small, 2, s -> withDescription(s, d -> with(d, "precursors",
                List.of(with(p, "isolationWindow", null)))));
        Result result = ModelAssert.compare(small, noWindow, STRICT);
        assertThat(result.paths()).containsExactly("spectrum[2].precursors[0].isolationWindow");
        assertThat(result.differences().get(0)).startsWith(
                "spectrum[2].precursors[0].isolationWindow: expected IsolationWindow[targetMz=").endsWith(", got null");

        Model activation = withSpectrum(small, 2, s -> withDescription(s, d -> with(d, "precursors",
                List.of(with(p, "activation", new Activation(List.of()))))));
        assertThat(ModelAssert.compare(small, activation, STRICT).paths()).containsExactly(
                "spectrum[2].precursors[0].activation.parameters[MS:1000133]",
                "spectrum[2].precursors[0].activation.parameters[MS:1000045]");
    }

    @Test
    void mapOfArraysRuleTakesTheKeysInAnyOrderAndEachArrayBitExact() {
        Spectrum s0 = dia.spectrumList().get(0);
        double[] im = s0.arrays().get("MS:1003006");
        Map<String, double[]> changed = new LinkedHashMap<>();
        double[] imChanged = im.clone();
        imChanged[3] = -1.0;
        changed.put("MS:1003006", imChanged);
        Model changedModel = withSpectrum(dia, 0, s -> new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(),
                changed, s.peakArrays()));
        assertOnly(dia, changedModel, "spectrum[0].arrays[MS:1003006]",
                "spectrum[0].arrays[MS:1003006]: 1 of 205921 values differ, first [3]: expected " + im[3]
                        + ", got -1.0");

        Map<String, double[]> two = new LinkedHashMap<>();
        two.put("MS:1003006", im);
        two.put("extra", im);
        Map<String, double[]> reversed = new LinkedHashMap<>();
        reversed.put("extra", im.clone());
        reversed.put("MS:1003006", im.clone());
        Model inOrder = withSpectrum(dia, 0, s -> new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(),
                two, s.peakArrays()));
        Model inReverse = withSpectrum(dia, 0, s -> new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(),
                reversed, s.peakArrays()));
        assertThat(ModelAssert.compare(inOrder, inReverse, STRICT).equal()).isTrue();
        assertOnly(dia, inOrder, "spectrum[0].arrays[extra]",
                "spectrum[0].arrays[extra]: expected null, got [205921 values]");
        assertOnly(inOrder, dia, "spectrum[0].arrays[extra]",
                "spectrum[0].arrays[extra]: expected [205921 values], got null");
    }

    @Test
    void theOtherSpectrumComponentsAreCompared() {
        Spectrum s0 = dia.spectrumList().get(0);
        Model peaks = withSpectrum(dia, 0, s -> {
            List<CentroidPeak> list = new ArrayList<>(s.peaks());
            CentroidPeak p = list.get(4);
            list.set(4, new CentroidPeak(p.mz(), p.intensity() + 1, p.index()));
            return new Spectrum(s.description(), s.mz(), s.intensity(), list, s.arrays(), s.peakArrays());
        });
        assertThat(ModelAssert.compare(dia, peaks, STRICT).paths()).containsExactly("spectrum[0].peaks[4].intensity");

        Map<String, double[]> peakArrays = Map.of("MS:1003006", new double[s0.peaks().size()]);
        Model peakArray = withSpectrum(dia, 0, s -> new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(),
                s.arrays(), peakArrays));
        assertThat(ModelAssert.compare(dia, peakArray, STRICT).paths())
                .containsExactly("spectrum[0].peakArrays[MS:1003006]");

        Model intensity = withSpectrum(dia, 0, s -> {
            double[] values = s.intensity().clone();
            values[0] += 1;
            return new Spectrum(s.description(), s.mz(), values, s.peaks(), s.arrays(), s.peakArrays());
        });
        assertThat(ModelAssert.compare(dia, intensity, STRICT).paths()).containsExactly("spectrum[0].intensity");
    }

    @Test
    void componentsAddedThisRoundAreCompared() {
        // MZ-4: WavelengthSpectrum.parameters
        Model uv = with(hasUv, "wavelengthSpectra", replace(hasUv.wavelengthSpectra(), 7, w -> with(w, "parameters",
                replaceParam(w.parameters(), "MS:1000619", p -> new Param(p.name(), p.accession(), 211.0, p.unit())))));
        assertOnly(hasUv, uv, "wavelengthSpectrum[7].parameters[MS:1000619]",
                "wavelengthSpectrum[7].parameters[MS:1000619]: expected [MS:1000619=210.0 (Double) unit UO:0000018],"
                        + " got [MS:1000619=211.0 (Double) unit UO:0000018]");

        // MZ-5: Chromatogram.polarity, dataProcessingRef, parameters, precursors
        assertOnly(small, withChromatogram(small, c -> with(c, "polarity", Polarity.POSITIVE)),
                "chromatogram[0].polarity", "chromatogram[0].polarity: expected UNKNOWN, got POSITIVE");
        assertOnly(small, withChromatogram(small, c -> with(c, "dataProcessingRef", "dp2")),
                "chromatogram[0].dataProcessingRef", "chromatogram[0].dataProcessingRef: expected null, got \"dp2\"");
        assertOnly(small, withChromatogram(small, c -> with(c, "parameters",
                        List.of(new Param("chromatogram title", "MS:1000796", "TIC", null)))),
                "chromatogram[0].parameters[MS:1000796]",
                "chromatogram[0].parameters[MS:1000796]: expected [], got [MS:1000796=\"TIC\" (String)]");
        Precursor precursor = new Precursor(null, "scan=19", new IsolationWindow(445.3, 0.5, 0.5), List.of(),
                new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null))));
        Model withPrecursor = withChromatogram(small, c -> with(c, "precursors", List.of(precursor)));
        assertOnly(small, withPrecursor, "chromatogram[0].precursors.size",
                "chromatogram[0].precursors.size: expected 0 elements, got 1");
        assertOnly(withPrecursor, withChromatogram(small, c -> with(c, "precursors",
                        List.of(with(precursor, "precursorId", "scan=20")))),
                "chromatogram[0].precursors[0].precursorId",
                "chromatogram[0].precursors[0].precursorId: expected \"scan=19\", got \"scan=20\"");

        // MZ-7: FileDescription.contacts, Contact.name, affiliation, parameters
        FileMetadata.Contact contact = new FileMetadata.Contact("Bob", "Higglesworth University",
                List.of(new Param("contact email", "MS:1000589", "bob@example.org", null)));
        Model withContact = withContacts(example, List.of(contact));
        assertOnly(example, withContact, "fileMetadata.fileDescription.contacts.size",
                "fileMetadata.fileDescription.contacts.size: expected 0 elements, got 1");
        assertOnly(withContact, withContacts(example, List.of(with(contact, "name", "Alice"))),
                "fileMetadata.fileDescription.contacts[0].name",
                "fileMetadata.fileDescription.contacts[0].name: expected \"Bob\", got \"Alice\"");
        assertOnly(withContact, withContacts(example, List.of(with(contact, "affiliation", null))),
                "fileMetadata.fileDescription.contacts[0].affiliation",
                "fileMetadata.fileDescription.contacts[0].affiliation: expected \"Higglesworth University\", got null");
        assertOnly(withContact, withContacts(example, List.of(with(contact, "parameters", List.of()))),
                "fileMetadata.fileDescription.contacts[0].parameters[MS:1000589]",
                "fileMetadata.fileDescription.contacts[0].parameters[MS:1000589]: expected"
                        + " [MS:1000589=\"bob@example.org\" (String)], got []");

        // MZ-7: ScanSettings.sourceFileRefs, targets
        Model refs = withScanSettings(example, s -> with(with(s, "sourceFileRefs", List.of("sf1")), "targets",
                List.of(List.of(new Param("m/z", "MS:1000040", 1000L, null)),
                        List.of(new Param("m/z", "MS:1000040", 1200L, null)))));
        assertOnly(example, refs, "scanSettings[0].sourceFileRefs.size",
                "scanSettings[0].sourceFileRefs.size: expected 0 elements, got 1",
                "scanSettings[0].targets.size: expected 0 elements, got 2");
        assertOnly(refs, withScanSettings(refs, s -> with(s, "sourceFileRefs", List.of("sf2"))),
                "scanSettings[0].sourceFileRefs[0]",
                "scanSettings[0].sourceFileRefs[0]: expected \"sf1\", got \"sf2\"");
        assertOnly(refs, withScanSettings(refs, s -> with(s, "targets", List.of(s.targets().get(0),
                        List.of(new Param("m/z", "MS:1000040", 1201L, null))))),
                "scanSettings[0].targets[1][MS:1000040]",
                "scanSettings[0].targets[1][MS:1000040]: expected [MS:1000040=1200 (Long)],"
                        + " got [MS:1000040=1201 (Long)]");
    }

    @Test
    void theOtherTopLevelPartsAreCompared() {
        Model run = with(dia, "fileMetadata", with(dia.fileMetadata(), "run",
                with(dia.fileMetadata().run(), "defaultSourceFileId", "analysis.tdf_bin")));
        assertOnly(dia, run, "fileMetadata.run.defaultSourceFileId",
                "fileMetadata.run.defaultSourceFileId: expected \"analysis.tdf\", got \"analysis.tdf_bin\"");

        Model cv = with(small, "cvList", replace(small.cvList(), 1, c -> with(c, "version", "2026-01-17")));
        assertOnly(small, cv, "cvList[1].version", "cvList[1].version: expected \"2026-01-16\", got \"2026-01-17\"");

        Model fewer = with(small, "spectra", small.spectrumList().subList(0, 47));
        assertOnly(small, fewer, "spectrum.size", "spectrum.size: expected 48 elements, got 47");
        assertOnly(fewer, small, "spectrum.size", "spectrum.size: expected 47 elements, got 48");
        assertOnly(hasUv, with(hasUv, "chromatograms", hasUv.chromatograms().subList(0, 1)), "chromatogram.size",
                "chromatogram.size: expected 2 elements, got 1");
        assertOnly(hasUv, with(hasUv, "wavelengthSpectra", List.of()), "wavelengthSpectrum.size",
                "wavelengthSpectrum.size: expected 520 elements, got 0");
        assertOnly(example, with(example, "scanSettings", List.of()), "scanSettings.size",
                "scanSettings.size: expected 1 elements, got 0");
        Result empty = ModelAssert.compare(example, with(example, "fileMetadata", FileMetadata.EMPTY), STRICT);
        assertThat(empty.paths()).containsExactly("fileMetadata.fileDescription",
                "fileMetadata.instrumentConfigurations.size", "fileMetadata.dataProcessingMethods.size",
                "fileMetadata.samples.size", "fileMetadata.software.size", "fileMetadata.run");
        assertThat(empty.differences().get(0)).startsWith("fileMetadata.fileDescription: expected FileDescription[")
                .endsWith("..., got null");

        Result noCvList = ModelAssert.compare(small, with(small, "cvList", null), STRICT);
        assertThat(noCvList.paths()).containsExactly("cvList");
        assertThat(noCvList.differences().get(0)).startsWith("cvList: expected [CvEntry[id=MS, ")
                .endsWith("..., got null");
    }

    @Test
    void reportListsAtMostFiftyDifferences() {
        List<Spectrum> spectra = new ArrayList<>();
        for (Spectrum s : small.spectrumList()) {
            spectra.add(withDescription(s, d -> with(d, "msLevel", 7)));
        }
        Result result = ModelAssert.compare(small, with(small, "spectra", spectra), STRICT);

        assertThat(result.differences()).hasSize(48);
        assertThat(result.report().split("\n")).hasSize(1 + 48);
        assertThat(ModelAssert.MAX_LISTED).isEqualTo(50);

        List<Spectrum> twice = new ArrayList<>();
        for (Spectrum s : spectra) {
            twice.add(withDescription(s, d -> with(d, "polarity", Polarity.UNKNOWN)));
        }
        Result many = ModelAssert.compare(small, with(small, "spectra", twice), STRICT);
        String[] lines = many.report().split("\n");
        assertThat(many.differences()).hasSize(96);
        assertThat(lines).hasSize(1 + 50 + 1);
        assertThat(lines[0]).isEqualTo("96 differences");
        assertThat(lines[1]).isEqualTo("  spectrum[0].msLevel: expected 1, got 7");
        assertThat(lines[51]).isEqualTo("  ... and 46 more");
        assertThatThrownBy(() -> ModelAssert.assertEqual("small", small, with(small, "spectra", twice), STRICT))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("small: 96 differences\n  spectrum[0].msLevel: expected 1, got 7");
    }

    @Test
    void longValuesAreCutInTheReport() {
        Model long1 = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "id", "x".repeat(300))));
        String line = ModelAssert.compare(small, long1, STRICT).differences().get(0);

        assertThat(line).endsWith(", got \"" + "x".repeat(199) + "...");
    }

    // ---- exclusions ------------------------------------------------------------------------------------------------

    @Test
    void a7HidesNoCombinationAddedToASpectrumAndIsStaleOnAnIdenticalPair() {
        Param noCombination = new Param("no combination", "MS:1000795", null, null);
        Model added = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), noCombination))));

        Result hidden = ModelAssert.assertEqual("A_7", small, added, STRICT, ModelAssert.A_7);
        assertThat(hidden.hidden()).containsExactly(Map.entry("A_7", 1));
        assertThat(ModelAssert.compare(small, added, STRICT).paths())
                .containsExactly("spectrum[5].parameters[MS:1000795]");

        Result stale = ModelAssert.compare(small, small, STRICT, ModelAssert.A_7);
        assertThat(stale.equal()).isFalse();
        assertThat(stale.stale()).containsExactly("A_7");
        assertThat(stale.report()).isEqualTo("stale exclusion A_7: it hides no difference\n0 differences");
        assertThatThrownBy(() -> ModelAssert.assertEqual("same", small, small, STRICT, ModelAssert.A_7))
                .isInstanceOf(AssertionError.class).hasMessageContaining("stale exclusion A_7");

        // only when added: not when the expected side has it, nor when the actual side changes its value
        Result removed = ModelAssert.compare(added, small, STRICT, ModelAssert.A_7);
        assertThat(removed.paths()).containsExactly("spectrum[5].parameters[MS:1000795]");
        assertThat(removed.stale()).containsExactly("A_7");
        Model valued = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), new Param("no combination", "MS:1000795", "x", null)))));
        assertThat(ModelAssert.compare(added, valued, STRICT, ModelAssert.A_7).paths())
                .containsExactly("spectrum[5].parameters[MS:1000795]");

        Model uv = with(hasUv, "wavelengthSpectra", replace(hasUv.wavelengthSpectra(), 0, w -> with(w, "parameters",
                append(w.parameters(), noCombination))));
        assertThat(ModelAssert.assertEqual("A_7 uv", hasUv, uv, STRICT, ModelAssert.A_7).hidden())
                .containsEntry("A_7", 1);
    }

    @Test
    void a7HidesNothingWhereTheExpectedSideHasAnotherCombinationTerm() {
        Param noCombination = new Param("no combination", "MS:1000795", null, null);
        for (Param other : List.of(new Param("sum of spectra", "MS:1000571", null, null),
                new Param("median of spectra", "MS:1000573", null, null),
                new Param("mean of spectra", "MS:1000575", null, null))) {
            Model combined = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                    append(d.parameters(), other))));
            Model added = withSpectrum(combined, 5, s -> withDescription(s, d -> with(d, "parameters",
                    append(d.parameters(), noCombination))));
            Result kept = ModelAssert.compare(combined, added, STRICT, ModelAssert.A_7);
            assertThat(kept.paths()).as("%s", other.accession()).containsExactly("spectrum[5].parameters[MS:1000795]");
            assertThat(kept.stale()).containsExactly("A_7");

            Model uvCombined = with(hasUv, "wavelengthSpectra", replace(hasUv.wavelengthSpectra(), 0,
                    w -> with(w, "parameters", append(w.parameters(), other))));
            Model uvAdded = with(uvCombined, "wavelengthSpectra", replace(uvCombined.wavelengthSpectra(), 0,
                    w -> with(w, "parameters", append(w.parameters(), noCombination))));
            assertThat(ModelAssert.compare(uvCombined, uvAdded, STRICT, ModelAssert.A_7).paths())
                    .containsExactly("wavelengthSpectrum[0].parameters[MS:1000795]");
        }
    }

    @Test
    void a7HidesNoCombinationAddedNextToAUserParam() {
        // a user param has no accession (tiny's scan=21 carries one, TOFsulfas 213); it is no combination term
        Param note = new Param("note", null, "x", null);
        Param noCombination = new Param("no combination", "MS:1000795", null, null);
        Model noted = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), note))));
        Model added = withSpectrum(noted, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), noCombination))));
        assertThat(ModelAssert.compare(noted, added, STRICT).paths())
                .containsExactly("spectrum[5].parameters[MS:1000795]");
        assertThat(ModelAssert.assertEqual("A_7 user param", noted, added, STRICT, ModelAssert.A_7).hidden())
                .containsExactly(Map.entry("A_7", 1));

        Model uvNoted = with(hasUv, "wavelengthSpectra", replace(hasUv.wavelengthSpectra(), 0,
                w -> with(w, "parameters", append(w.parameters(), note))));
        Model uvAdded = with(uvNoted, "wavelengthSpectra", replace(uvNoted.wavelengthSpectra(), 0,
                w -> with(w, "parameters", append(w.parameters(), noCombination))));
        assertThat(ModelAssert.compare(uvNoted, uvAdded, STRICT).paths())
                .containsExactly("wavelengthSpectrum[0].parameters[MS:1000795]");
        assertThat(ModelAssert.assertEqual("A_7 uv user param", uvNoted, uvAdded, STRICT, ModelAssert.A_7).hidden())
                .containsExactly(Map.entry("A_7", 1));
    }

    @Test
    void a16HidesTheGainedTypeAndSummariesOnlyWhereTheWriterDerivesThem() {
        Model bare = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters", List.of())));
        Result result = ModelAssert.assertEqual("A_16", bare, small, STRICT, ModelAssert.A_16);
        assertThat(result.hidden()).containsExactly(Map.entry("A_16", 6));

        // a type next to a type the source has, and a summary next to a summary the source has, are differences
        Model typed = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                List.of(new Param("MSn spectrum", "MS:1000580", null, null),
                        d.parameters().get(1)))));
        Result kept = ModelAssert.compare(typed, small, STRICT, ModelAssert.A_16);
        assertThat(kept.paths()).containsExactly("spectrum[0].parameters[MS:1000580]",
                "spectrum[0].parameters[MS:1000579]", "spectrum[0].parameters[MS:1000527]",
                "spectrum[0].parameters[MS:1000504]", "spectrum[0].parameters[MS:1000505]",
                "spectrum[0].parameters[MS:1000285]");
        assertThat(kept.stale()).containsExactly("A_16");

        Model other = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                List.of(new Param("ms level", "MS:1000511", 1L, null)))));
        Model otherPlusUser = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                List.of(new Param("ms level", "MS:1000511", 1L, null), new Param("note", null, "x", null)))));
        assertThat(ModelAssert.compare(other, otherPlusUser, STRICT, ModelAssert.A_16).paths())
                .containsExactly("spectrum[0].parameters[note]");

        // a user param in the expected params has no accession: it is neither a type nor a summary
        Param note = new Param("note", null, "x", null);
        Model noted = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters", List.of(note))));
        Model gained = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), note))));
        assertThat(ModelAssert.assertEqual("A_16 user param", noted, gained, STRICT, ModelAssert.A_16).hidden())
                .containsExactly(Map.entry("A_16", 6));
    }

    @Test
    void a1HidesALostPeakListInStrictMode() {
        Model lost = withSpectra(chunked, s -> s.description().signalContinuity() == SignalContinuity.PROFILE
                ? new Spectrum(with(s.description(), "numberOfPeaks", 0L), s.mz(), s.intensity(), List.of())
                : s);
        Result strict = ModelAssert.compare(chunked, lost, STRICT);
        assertThat(kinds(strict)).containsExactly(Map.entry("spectrum[].numberOfPeaks", 14),
                Map.entry("spectrum[].peaks.size", 14));
        assertThat(ModelAssert.assertEqual("A_1", chunked, lost, STRICT, ModelAssert.A_1).hidden())
                .containsExactly(Map.entry("A_1", 28));

        // a CENTROID peak list that differs from its arrays comes back as a row copy of the arrays
        Model differing = withSpectrum(small, 2, ModelAssertTest::oneChangedPeakIntensity);
        assertThat(ModelAssert.compare(differing, small, STRICT).paths())
                .containsExactly("spectrum[2].peaks[0].intensity");
        assertThat(ModelAssert.assertEqual("A_1 centroid", differing, small, STRICT, ModelAssert.A_1).hidden())
                .containsExactly(Map.entry("A_1", 1));

        // two peak lists that are both not a row copy are compared
        Model changedProfilePeaks = withSpectrum(chunked, 0, ModelAssertTest::oneChangedPeakIntensity);
        Result both = ModelAssert.compare(chunked, changedProfilePeaks, STRICT, ModelAssert.A_1);
        assertThat(both.paths()).containsExactly("spectrum[0].peaks[0].intensity");
        assertThat(both.stale()).containsExactly("A_1");
    }

    @Test
    void a9HidesARetentionTimeReplacedByTheFirstScanStartTime() {
        Model legacy = Model.load(Fixtures.legacy("small.unpacked.mzpeak"));
        Model scanTime = withSpectra(legacy, s -> withDescription(s,
                d -> with(d, "retentionTime", d.scans().get(0).startTime())));

        assertThat(ModelAssert.compare(legacy, scanTime, STRICT).paths()).hasSize(48)
                .allMatch(p -> p.matches("spectrum\\[\\d+]\\.retentionTime"));
        assertThat(ModelAssert.assertEqual("A_9", legacy, scanTime, STRICT, ModelAssert.A_9).hidden())
                .containsExactly(Map.entry("A_9", 48));

        Model otherTime = withSpectrum(legacy, 0, s -> withDescription(s, d -> with(d, "retentionTime", 9.0)));
        assertThat(ModelAssert.compare(legacy, otherTime, STRICT, ModelAssert.A_9).paths())
                .containsExactly("spectrum[0].retentionTime");
        Model noScans = withSpectrum(legacy, 0, s -> withDescription(s, d -> with(d, "scans", List.of())));
        assertThat(ModelAssert.compare(noScans, withSpectrum(noScans, 0, s -> withDescription(s,
                d -> with(d, "retentionTime", 9.0))), STRICT, ModelAssert.A_9).paths())
                .containsExactly("spectrum[0].retentionTime");
    }

    @Test
    void a2A8A18HideTheRunFieldsDiaPasefChanges() {
        FileMetadata.MsRun run = dia.fileMetadata().run();
        assertThat(run.id()).isEqualTo("test/data/diaPASEF.d");
        assertThat(run.defaultDataProcessingId()).isNull();
        assertThat(run.defaultInstrumentId()).isEqualTo(1);
        assertThat(dia.fileMetadata().instrumentConfigurations()).extracting(FileMetadata.InstrumentConfiguration::id)
                .containsExactly(0);
        assertThat(dia.fileMetadata().dataProcessingMethods()).extracting(FileMetadata.DataProcessing::id)
                .containsExactly("mzpeak_conversion1");
        Model written = withRun(dia, r -> with(with(with(r, "id", "test_x002f_data_x002f_diaPASEF.d"),
                "defaultDataProcessingId", "mzpeak_conversion1"), "defaultInstrumentId", 0));

        assertThat(ModelAssert.compare(dia, written, STRICT).paths()).containsExactly("fileMetadata.run.id",
                "fileMetadata.run.defaultInstrumentId", "fileMetadata.run.defaultDataProcessingId");
        assertThat(ModelAssert.assertEqual("diaPASEF", dia, written, STRICT, ModelAssert.A_2, ModelAssert.A_8,
                ModelAssert.A_18).hidden()).containsExactly(Map.entry("A_2", 1), Map.entry("A_8", 1),
                Map.entry("A_18", 1));

        Model wrong = withRun(dia, r -> with(with(with(r, "id", "test_data_diaPASEF.d"),
                "defaultDataProcessingId", "other"), "defaultInstrumentId", 2));
        Result notHidden = ModelAssert.compare(dia, wrong, STRICT, ModelAssert.A_2, ModelAssert.A_8,
                ModelAssert.A_18);
        assertThat(notHidden.paths()).hasSize(3);
        assertThat(notHidden.stale()).containsExactly("A_2", "A_8", "A_18");

        // an id that is an NCName, a default that is set, and a default naming an existing configuration (1 of 0, 1)
        // stay differences, although actual has the first configuration and data processing
        FileMetadata twoIcs = with(written.fileMetadata(), "instrumentConfigurations",
                List.of(dia.fileMetadata().instrumentConfigurations().get(0),
                        with(dia.fileMetadata().instrumentConfigurations().get(0), "id", 1)));
        Model valid = withRun(with(written, "fileMetadata", twoIcs),
                r -> with(with(r, "defaultDataProcessingId", "x"), "defaultInstrumentId", 1));
        Model validChanged = withRun(with(written, "fileMetadata", twoIcs), r -> with(r, "id", "a_x002f_b"));
        Result kept = ModelAssert.compare(valid, validChanged, STRICT, ModelAssert.A_2, ModelAssert.A_8,
                ModelAssert.A_18);
        assertThat(kept.paths()).containsExactly("fileMetadata.run.id", "fileMetadata.run.defaultInstrumentId",
                "fileMetadata.run.defaultDataProcessingId");
        assertThat(kept.stale()).containsExactly("A_2", "A_8", "A_18");
    }

    /**
     * A_26 hides a run start time exactly as jmzML's CalendarAdapter rewrites it in the default time zone (JZ-7): under
     * UTC a time without zone gains Z and a fraction is cut to milliseconds; another change, another zone, no expected
     * time, text that is no xs:dateTime and another path are not hidden.
     */
    @Test
    void a26HidesARunStartTimeAsJmzmlRewritesItInTheDefaultTimeZone() {
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (List<String> rewritten : List.of(List.of("2007-06-27T15:23:45.00035", "2007-06-27T15:23:45Z"),
                    List.of("2005-07-20T19:44:22.1234Z", "2005-07-20T19:44:22.123Z"))) {
                Model expected = withRun(dia, r -> with(r, "startTime", rewritten.get(0)));
                Model actual = withRun(dia, r -> with(r, "startTime", rewritten.get(1)));
                assertThat(ModelAssert.compare(expected, actual, STRICT).paths())
                        .containsExactly("fileMetadata.run.startTime");
                assertThat(ModelAssert.assertEqual("A_26 " + rewritten, expected, actual, STRICT, ModelAssert.A_26)
                        .hidden()).containsExactly(Map.entry("A_26", 1));
            }

            for (List<String> kept : Arrays.asList(Arrays.asList("2005-07-20T19:44:22Z", "2005-07-20T19:44:23Z"),
                    Arrays.asList(null, "2007-06-27T15:23:45Z"), Arrays.asList("no time", "2007-06-27T15:23:45Z"))) {
                Result result = ModelAssert.compare(withRun(dia, r -> with(r, "startTime", kept.get(0))),
                        withRun(dia, r -> with(r, "startTime", kept.get(1))), STRICT, ModelAssert.A_26);
                assertThat(result.paths()).as("%s", kept).containsExactly("fileMetadata.run.startTime");
                assertThat(result.stale()).containsExactly("A_26");
            }
            Result otherPath = ModelAssert.compare(withRun(dia, r -> with(r, "id", "2007-06-27T15:23:45.00035")),
                    withRun(dia, r -> with(r, "id", "2007-06-27T15:23:45Z")), STRICT, ModelAssert.A_26);
            assertThat(otherPath.paths()).containsExactly("fileMetadata.run.id");
            assertThat(otherPath.stale()).containsExactly("A_26");

            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            Result otherZone = ModelAssert.compare(withRun(dia, r -> with(r, "startTime", "2007-06-27T15:23:45.00035")),
                    withRun(dia, r -> with(r, "startTime", "2007-06-27T15:23:45Z")), STRICT, ModelAssert.A_26);
            assertThat(otherZone.paths()).containsExactly("fileMetadata.run.startTime");
            assertThat(otherZone.stale()).containsExactly("A_26");
            assertThat(ModelAssert.assertEqual("A_26 Chicago",
                    withRun(dia, r -> with(r, "startTime", "2007-06-27T15:23:45.00035")),
                    withRun(dia, r -> with(r, "startTime", "2007-06-27T15:23:45-05:00")), STRICT, ModelAssert.A_26)
                    .hidden()).containsExactly(Map.entry("A_26", 1));
        } finally {
            TimeZone.setDefault(zone);
        }
    }

    @Test
    void a17HidesOnlyAFilledEmptyCvList() {
        Model empty = with(small, "cvList", List.of());
        assertThat(ModelAssert.assertEqual("A_17", empty, small, STRICT, ModelAssert.A_17).hidden())
                .containsExactly(Map.entry("A_17", 1));

        Model one = with(small, "cvList", small.cvList().subList(0, 1));
        Result kept = ModelAssert.compare(one, small, STRICT, ModelAssert.A_17);
        assertThat(kept.paths()).containsExactly("cvList.size");
        assertThat(kept.stale()).containsExactly("A_17");
    }

    @Test
    void a17HidesOnlyAListFilledWithKnownEntries() {
        assertThat(MzPeakManifest.CvEntry.known()).containsAll(small.cvList());
        Model empty = with(small, "cvList", List.of());
        List<MzPeakManifest.CvEntry> known = MzPeakManifest.CvEntry.known();
        Model ims = with(small, "cvList", List.of(known.get(0), known.get(1), known.get(known.size() - 1)));
        assertThat(ims.cvList()).extracting(MzPeakManifest.CvEntry::id).containsExactly("MS", "UO", "IMS");
        assertThat(ModelAssert.assertEqual("A_17 IMS", empty, ims, STRICT, ModelAssert.A_17).hidden())
                .containsExactly(Map.entry("A_17", 1));

        Model otherVersion = with(small, "cvList", replace(small.cvList(), 1, c -> with(c, "version", "2026-01-17")));
        Model unknownCv = with(small, "cvList", append(small.cvList(),
                new MzPeakManifest.CvEntry("XX", "Unknown", "http://example.org/xx.obo", "1")));
        for (Model filled : List.of(otherVersion, unknownCv)) {
            Result kept = ModelAssert.compare(empty, filled, STRICT, ModelAssert.A_17);
            assertThat(kept.paths()).containsExactly("cvList.size");
            assertThat(kept.stale()).containsExactly("A_17");
        }
    }

    @Test
    void b22HidesArraysThatAreEqualAsMzSortedTuples() {
        Model scanOrder = withSpectrum(dia, 0, s -> reversed(s, false));
        Result strict = ModelAssert.compare(scanOrder, dia, STRICT);
        assertThat(strict.paths()).containsExactly("spectrum[0].mz", "spectrum[0].intensity",
                "spectrum[0].arrays[MS:1003006]");
        assertThat(ModelAssert.assertEqual("B_22", scanOrder, dia, STRICT, ModelAssert.B_22).hidden())
                .containsExactly(Map.entry("B_22", 3));

        Model changed = withSpectrum(dia, 0, s -> reversed(s, true));
        Result kept = ModelAssert.compare(changed, dia, STRICT, ModelAssert.B_22);
        assertThat(kept.paths()).containsExactly("spectrum[0].mz", "spectrum[0].intensity",
                "spectrum[0].arrays[MS:1003006]");
        assertThat(kept.stale()).containsExactly("B_22");

        Model noArrays = withSpectrum(dia, 0, s -> new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(),
                Map.of(), s.peakArrays()));
        assertThat(ModelAssert.compare(noArrays, dia, STRICT, ModelAssert.B_22).paths())
                .containsExactly("spectrum[0].arrays[MS:1003006]");
    }

    @Test
    void b34HidesMzValuesThatAreNaNInExpected() {
        Model nan = withSpectrum(small, 0, s -> {
            double[] mz = s.mz().clone();
            mz[0] = Double.NaN;
            mz[3] = Double.NaN;
            return new Spectrum(s.description(), mz, s.intensity(), s.peaks());
        });
        // the mzPeak read fills the leading NaN with the first finite m/z (NullFill.interpolateGaps)
        Model filled = withMz(small, 0, m -> m[0] = m[1]);
        assertThat(ModelAssert.assertEqual("B_34", nan, filled, STRICT, ModelAssert.B_34).hidden())
                .containsExactly(Map.entry("B_34", 1));

        Model alsoChanged = withSpectrum(nan, 0, s -> {
            double[] mz = s.mz().clone();
            mz[4] = 1.0;
            return new Spectrum(s.description(), mz, s.intensity(), s.peaks());
        });
        Result kept = ModelAssert.compare(alsoChanged, filled, STRICT, ModelAssert.B_34);
        assertThat(kept.paths()).containsExactly("spectrum[0].mz");
        assertThat(kept.stale()).containsExactly("B_34");
        assertThat(ModelAssert.compare(nan, filled, STRICT, ModelAssert.B_34).equal()).isTrue();
        Model shorter = withSpectrum(nan, 0, s -> new Spectrum(s.description(), Arrays.copyOf(s.mz(), 5),
                Arrays.copyOf(s.intensity(), 5), s.peaks()));
        assertThat(ModelAssert.compare(nan, shorter, STRICT, ModelAssert.B_34).paths())
                .containsExactly("spectrum[0].mz", "spectrum[0].intensity");
    }

    // ---- negative controls: each exclusion hides only what design 6.1 and 6.2 define ------------------------------

    @Test
    void a7HidesOnlyOneBareNoCombinationOnASpectrumWithAtMostOneScan() {
        Param noCombination = new Param("no combination", "MS:1000795", null, null);
        assertThat(small.spectrumList().get(5).description().scans()).hasSize(1);
        Model twice = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(append(d.parameters(), noCombination), noCombination))));
        Model withUnit = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), new Param("no combination", "MS:1000795", null, "UO:0000186")))));
        Model withValue = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), new Param("no combination", "MS:1000795", "x", null)))));
        for (Model added : List.of(twice, withUnit, withValue)) {
            Result kept = ModelAssert.compare(small, added, STRICT, ModelAssert.A_7);
            assertThat(kept.paths()).containsExactly("spectrum[5].parameters[MS:1000795]");
            assertThat(kept.stale()).containsExactly("A_7");
        }

        // design 5.5 writes the term for a spectrum of at most one scan, none for two or more
        Model noScans = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "scans", List.of())));
        Model twoScans = withSpectrum(small, 5, s -> withDescription(s, d -> with(d, "scans",
                List.of(d.scans().get(0), d.scans().get(0)))));
        assertThat(ModelAssert.assertEqual("A_7 no scan", noScans, withSpectrum(noScans, 5, s -> withDescription(s,
                d -> with(d, "parameters", append(d.parameters(), noCombination)))), STRICT, ModelAssert.A_7)
                .hidden()).containsExactly(Map.entry("A_7", 1));
        Result two = ModelAssert.compare(twoScans, withSpectrum(twoScans, 5, s -> withDescription(s,
                d -> with(d, "parameters", append(d.parameters(), noCombination)))), STRICT, ModelAssert.A_7);
        assertThat(two.paths()).containsExactly("spectrum[5].parameters[MS:1000795]");
        assertThat(two.stale()).containsExactly("A_7");
    }

    @Test
    void a16HidesADerivedTypeOnlyForItsMsLevelAndASummaryOnlyOnceWithAFiniteNumber() {
        int msn = 0;
        while (small.spectrumList().get(msn).description().msLevel() < 2) {
            msn++;
        }
        int ms2 = msn;
        assertThat(small.spectrumList().get(0).description().msLevel()).isEqualTo(1);
        assertThat(Param.contains(small.spectrumList().get(ms2).description().parameters(), "MS:1000580")).isTrue();
        Model bare = withSpectra(small, s -> withDescription(s, d -> with(d, "parameters", List.of())));
        assertThat(ModelAssert.assertEqual("A_16 MS1 and MSn", withSpectrum(small, ms2, s -> bare.spectrumList()
                .get(ms2)), small, STRICT, ModelAssert.A_16).hidden()).containsExactly(Map.entry("A_16", 6));

        // an MS1 type on an MSn spectrum and an MSn type on an MS1 spectrum
        Model ms1OnMsn = withSpectrum(small, ms2, s -> withDescription(s, d -> with(d, "parameters",
                replaceParam(d.parameters(), "MS:1000580", p -> new Param("MS1 spectrum", "MS:1000579", null, null)))));
        Model msnOnMs1 = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                replaceParam(d.parameters(), "MS:1000579", p -> new Param("MSn spectrum", "MS:1000580", null, null)))));
        Result ms1 = ModelAssert.compare(bare, ms1OnMsn, STRICT, ModelAssert.A_16);
        assertThat(ms1.paths()).contains("spectrum[" + ms2 + "].parameters[MS:1000579]")
                .noneMatch(path -> path.startsWith("spectrum[0]."));
        Result msn1 = ModelAssert.compare(bare, msnOnMs1, STRICT, ModelAssert.A_16);
        assertThat(msn1.paths()).contains("spectrum[0].parameters[MS:1000580]")
                .noneMatch(path -> path.startsWith("spectrum[" + ms2 + "]."));

        // a summary that is not a finite number, or that is there twice
        Model bare0 = withSpectrum(small, 0, s -> bare.spectrumList().get(0));
        Param tic = Param.find(small.spectrumList().get(0).description().parameters(), "MS:1000285").orElseThrow();
        assertThat(tic.value()).isInstanceOf(Number.class);
        for (Object value : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, "123", null)) {
            Model changed = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                    replaceParam(d.parameters(), "MS:1000285", p -> with(p, "value", value)))));
            Result kept = ModelAssert.compare(bare0, changed, STRICT, ModelAssert.A_16);
            assertThat(kept.paths()).as("%s", value).containsExactly("spectrum[0].parameters[MS:1000285]");
            assertThat(kept.hidden()).containsExactly(Map.entry("A_16", 5));
        }
        Model twice = withSpectrum(small, 0, s -> withDescription(s, d -> with(d, "parameters",
                append(d.parameters(), tic))));
        assertThat(ModelAssert.compare(bare0, twice, STRICT, ModelAssert.A_16).paths())
                .containsExactly("spectrum[0].parameters[MS:1000285]");
    }

    @Test
    void a17HidesOnlyDistinctKnownEntriesInTheirOrderWithMsAndUo() {
        Model empty = with(small, "cvList", List.of());
        List<MzPeakManifest.CvEntry> known = MzPeakManifest.CvEntry.known();
        List<List<MzPeakManifest.CvEntry>> notFilledByTheWriter = List.of(
                List.of(known.get(0)),
                List.of(known.get(1), known.get(0)),
                List.of(known.get(0), known.get(1), known.get(1)),
                List.of(known.get(0), known.get(9)),
                Arrays.asList(known.get(0), known.get(1), null));
        for (List<MzPeakManifest.CvEntry> filled : notFilledByTheWriter) {
            Result kept = ModelAssert.compare(empty, with(small, "cvList", filled), STRICT, ModelAssert.A_17);
            assertThat(kept.paths()).as("%s", filled).containsExactly("cvList.size");
            assertThat(kept.stale()).containsExactly("A_17");
        }
    }

    @Test
    void b22HidesNothingWhereTheActualMzIsNotSorted() {
        double[] mz = dia.spectrumList().get(0).mz();
        int i = 0;
        while (!(mz[i] < mz[i + 1])) {
            i++;
        }
        int at = i;
        Model unsorted = withSpectrum(dia, 0, s -> swapped(s, at));

        Result kept = ModelAssert.compare(dia, unsorted, STRICT, ModelAssert.B_22);
        assertThat(kept.paths()).containsExactly("spectrum[0].mz", "spectrum[0].intensity",
                "spectrum[0].arrays[MS:1003006]");
        assertThat(kept.stale()).containsExactly("B_22");
    }

    @Test
    void b34HidesOnlyAFiniteValueBetweenTheNearestFiniteNeighbours() {
        double[] mz = small.spectrumList().get(0).mz();
        int last = mz.length - 1;
        Model nan = withMz(small, 0, m -> {
            m[0] = Double.NaN;
            m[3] = Double.NaN;
            m[last] = Double.NaN;
        });
        // a leading or trailing NaN takes the nearest finite m/z, as NullFill.interpolateGaps fills it
        Consumer<double[]> carried = m -> {
            m[0] = mz[1];
            m[last] = mz[last - 1];
        };
        // inside the neighbours, inclusive
        for (double v : new double[] {mz[2], (mz[2] + mz[4]) / 2, mz[4]}) {
            Model filled = withMz(small, 0, carried.andThen(m -> m[3] = v));
            assertThat(ModelAssert.assertEqual("B_34 " + v, nan, filled, STRICT, ModelAssert.B_34).hidden())
                    .containsExactly(Map.entry("B_34", 1));
        }

        // 0, Infinity, NaN, or a value outside the neighbours where expected has NaN; before the first finite m/z
        // or after the last one, any value but that m/z, on either side of it
        List<Consumer<double[]>> wrong = List.of(m -> m[3] = 0.0,
                m -> m[3] = Math.nextUp(mz[4]),
                m -> m[0] = Double.POSITIVE_INFINITY,
                m -> m[0] = Math.nextUp(mz[1]),
                m -> m[0] = Math.nextDown(mz[1]),
                m -> m[0] = -999.0,
                m -> m[last] = Math.nextDown(mz[last - 1]),
                m -> m[last] = Math.nextUp(mz[last - 1]),
                m -> m[last] = mz[last - 1] + 1000.0,
                m -> m[3] = Double.NaN);
        for (Consumer<double[]> change : wrong) {
            Result kept = ModelAssert.compare(nan, withMz(small, 0, carried.andThen(change)), STRICT,
                    ModelAssert.B_34);
            assertThat(kept.paths()).containsExactly("spectrum[0].mz");
            assertThat(kept.stale()).containsExactly("B_34");
        }

        // an all-NaN m/z array stays NaN
        Model allNaN = withMz(small, 0, m -> Arrays.fill(m, Double.NaN));
        Result kept = ModelAssert.compare(allNaN, small, STRICT, ModelAssert.B_34);
        assertThat(kept.paths()).containsExactly("spectrum[0].mz");
        assertThat(kept.stale()).containsExactly("B_34");
    }

    @Test
    void exclusionsHaveTheirDesignIdsAndCannotBePassedTwice() {
        assertThat(ModelAssert.EXCLUSIONS).extracting(Exclusion::id).containsExactly("A_1", "A_2", "A_7", "A_8",
                "A_9", "A_16", "A_17", "A_18", "A_26", "B_22", "B_34");
        assertThat(ModelAssert.EXCLUSIONS).allSatisfy(e -> {
            assertThat(e.description()).startsWith(e.id().replace('_', '-') + ": ");
            assertThat(e).hasToString(e.id());
        });
        assertThatThrownBy(() -> ModelAssert.compare(small, small, STRICT, ModelAssert.A_7, ModelAssert.A_7))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("exclusion A_7 passed twice");
    }

    @Test
    void anExclusionCountsEveryDifferenceItHidesAndTwoMayHideTheSame() {
        Exclusion anyId = new Exclusion("X_1", "every id", d -> d.path().endsWith(".id"));
        Exclusion spectrumThree = new Exclusion("X_2", "spectrum 3", d -> d.path().startsWith("spectrum[3]"));
        Model changed = withSpectra(small, s -> withDescription(s, d -> with(d, "id", "x" + d.index())));

        Result result = ModelAssert.compare(small, changed, STRICT, anyId, spectrumThree);

        assertThat(result.equal()).isTrue();
        assertThat(result.hidden()).containsExactly(Map.entry("X_1", 48), Map.entry("X_2", 1));
    }

    // ---- (e) mixed mode ((d) is a7HidesNoCombinationAddedToASpectrumAndIsStaleOnAnIdenticalPair) ----------------

    @Test
    void mixedModeLeavesOutDeclaredCountsAndRowCopyPeaks() {
        Model mzml = withSpectra(small, s -> s.description().signalContinuity() == SignalContinuity.CENTROID
                ? new Spectrum(with(s.description(), "numberOfPeaks", 0L), s.mz(), s.intensity(), List.of())
                : s);

        assertThat(ModelAssert.assertEqual("mixed", mzml, small, MZML_VS_MZPEAK).differences()).isEmpty();
        assertThat(ModelAssert.assertEqual("mixed", small, mzml, MZPEAK_VS_MZML).differences()).isEmpty();
        Result strict = ModelAssert.compare(mzml, small, STRICT);
        assertThat(kinds(strict)).containsExactly(Map.entry("spectrum[].numberOfPeaks", 34),
                Map.entry("spectrum[].peaks.size", 34));

        Model points = withSpectrum(mzml, 0, s -> withDescription(s, d -> with(d, "numberOfDataPoints", 1L)));
        assertThat(ModelAssert.compare(points, small, MZML_VS_MZPEAK).equal()).isTrue();
        assertThat(ModelAssert.compare(points, mzml, STRICT).paths())
                .containsExactly("spectrum[0].numberOfDataPoints");
    }

    @Test
    void mixedModeReportsPeaksOnTheMzMLSideWhenTheMzPeakSideHasNone() {
        Model mzpeak = withSpectra(small, s -> new Spectrum(s.description(), s.mz(), s.intensity(), List.of()));

        Result mzmlWithPeaks = ModelAssert.compare(small, mzpeak, MZML_VS_MZPEAK);

        assertThat(kinds(mzmlWithPeaks)).containsExactly(Map.entry("spectrum[].peaks", 34));
        assertThat(mzmlWithPeaks.differences()).allMatch(d -> d.matches(
                "spectrum\\[\\d+]\\.peaks: the mzML side has \\d+ peaks: MzMLReader builds none \\(design 5\\.4\\)"));
        assertThat(ModelAssert.compare(small, mzpeak, MZPEAK_VS_MZML).equal()).isTrue();
    }

    @Test
    void mixedModeReportsPeaksThatAreNotARowCopyUnlessA1IsPassed() {
        Model mzml = withoutPeaks(chunked);

        Result mixed = ModelAssert.compare(mzml, chunked, MZML_VS_MZPEAK);
        assertThat(mixed.differences()).hasSize(14).allMatch(d -> d.matches(
                "spectrum\\[\\d+]\\.peaks: the mzPeak side's \\d+ peaks are not a row copy of its arrays"
                        + " \\(design 5\\.4\\)"));
        assertThat(mixed.differences().get(0))
                .isEqualTo("spectrum[0].peaks: the mzPeak side's 1612 peaks are not a row copy of its arrays"
                        + " (design 5.4)");
        assertThat(ModelAssert.assertEqual("A_1", mzml, chunked, MZML_VS_MZPEAK, ModelAssert.A_1).hidden())
                .containsExactly(Map.entry("A_1", 14));
        assertThat(ModelAssert.compare(chunked, mzml, MZPEAK_VS_MZML).differences()).hasSize(14);
    }

    @Test
    void mixedModeReportsACentroidPeakListThatDiffersFromItsArrays() {
        Model mzpeak = withSpectrum(small, 2, ModelAssertTest::oneChangedPeakIntensity);
        Model mzml = withoutPeaks(small);

        Result mixed = ModelAssert.compare(mzml, mzpeak, MZML_VS_MZPEAK);
        assertThat(mixed.differences()).containsExactly(
                "spectrum[2].peaks: the mzPeak side's 485 peaks are not a row copy of its arrays (design 5.4)");
        assertThat(ModelAssert.assertEqual("A_1", mzml, mzpeak, MZML_VS_MZPEAK, ModelAssert.A_1).hidden())
                .containsExactly(Map.entry("A_1", 1));
        assertThat(ModelAssert.compare(mzpeak, mzml, MZPEAK_VS_MZML).paths()).containsExactly("spectrum[2].peaks");
    }

    @Test
    void mixedModeReportsPeaksAndPeakArraysOnTheMzMLSide() {
        Spectrum s0 = dia.spectrumList().get(0);
        assertThat(ModelAssert.isRowCopy(s0)).isTrue();
        assertThat(s0.peakArrays()).containsOnlyKeys("MS:1003006");

        for (Mode mode : List.of(MZML_VS_MZPEAK, MZPEAK_VS_MZML)) {
            Result result = ModelAssert.compare(dia, dia, mode);
            assertThat(kinds(result)).as("%s", mode).containsExactly(Map.entry("spectrum[].peakArrays", 9),
                    Map.entry("spectrum[].peaks", 9));
            assertThat(result.differences()).as("%s", mode).startsWith(
                    "spectrum[0].peaks: the mzML side has " + s0.peaks().size()
                            + " peaks: MzMLReader builds none (design 5.4)",
                    "spectrum[0].peakArrays: the mzML side has peak arrays [MS:1003006]: MzMLReader builds none"
                            + " (design 5.4)");
        }
        assertThat(ModelAssert.compare(withoutPeaks(dia), dia, MZML_VS_MZPEAK).equal()).isTrue();
        assertThat(ModelAssert.compare(dia, withoutPeaks(dia), MZPEAK_VS_MZML).equal()).isTrue();

        // A_1 does not hide them, not even where its own condition holds: the mzML side's peaks of spectrum 2 are
        // not a row copy, the mzPeak side's are one
        Model notACopy = withSpectrum(small, 2, ModelAssertTest::oneChangedPeakIntensity);
        Result withA1 = ModelAssert.compare(notACopy, small, MZML_VS_MZPEAK, ModelAssert.A_1);
        assertThat(kinds(withA1)).containsExactly(Map.entry("spectrum[].peaks", 34));
        assertThat(withA1.differences()).contains(
                "spectrum[2].peaks: the mzML side has 485 peaks: MzMLReader builds none (design 5.4)");
        assertThat(withA1.stale()).containsExactly("A_1");
    }

    @Test
    void mixedModeReportsTheMzMLSidesPeakListAlsoWhereTheMzPeakSideHasNoPeaks() {
        // A_1's own condition holds (the mzML side's peaks of spectrum 2 are not a row copy, the mzPeak side has
        // none), but the difference lies in the mzML side's own peak list, which A_1 does not hide
        Spectrum s2 = small.spectrumList().get(2);
        Model mzml = withSpectrum(withoutPeaks(small), 2, s -> oneChangedPeakIntensity(s2));
        Model mzpeak = withoutPeaks(small);
        String peaks = "spectrum[2].peaks: the mzML side has 485 peaks: MzMLReader builds none (design 5.4)";
        Result mzmlFirst = ModelAssert.compare(mzml, mzpeak, MZML_VS_MZPEAK, ModelAssert.A_1);
        assertThat(mzmlFirst.differences()).containsExactly(peaks);
        assertThat(mzmlFirst.stale()).containsExactly("A_1");
        Result mzpeakFirst = ModelAssert.compare(mzpeak, mzml, MZPEAK_VS_MZML, ModelAssert.A_1);
        assertThat(mzpeakFirst.differences()).containsExactly(peaks);
        assertThat(mzpeakFirst.stale()).containsExactly("A_1");

        // equal peak arrays without peaks on both sides (parallel to no peaks, so of no values): the mzML side's are
        // a difference
        Spectrum s0 = dia.spectrumList().get(0);
        Model arraysOnly = withSpectrum(withoutPeaks(dia), 0, s -> new Spectrum(s0.description(), s0.mz(),
                s0.intensity(), List.of(), s0.arrays(), Map.of("MS:1003006", new double[0])));
        String peakArrays = "spectrum[0].peakArrays: the mzML side has peak arrays [MS:1003006]: MzMLReader builds"
                + " none (design 5.4)";
        assertThat(ModelAssert.compare(arraysOnly, arraysOnly, MZML_VS_MZPEAK).differences())
                .containsExactly(peakArrays);
        assertThat(ModelAssert.compare(arraysOnly, arraysOnly, MZPEAK_VS_MZML).differences())
                .containsExactly(peakArrays);

        // where neither side has peaks and the mzML side no peak arrays, the mzPeak side's are compared with none
        assertThat(ModelAssert.compare(withoutPeaks(dia), arraysOnly, MZML_VS_MZPEAK).paths())
                .containsExactly("spectrum[0].peakArrays[MS:1003006]");
        assertThat(ModelAssert.compare(arraysOnly, withoutPeaks(dia), MZPEAK_VS_MZML).paths())
                .containsExactly("spectrum[0].peakArrays[MS:1003006]");

        // where the mzML side has peaks, they are the difference and the peak lists are not compared pairwise, so
        // A_1 has nothing to hide: in mixed mode it hides only 'not a row copy'
        Model peaksOnly = withSpectrum(withoutPeaks(dia), 0, s -> new Spectrum(s0.description(), s0.mz(),
                s0.intensity(), oneChangedPeakIntensity(s0).peaks(), s0.arrays(), Map.of()));
        String mzmlPeaks = "spectrum[0].peaks: the mzML side has " + s0.peaks().size()
                + " peaks: MzMLReader builds none (design 5.4)";
        Result peaksFirst = ModelAssert.compare(peaksOnly, arraysOnly, MZML_VS_MZPEAK, ModelAssert.A_1);
        assertThat(peaksFirst.differences()).containsExactly(mzmlPeaks);
        assertThat(peaksFirst.stale()).containsExactly("A_1");
        Result arraysFirst = ModelAssert.compare(arraysOnly, peaksOnly, MZPEAK_VS_MZML, ModelAssert.A_1);
        assertThat(arraysFirst.differences()).containsExactly(mzmlPeaks);
        assertThat(arraysFirst.stale()).containsExactly("A_1");
    }

    @Test
    void rowCopyFollowsDesign54() {
        Spectrum centroid = small.spectrumList().get(2);
        assertThat(ModelAssert.isRowCopy(centroid)).isTrue();
        assertThat(ModelAssert.isRowCopy(dia.spectrumList().get(0))).isTrue();
        assertThat(ModelAssert.isRowCopy(chunked.spectrumList().get(0))).isFalse();
        assertThat(ModelAssert.isRowCopy(oneChangedPeakIntensity(centroid))).isFalse();

        // the continuity does not count; a row without a peak must have a non-finite m/z
        Spectrum profile = withDescription(centroid, d -> with(d, "signalContinuity", SignalContinuity.PROFILE));
        assertThat(ModelAssert.isRowCopy(profile)).isTrue();
        double[] mz = {1.0, Double.NaN, 3.0};
        double[] intensity = {10.0, 20.0, 30.0};
        List<CentroidPeak> peaks = List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(3.0, 30.0, 2));
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity, peaks))).isTrue();
        double[] finite = {1.0, 2.0, 3.0};
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), finite, intensity, peaks))).isFalse();

        // each peak names a distinct row holding its m/z, intensity and extra values
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity,
                List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(1.0, 10.0, 0))))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity,
                List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(3.0, 30.0, 5))))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity,
                List.of(new CentroidPeak(1.0, 10.0, -1), new CentroidPeak(3.0, 30.0, 2))))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity,
                List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(3.5, 30.0, 2))))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity,
                List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(3.0, 31.0, 2))))).isFalse();
        // the peak arrays are parallel to the stored peak list, which keeps the peak of non-finite m/z at index 1
        List<CentroidPeak> stored = List.of(new CentroidPeak(1.0, 10.0, 0), new CentroidPeak(Double.NaN, 20.0, 1),
                new CentroidPeak(3.0, 30.0, 2));
        Map<String, double[]> im = Map.of("MS:1003006", new double[] {0.5, 0.6, 0.7});
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity, stored, im,
                Map.of("MS:1003006", new double[] {0.5, 0.6, 0.7})))).isTrue();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity, stored, im,
                Map.of("MS:1003006", new double[] {0.5, 0.6, 0.8})))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity, stored, im,
                Map.of()))).isFalse();
        assertThat(ModelAssert.isRowCopy(new Spectrum(centroid.description(), mz, intensity, peaks, im,
                Map.of("MS:1003006", new double[] {0.5, 0.7})))).isFalse();
    }

    // ---- (f) legacy versus current ----------------------------------------------------------------------------------

    /**
     * Legacy small.unpacked (29e59b2, packed layout) versus current small.unpacked (2ee2589). A_16 hides the type and
     * the five summary params of every spectrum (48 x 6), A_17 the cv list. The retention times are equal (both read
     * {@code spectrum.time}), so A_9 hides nothing here and is stale. Known fixture differences, not exclusions: the
     * legacy scans store the start time as float32 (48, for example 0.004935000091791153 for 0.004935), and the legacy
     * isolation windows lack the user param {@code ms level} = 1 of the 34 MS2 precursors (read since AP-7).
     */
    @Test
    void legacySmallVersusCurrentSmallDiffersByA16A17AndKnownFixtureDifferences() {
        Model legacy = Model.load(Fixtures.legacy("small.unpacked.mzpeak"));

        Result result = ModelAssert.compare(legacy, small, STRICT, ModelAssert.A_16, ModelAssert.A_17);

        assertThat(result.hidden()).containsExactly(Map.entry("A_16", 288), Map.entry("A_17", 1));
        assertThat(result.stale()).isEmpty();
        assertThat(kinds(result)).containsExactly(
                Map.entry("spectrum[].precursors[].isolationWindow.parameters[ms level]", 34),
                Map.entry("spectrum[].scans[].startTime", 48));
        assertThat(result.differences()).contains(
                "spectrum[0].scans[0].startTime: expected 0.004935000091791153, got 0.004935",
                "spectrum[2].precursors[0].isolationWindow.parameters[ms level]: expected [], got"
                        + " [\"ms level\"=1 (Long)]");

        Result withA9 = ModelAssert.compare(legacy, small, STRICT, ModelAssert.A_9, ModelAssert.A_16,
                ModelAssert.A_17);
        assertThat(withA9.stale()).containsExactly("A_9");
        assertThat(withA9.report()).startsWith("stale exclusion A_9: it hides no difference\n82 differences\n");
        for (int i = 0; i < 48; i++) {
            assertThat(Double.compare(legacy.spectrumList().get(i).description().retentionTime(),
                    small.spectrumList().get(i).description().retentionTime())).isZero();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** Asserts that the strict comparison finds exactly {@code lines}, the first at {@code path}. */
    private static void assertOnly(Model expected, Model actual, String path, String... lines) {
        Result result = ModelAssert.compare(expected, actual, STRICT);
        assertThat(result.differences()).containsExactly(lines);
        assertThat(result.paths().get(0)).isEqualTo(path);
        assertThat(result.equal()).isFalse();
    }

    /** The paths of the differences with every index removed, counted. */
    private static Map<String, Integer> kinds(Result result) {
        Map<String, Integer> kinds = new TreeMap<>();
        for (String p : result.paths()) {
            kinds.merge(p.replaceAll("\\[\\d+]", "[]"), 1, Integer::sum);
        }
        return kinds;
    }

    /** {@code s} with its first peak's intensity raised by one, so the peaks are no row copy of the arrays. */
    private static Spectrum oneChangedPeakIntensity(Spectrum s) {
        List<CentroidPeak> peaks = new ArrayList<>(s.peaks());
        CentroidPeak p = peaks.get(0);
        peaks.set(0, new CentroidPeak(p.mz(), p.intensity() + 1, p.index()));
        return new Spectrum(s.description(), s.mz(), s.intensity(), peaks, s.arrays(), s.peakArrays());
    }

    /** {@code s} with its rows in reverse order, and with the last intensity changed when {@code change}. */
    private static Spectrum reversed(Spectrum s, boolean change) {
        int n = s.mz().length;
        double[] mz = new double[n];
        double[] intensity = new double[n];
        Map<String, double[]> arrays = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : s.arrays().entrySet()) {
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = e.getValue()[n - 1 - i];
            }
            arrays.put(e.getKey(), values);
        }
        for (int i = 0; i < n; i++) {
            mz[i] = s.mz()[n - 1 - i];
            intensity[i] = s.intensity()[n - 1 - i];
        }
        if (change) {
            intensity[n - 1] += 1;
        }
        return new Spectrum(s.description(), mz, intensity, s.peaks(), arrays, s.peakArrays());
    }

    /** {@code s} with the points {@code i} and {@code i + 1} swapped in every array. */
    private static Spectrum swapped(Spectrum s, int i) {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        s.arrays().forEach((key, values) -> arrays.put(key, swap(values, i)));
        return new Spectrum(s.description(), swap(s.mz(), i), swap(s.intensity(), i), s.peaks(), arrays,
                s.peakArrays());
    }

    private static double[] swap(double[] values, int i) {
        double[] out = values.clone();
        out[i] = values[i + 1];
        out[i + 1] = values[i];
        return out;
    }

    /** {@code m} with the m/z array of spectrum {@code i} copied and changed by {@code change}. */
    private static Model withMz(Model m, int i, java.util.function.Consumer<double[]> change) {
        return withSpectrum(m, i, s -> {
            double[] mz = s.mz().clone();
            change.accept(mz);
            return new Spectrum(s.description(), mz, s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
        });
    }

    private static <T> List<T> append(List<T> list, T element) {
        List<T> out = new ArrayList<>(list);
        out.add(element);
        return out;
    }

    private static List<Param> replaceParam(List<Param> params, String accession, UnaryOperator<Param> f) {
        List<Param> out = new ArrayList<>();
        for (Param p : params) {
            out.add(accession.equals(p.accession()) ? f.apply(p) : p);
        }
        assertThat(out).isNotEqualTo(params);
        return out;
    }

    private static Spectrum withDescription(Spectrum s, UnaryOperator<SpectrumDescription> f) {
        return new Spectrum(f.apply(s.description()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Model withSpectrum(Model m, int i, UnaryOperator<Spectrum> f) {
        return with(m, "spectra", replace(m.spectrumList(), i, f));
    }

    private static Model withSpectra(Model m, UnaryOperator<Spectrum> f) {
        List<Spectrum> spectra = new ArrayList<>();
        for (Spectrum s : m.spectrumList()) {
            spectra.add(f.apply(s));
        }
        return with(m, "spectra", spectra);
    }

    /** {@code m} with no peaks and no peak arrays, as {@code MzMLReader} reads every spectrum (design 5.4). */
    private static Model withoutPeaks(Model m) {
        return withSpectra(m, s -> new Spectrum(s.description(), s.mz(), s.intensity(), List.of(), s.arrays(),
                Map.of()));
    }

    private static Model withChromatogram(Model m, UnaryOperator<Chromatogram> f) {
        return with(m, "chromatograms", replace(m.chromatograms(), 0, f));
    }

    private static Model withScanSettings(Model m, UnaryOperator<ScanSettings> f) {
        return with(m, "scanSettings", replace(m.scanSettings(), 0, f));
    }

    private static Model withRun(Model m, UnaryOperator<FileMetadata.MsRun> f) {
        return with(m, "fileMetadata", with(m.fileMetadata(), "run", f.apply(m.fileMetadata().run())));
    }

    private static Model withContacts(Model m, List<FileMetadata.Contact> contacts) {
        FileMetadata fm = m.fileMetadata();
        return with(m, "fileMetadata", with(fm, "fileDescription", with(fm.fileDescription(), "contacts", contacts)));
    }

    private static <T> List<T> replace(List<T> list, int i, UnaryOperator<T> f) {
        List<T> copy = new ArrayList<>(list);
        copy.set(i, f.apply(copy.get(i)));
        return copy;
    }

    /** {@code record} with its component {@code name} replaced by {@code value}, through the canonical constructor. */
    @SuppressWarnings("unchecked")
    static <R extends Record> R with(R record, String name, Object value) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                found |= components[i].getName().equals(name);
                args[i] = components[i].getName().equals(name) ? value
                        : components[i].getAccessor().invoke(record);
            }
            assertThat(found).as("%s has a component %s", record.getClass(), name).isTrue();
            return (R) record.getClass().getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
