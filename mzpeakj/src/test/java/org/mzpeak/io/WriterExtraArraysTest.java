package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * MZ-1 (upstream WR-11, spectrum part; design §11.7 of the upstream design, §5.7 of the mzML design): the writer keeps
 * the extra signal arrays of spectra, {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()}, as extra columns of
 * spectra_data and spectra_peaks, each with an array-index entry, in the point layout and as chunk secondary lists in
 * the Numpress layout.
 *
 * <p>Oracle: the vendored HUPO-PSI fixtures, read by {@link MzPeakReader}: current diaPASEF.ref, whose 9 centroid
 * spectra carry 291,453 mean inverse reduced ion mobility values (MS:1003006) in spectra_peaks, and current
 * small.unpacked, whose spectrum 2 is a centroid spectrum of 485 points, changed where a test says so.
 */
class WriterExtraArraysTest {

    private static final Path DIA_REF = Fixtures.current("diaPASEF.ref.mzpeak");
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String MEAN_INVERSE_REDUCED_ION_MOBILITY = "MS:1003006";
    private static final String CHARGE = "MS:1000516";
    private static final String MY_ARRAY = "my array";
    private static final String FLOAT64 = "MS:1000523";
    private static final String NON_STANDARD = "MS:1000786";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Options NUMPRESS = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);

    // ---- (a) diaPASEF.ref with the defaults -----------------------------------------------------------------------

    /**
     * diaPASEF.ref read, written with the defaults (point layout, split metadata) and read again: every spectrum keeps
     * its ion mobility, value for value, and spectra_peaks lists it once in its array index.
     */
    @Test
    void diaPasefRefKeepsItsIonMobilityThroughAWriteWithTheDefaults(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(DIA_REF);
        Path out = tmp.resolve("dia.mzpeak");

        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(back.spectra).hasSize(9);
        long total = 0;
        for (int i = 0; i < 9; i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            assertThat(b.peakArrays().keySet()).as("spectrum %d peak arrays", i)
                    .isEqualTo(Set.of(MEAN_INVERSE_REDUCED_ION_MOBILITY));
            assertThat(b.peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY)).as("spectrum %d ion mobility", i)
                    .isEqualTo(a.peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY));
            total += b.peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY).length;
        }
        assertThat(back.spectra.get(0).peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY)).hasSize(205_921);
        assertThat(total).isEqualTo(291_453);

        assertThat(extraEntries(out, DataKind.PEAKS)).singleElement().satisfies(e -> {
            assertThat(e.arrayType()).isEqualTo(MEAN_INVERSE_REDUCED_ION_MOBILITY);
            assertThat(e.arrayName()).isEqualTo("mean inverse reduced ion mobility array");
            assertThat(e.dataType()).isEqualTo(FLOAT64);
            assertThat(e.unit()).isEqualTo("MS:1002814");
            assertThat(e.field()).isEqualTo("mean_inverse_reduced_ion_mobility");
            assertThat(e.format()).isEqualTo(BufferFormat.POINT);
        });
        assertThat(extraEntries(out, DataKind.DATA_ARRAYS)).as("spectra_data gets the same extra column")
                .extracting(Entry::field).containsExactly("mean_inverse_reduced_ion_mobility");
    }

    // ---- (b) diaPASEF.ref as Numpress --------------------------------------------------------------------------

    /**
     * diaPASEF.ref written in the Numpress layout with SLOF intensities: the ion mobility is a chunk secondary list,
     * so its values come back exactly, each at the stored position of its m/z, which Numpress linear changes only
     * within its precision.
     */
    @Test
    void diaPasefRefKeepsItsIonMobilityExactlyInTheNumpressLayout(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(DIA_REF);
        Path out = tmp.resolve("dia-numpress.mzpeak");

        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, NUMPRESS);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        for (int i = 0; i < 9; i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            assertThat(b.peakArrays()).as("spectrum %d peak arrays", i)
                    .containsOnlyKeys(MEAN_INVERSE_REDUCED_ION_MOBILITY);
            double[] im = a.peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY);
            assertThat(b.peakArrays().get(MEAN_INVERSE_REDUCED_ION_MOBILITY)).as("spectrum %d ion mobility", i)
                    .isEqualTo(im);
            assertThat(b.mz()).as("spectrum %d m/z", i).hasSameSizeAs(a.mz());
            for (int k = 0; k < a.mz().length; k++) {
                assertThat(b.mz()[k]).as("spectrum %d m/z %d", i, k).isCloseTo(a.mz()[k], within(1e-4));
            }
        }
        assertThat(extraEntries(out, DataKind.PEAKS)).singleElement().satisfies(e -> {
            assertThat(e.arrayType()).isEqualTo(MEAN_INVERSE_REDUCED_ION_MOBILITY);
            assertThat(e.field()).isEqualTo("mean_inverse_reduced_ion_mobility");
            assertThat(e.unit()).isEqualTo("MS:1002814");
            assertThat(e.format()).isEqualTo(BufferFormat.CHUNK_SECONDARY);
        });
    }

    // ---- (c), (e) a charge array and a non-standard array -------------------------------------------------------

    static Stream<Arguments> layouts() {
        List<Arguments> cases = new ArrayList<>();
        for (SignalEncoding encoding : SignalEncoding.values()) {
            for (MetadataLayout metadata : MetadataLayout.values()) {
                cases.add(Arguments.of(encoding, metadata));
            }
        }
        return cases.stream();
    }

    /**
     * Spectrum 2 of current small.unpacked, a CENTROID peak copy of 485 points, with a charge array of 2.0 and a
     * non-standard array {@code my array} of 0..484 as its arrays and its peak arrays: it reads back with both, in
     * either signal encoding and either metadata layout, through columns {@code charge} and {@code my_array} without
     * a unit; the other 47 spectra, which have no extra array, read back without one.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layouts")
    void aChargeArrayAndANonStandardArrayReadBackEqual(SignalEncoding encoding, MetadataLayout metadata,
                                                       @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        Spectrum s2 = source.spectra.get(2);
        assertThat(s2.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        assertThat(s2.mz()).hasSize(485);
        assertThat(source.spectra).allSatisfy(s -> assertThat(s.arrays()).isEmpty());
        Map<String, double[]> extras = chargeAndMyArray(485);
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        spectra.set(2, new Spectrum(s2.description(), s2.mz(), s2.intensity(), s2.peaks(), extras, extras));
        Path out = tmp.resolve("extras.mzpeak");

        MzPeakWriter.writeDirectory(out, spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withSignalEncoding(encoding)
                        .withMetadataLayout(metadata));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        Spectrum b = back.spectra.get(2);
        assertThat(b.arrays()).containsOnlyKeys(CHARGE, MY_ARRAY);
        assertThat(b.arrays().get(CHARGE)).isEqualTo(extras.get(CHARGE));
        assertThat(b.arrays().get(MY_ARRAY)).isEqualTo(extras.get(MY_ARRAY));
        assertThat(b.peakArrays()).containsOnlyKeys(CHARGE, MY_ARRAY);
        assertThat(b.peakArrays().get(MY_ARRAY)).isEqualTo(extras.get(MY_ARRAY));
        for (int i = 0; i < back.spectra.size(); i++) {
            if (i != 2) {
                assertThat(back.spectra.get(i).arrays()).as("spectrum %d arrays", i).isEmpty();
                assertThat(back.spectra.get(i).peakArrays()).as("spectrum %d peak arrays", i).isEmpty();
            }
        }
        for (DataKind kind : List.of(DataKind.DATA_ARRAYS, DataKind.PEAKS)) {
            assertThat(extraEntries(out, kind)).as("%s", kind)
                    .extracting(Entry::field, Entry::arrayType, Entry::arrayName, Entry::unit, Entry::dataType)
                    .containsExactly(
                            tuple("charge", CHARGE, "charge array", null, FLOAT64),
                            tuple("my_array", NON_STANDARD, MY_ARRAY, null, FLOAT64));
        }
    }

    /**
     * A key on a profile spectrum of spectra_data and on the centroid spectrum 2 of spectra_peaks only: both files
     * get the column, the other spectra write null cells and read back without the key, and a NaN value is written as
     * NaN.
     */
    @Test
    void spectraWithoutAKeyReadBackWithoutIt(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        int profile = firstProfile(spectra);
        Spectrum p = spectra.get(profile);
        double[] noise = new double[p.mz().length];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = i % 7 == 0 ? Double.NaN : i * 0.5;
        }
        spectra.set(profile, new Spectrum(p.description(), p.mz(), p.intensity(), p.peaks(), Map.of("noise", noise),
                p.peakArrays()));
        Spectrum s2 = spectra.get(2);
        double[] charge = filled(s2.mz().length, 3.0);
        spectra.set(2, new Spectrum(s2.description(), s2.mz(), s2.intensity(), List.of(), Map.of(CHARGE, charge),
                Map.of()));
        Path out = tmp.resolve("mixed.mzpeak");

        MzPeakWriter.writeDirectory(out, spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        for (int i = 0; i < back.spectra.size(); i++) {
            Spectrum b = back.spectra.get(i);
            if (i == profile) {
                assertThat(b.arrays()).containsOnlyKeys("noise");
                assertThat(b.arrays().get("noise")).isEqualTo(noise);
            } else if (i == 2) {
                assertThat(b.arrays()).containsOnlyKeys(CHARGE);
                assertThat(b.arrays().get(CHARGE)).isEqualTo(charge);
                assertThat(b.peakArrays()).containsOnlyKeys(CHARGE);
            } else {
                assertThat(b.arrays()).as("spectrum %d arrays", i).isEmpty();
            }
        }
        List<String> columns = List.of("noise", "charge");
        assertThat(extraEntries(out, DataKind.DATA_ARRAYS)).extracting(Entry::field).isEqualTo(columns);
        assertThat(extraEntries(out, DataKind.PEAKS)).extracting(Entry::field).isEqualTo(columns);
    }

    // ---- (d) main-axis order ------------------------------------------------------------------------------------

    /**
     * Spectrum 2 with its m/z, intensity and both extra arrays reversed and no peak list: it is written ascending, its
     * extras permuted with the m/z, and the write warns once that it re-sorted the spectrum.
     */
    @Test
    void reversedArraysAreWrittenAscendingWithTheirExtrasPermutedAlike(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        Spectrum s2 = source.spectra.get(2);
        for (int i = 1; i < s2.mz().length; i++) {
            assertThat(s2.mz()[i]).as("m/z %d", i).isGreaterThan(s2.mz()[i - 1]);
        }
        Map<String, double[]> extras = chargeAndMyArray(485);
        Map<String, double[]> reversed = new LinkedHashMap<>();
        extras.forEach((k, v) -> reversed.put(k, reversed(v)));
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        spectra.set(2, new Spectrum(s2.description(), reversed(s2.mz()), reversed(s2.intensity()), List.of(), reversed,
                Map.of()));
        Path out = tmp.resolve("reversed.mzpeak");

        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, spectra, source.chromatograms,
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata));

        assertThat(warnings).filteredOn(w -> w.contains("main axis")).containsExactly(
                "spectrum 2: main axis not ascending; re-sorted 485 rows");
        Spectrum b = ForwardBackwardCorpusTest.readSource(out).spectra.get(2);
        assertThat(b.mz()).isEqualTo(s2.mz());
        assertThat(b.intensity()).isEqualTo(s2.intensity());
        assertThat(b.arrays().get(CHARGE)).isEqualTo(extras.get(CHARGE));
        assertThat(b.arrays().get(MY_ARRAY)).isEqualTo(extras.get(MY_ARRAY));
        assertThat(spectra.get(2).arrays().get(MY_ARRAY)).as("the caller's array").isEqualTo(reversed.get(MY_ARRAY));
    }

    // ---- (f) units -------------------------------------------------------------------------------------------------

    /**
     * A drift-time array is written in milliseconds, the default unit of {@code CvTerms.DEFAULT_ARRAY_UNITS}; the
     * generic ion mobility array MS:1002893, which has none, without a unit.
     */
    @Test
    void anArrayIsWrittenWithItsDefaultUnitOrWithout(@TempDir Path tmp) {
        Spectrum drift = twoPoints(0, Map.of("MS:1002477", new double[] {1.5, 2.5}));
        Spectrum generic = twoPoints(1, Map.of("MS:1002893", new double[] {0.5, 0.75}));
        Path out = tmp.resolve("units.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(drift, generic));

        assertThat(extraEntries(out, DataKind.PEAKS))
                .extracting(Entry::field, Entry::arrayType, Entry::unit)
                .containsExactly(tuple("mean_drift_time", "MS:1002477", "UO:0000028"),
                        tuple("ion_mobility", "MS:1002893", null));
        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(back.spectra.get(0).arrays().get("MS:1002477")).containsExactly(1.5, 2.5);
        assertThat(back.spectra.get(1).arrays().get("MS:1002893")).containsExactly(0.5, 0.75);
        assertThat(back.spectra.get(1).ionMobility()).hasValueSatisfying(v -> assertThat(v).containsExactly(0.5, 0.75));
    }

    // ---- (j) the CV of an array type ---------------------------------------------------------------------------

    /**
     * BTO, a CV that KnownCvs declares and the data use nowhere else, used only as an array type: column
     * {@code bto_0000142}, array name the CURIE, no unit, and metadata.cv_list declares BTO.
     */
    @Test
    void aKnownCvUsedOnlyAsAnArrayTypeIsDeclared(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("bto.mzpeak");

        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out,
                List.of(twoPoints(0, Map.of("BTO:0000142", new double[] {1.0, 2.0})))));

        assertThat(warnings).isEmpty();
        assertThat(extraEntries(out, DataKind.PEAKS)).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("bto_0000142");
            assertThat(e.arrayType()).isEqualTo("BTO:0000142");
            assertThat(e.arrayName()).isEqualTo("BTO:0000142");
            assertThat(e.unit()).isNull();
        });
        assertThat(cvIds(out)).containsExactly("MS", "UO", "BTO");
        assertThat(ForwardBackwardCorpusTest.readSource(out).spectra.get(0).arrays().get("BTO:0000142"))
                .containsExactly(1.0, 2.0);
    }

    /** A CURIE whose prefix no known CV has is written as given, and the write warns that the CV is not declared. */
    @Test
    void anUnknownCvUsedAsAnArrayTypeIsReported(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("zzz.mzpeak");

        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out,
                List.of(twoPoints(0, Map.of("ZZZ:0000001", new double[] {1.0, 2.0})))));

        assertThat(warnings).singleElement().asString()
                .contains("ZZZ").contains("is not a controlled vocabulary mzPeakJ knows");
        assertThat(cvIds(out)).containsExactly("MS", "UO");
        assertThat(extraEntries(out, DataKind.PEAKS)).extracting(Entry::field).containsExactly("zzz_0000001");
    }

    // ---- (h) no more drop warning -------------------------------------------------------------------------------

    /** diaPASEF.ref, whose spectra all carry an extra array, is planned without a warning about spectra. */
    @Test
    void theExtraArraysOfSpectraAreNoLongerReportedAsDropped() {
        Source source = ForwardBackwardCorpusTest.readSource(DIA_REF);

        WritePlan plan = WritePlanner.plan(source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults());

        assertThat(plan.report().warnings()).noneMatch(w -> w.contains("extra signal arrays"));
        assertThat(plan.spectrumExtras()).extracting(ExtraColumn::key)
                .containsExactly(MEAN_INVERSE_REDUCED_ION_MOBILITY);
    }

    // ---- column names ---------------------------------------------------------------------------------------------

    /**
     * The column of each kind of key: the spec's short name, the PSI-MS name of another array type without
     * {@code array}, a CURIE outside the array types in lower case, a name, the field of a {@code <key>@<field>}
     * key, and {@code non_standard_data} for keys that leave no name; a name already taken, by a column of the signal
     * files or by another extra, gets {@code _2}, {@code _3}, ... A key that is not a CURIE is a non-standard data
     * array named by the key, and an array type keeps its PSI-MS name.
     */
    @Test
    void everyKindOfKeyGetsItsColumnArrayTypeNameAndUnit() {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        for (String key : List.of("MS:1003006", "MS:1000820", "MS:1002743", "BTO:0000142", "ms level", "My Array",
                "MS:1003006@im_2", "x@", "", "intensity", "mz", "my-array", "My_Array", "MS:1000786")) {
            arrays.put(key, new double[] {1.0, 2.0});
        }

        List<ExtraColumn> columns = WritePlanner.plan(List.of(twoPoints(0, arrays)), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY, Options.defaults()).spectrumExtras();

        assertThat(columns).containsExactly(
                new ExtraColumn("MS:1003006", "mean_inverse_reduced_ion_mobility", "MS:1003006",
                        "mean inverse reduced ion mobility array", "MS:1002814"),
                new ExtraColumn("MS:1000820", "flow_rate", "MS:1000820", "flow rate array", null),
                new ExtraColumn("MS:1002743", "sampled_noise_m_z", "MS:1002743", "sampled noise m/z array", null),
                new ExtraColumn("BTO:0000142", "bto_0000142", "BTO:0000142", "BTO:0000142", null),
                new ExtraColumn("ms level", "ms_level", NON_STANDARD, "ms level", "UO:0000186"),
                new ExtraColumn("My Array", "My_Array", NON_STANDARD, "My Array", null),
                new ExtraColumn("MS:1003006@im_2", "im_2", NON_STANDARD, "MS:1003006@im_2", null),
                new ExtraColumn("x@", "non_standard_data", NON_STANDARD, "x@", null),
                new ExtraColumn("", "non_standard_data_2", NON_STANDARD, "", null),
                new ExtraColumn("intensity", "intensity_2", NON_STANDARD, "intensity", null),
                new ExtraColumn("mz", "mz_2", NON_STANDARD, "mz", null),
                new ExtraColumn("my-array", "my_array", NON_STANDARD, "my-array", null),
                new ExtraColumn("My_Array", "My_Array_2", NON_STANDARD, "My_Array", null),
                new ExtraColumn("MS:1000786", "non_standard_data_3", NON_STANDARD, "non-standard data array", null));
    }

    /**
     * Keys of every kind written in both encodings read back under the same key, the CURIE MS:1000786 excepted: it
     * is written with its PSI-MS name and read back under that name, as every non-standard data array is.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POINT", "NUMPRESS"})
    void keysOfEveryKindReadBackUnderTheirKey(String encoding, @TempDir Path tmp) {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        List<String> keys = List.of("MS:1003006", "MS:1000820", "BTO:0000142", "ms level", "MS:1003006@im_2", "x@",
                "", "intensity", "MS:1000786");
        for (int k = 0; k < keys.size(); k++) {
            arrays.put(keys.get(k), new double[] {k, k + 0.5});
        }
        Path out = tmp.resolve("keys.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(twoPoints(0, arrays)), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY,
                Options.defaults().withSignalEncoding(SignalEncoding.valueOf(encoding)));

        Map<String, double[]> back = ForwardBackwardCorpusTest.readSource(out).spectra.get(0).arrays();
        List<String> expectedKeys = new ArrayList<>(keys);
        expectedKeys.set(keys.size() - 1, "non-standard data array");
        assertThat(back.keySet()).containsExactlyElementsOf(expectedKeys);
        for (int k = 0; k < keys.size(); k++) {
            assertThat(back.get(expectedKeys.get(k))).as(keys.get(k)).containsExactly(k, k + 0.5);
        }
    }

    // ---- disposition: peak copies -----------------------------------------------------------------------------

    /**
     * A CENTROID spectrum whose peak list is a copy of its arrays is still a peak copy, written to spectra_peaks only,
     * when its peak arrays are a copy of its arrays too; when its peak arrays differ from its arrays in a key or in
     * a value, both files are written, so that neither map is lost.
     */
    @Test
    void aPeakCopyMustCopyTheExtraArraysToo(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        Spectrum s2 = source.spectra.get(2);
        Map<String, double[]> extras = chargeAndMyArray(485);
        double[] changed = extras.get(MY_ARRAY).clone();
        changed[100] = -1.0;
        Map<String, double[]> otherValue = new LinkedHashMap<>(extras);
        otherValue.put(MY_ARRAY, changed);

        assertThat(WritePlanner.isPeakCopy(withExtras(s2, extras, extras))).isTrue();
        assertThat(WritePlanner.isPeakCopy(withExtras(s2, extras, Map.of()))).isFalse();
        assertThat(WritePlanner.isPeakCopy(withExtras(s2, Map.of(), extras))).isFalse();
        assertThat(WritePlanner.isPeakCopy(withExtras(s2, extras, otherValue))).isFalse();

        Spectrum split = withExtras(s2, extras, otherValue);
        Path out = tmp.resolve("split.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(split));
        Spectrum b = ForwardBackwardCorpusTest.readSource(out).spectra.get(0);
        assertThat(b.mz()).isEqualTo(s2.mz());
        assertThat(b.arrays().get(MY_ARRAY)).isEqualTo(extras.get(MY_ARRAY));
        assertThat(b.peakArrays().get(MY_ARRAY)).isEqualTo(changed);
        assertThat(b.peaks()).isEqualTo(s2.peaks());
    }

    /**
     * A CENTROID spectrum whose peak names a row beyond its peak arrays is not a peak copy, and its peak list cannot
     * be written with them: the write is refused before any I/O.
     */
    @Test
    void aPeakArrayShorterThanTheRowsItsPeaksNameIsNoCopyAndIsRefused(@TempDir Path tmp) {
        double[] mz = {100.0, 200.0, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        Spectrum s = new Spectrum(centroid(0), mz, intensity, List.of(new CentroidPeak(300.0, 3.0, 2)),
                Map.of(CHARGE, new double[] {1.0, 1.0, 1.0}), Map.of(CHARGE, new double[] {1.0}));
        Path out = tmp.resolve("short.mzpeak");

        assertThat(WritePlanner.isPeakCopy(s)).isFalse();
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(s)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 0: the peak at m/z 300.0 has index 2, which is not a position of its peak arrays"
                        + " (1 values); a peak's index() is its position in the list the spectrum was built from");
        assertThat(out).doesNotExist();
    }

    /** A peak whose index is negative cannot be written with its peak arrays: the write is refused. */
    @Test
    void aPeakWithANegativeIndexAndPeakArraysIsRefused(@TempDir Path tmp) {
        Spectrum s = new Spectrum(centroid(0), new double[0], new double[0], List.of(new CentroidPeak(150.0, 5.0, -1)),
                Map.of(), Map.of(CHARGE, new double[] {2.0}));
        Path out = tmp.resolve("negative.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(s)))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("spectrum 0: the peak at m/z 150.0 has index -1, which is not a position of"
                        + " its peak arrays (1 values)");
        assertThat(out).doesNotExist();
    }

    /**
     * Peaks whose indices are positions of their peak arrays are written in m/z order, each with its own values; the
     * peak list of a spectrum without peak arrays writes null cells and reads back without them.
     */
    @Test
    void peakArraysAreWrittenThroughEachPeaksIndex(@TempDir Path tmp) {
        List<CentroidPeak> peaks = List.of(new CentroidPeak(300.0, 3.0, 0), new CentroidPeak(100.0, 1.0, 1),
                new CentroidPeak(200.0, 2.0, 2));
        Spectrum s = new Spectrum(centroid(0), new double[0], new double[0], peaks, Map.of(),
                Map.of(CHARGE, new double[] {3.0, 1.0, 2.0}));
        Spectrum without = new Spectrum(centroid(1), new double[0], new double[0],
                List.of(new CentroidPeak(150.0, 5.0, 0)), Map.of(), Map.of());
        Path out = tmp.resolve("peaks.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(s, without));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        Spectrum b = back.spectra.get(0);
        assertThat(b.peaks()).extracting(CentroidPeak::mz).containsExactly(100.0, 200.0, 300.0);
        assertThat(b.peakArrays().get(CHARGE)).containsExactly(1.0, 2.0, 3.0);
        assertThat(back.spectra.get(1).peaks()).extracting(CentroidPeak::mz).containsExactly(150.0);
        assertThat(back.spectra.get(1).peakArrays()).isEmpty();
    }

    // ---- refused keys -----------------------------------------------------------------------------------------

    /** The m/z and intensity array types cannot be extra keys: the write is refused before any I/O. */
    @ParameterizedTest
    @ValueSource(strings = {"MS:1000514", "MS:1000515"})
    void aMainArrayTypeAsAnExtraKeyIsRefused(String key, @TempDir Path tmp) {
        Path out = tmp.resolve("main.mzpeak");
        Spectrum s = twoPoints(0, Map.of(key, new double[] {1.0, 2.0}));

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(s)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 0: the extra array " + key + " ("
                        + (key.equals("MS:1000514") ? "m/z array" : "intensity array") + ") is a main array of a"
                        + " spectrum, which mz() and intensity() hold; it cannot be written as an extra column");
        assertThat(out).doesNotExist();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** A charge array of 2.0 and {@code my array} of 0..n-1, in that order. */
    private static Map<String, double[]> chargeAndMyArray(int n) {
        double[] counting = new double[n];
        for (int i = 0; i < n; i++) {
            counting[i] = i;
        }
        Map<String, double[]> extras = new LinkedHashMap<>();
        extras.put(CHARGE, filled(n, 2.0));
        extras.put(MY_ARRAY, counting);
        return extras;
    }

    private static double[] filled(int n, double value) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static double[] reversed(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[values.length - 1 - i];
        }
        return out;
    }

    /** {@code s} with {@code arrays} and {@code peakArrays} in place of its own. */
    private static Spectrum withExtras(Spectrum s, Map<String, double[]> arrays, Map<String, double[]> peakArrays) {
        return new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), arrays, peakArrays);
    }

    /** A CENTROID MS1 description with {@code index} and no scans or precursors. */
    private static SpectrumDescription centroid(long index) {
        return new SpectrumDescription(index, "scan=" + (index + 1), 1, 1.0 + index, null, SignalContinuity.CENTROID,
                0, 0, List.of(), List.of(), List.of());
    }

    /** A CENTROID spectrum of two points without a peak list, with {@code arrays}. */
    private static Spectrum twoPoints(long index, Map<String, double[]> arrays) {
        return new Spectrum(centroid(index), new double[] {100.0, 200.0}, new double[] {10.0, 20.0}, List.of(),
                arrays, Map.of());
    }

    private static int firstProfile(List<Spectrum> spectra) {
        for (int i = 0; i < spectra.size(); i++) {
            if (spectra.get(i).description().signalContinuity() == SignalContinuity.PROFILE) {
                return i;
            }
        }
        throw new AssertionError("no profile spectrum");
    }

    /** The WARNING messages {@link MzPeakWriter} logs while {@code write} runs. */
    private static List<String> warnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    /** The ids of the dataset's metadata.cv_list, in order. */
    private static List<String> cvIds(Path dataset) throws IOException {
        JsonNode index = MAPPER.readTree(Files.readAllBytes(dataset.resolve(ManifestWriter.INDEX)));
        List<String> ids = new ArrayList<>();
        for (JsonNode cv : index.path("metadata").path("cv_list")) {
            ids.add(cv.path("id").asText());
        }
        return ids;
    }

    /**
     * The array-index entries of the spectrum signal member of {@code kind} in {@code dataset} that are neither the
     * index, a column of the m/z axis nor the intensity.
     */
    static List<Entry> extraEntries(Path dataset, DataKind kind) {
        return arrayIndex(dataset, kind).entries().stream()
                .filter(e -> e.format() != BufferFormat.INDEX && !"MS:1000514".equals(e.arrayType())
                        && !"MS:1000515".equals(e.arrayType()))
                .toList();
    }

    /** The array index in the footer of the spectrum signal member of {@code kind} in {@code dataset}. */
    static ArrayIndex arrayIndex(Path dataset, DataKind kind) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String name = manifest.find(EntityType.SPECTRUM, kind).orElseThrow().name();
            ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
            return ArrayIndex.parse(footer.keyValue().get(SignalEntity.SPECTRUM.arrayIndexKey()),
                    SignalEntity.SPECTRUM);
        }
    }
}
