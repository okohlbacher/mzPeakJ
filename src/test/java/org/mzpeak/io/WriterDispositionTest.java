package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Where {@link MzPeakWriter} routes each spectrum's signal, spectra_data or spectra_peaks, and the counts its
 * spectra_metadata row declares for them (design §11.5). Inputs are the legacy {@code small.unpacked},
 * {@code small} and {@code has_uv} fixtures, read with {@link ForwardBackwardCorpusTest#readSource}, and spectra
 * built from them or by hand.
 */
class WriterDispositionTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.legacy("has_uv.mzpeak");
    private static final String METADATA = "spectra_metadata.parquet";
    private static final String DATA = "spectra_data.parquet";
    private static final String PEAKS = "spectra_peaks.parquet";
    /** The count columns of the packed spectrum struct. */
    private static final String DATA_POINTS = "MS_1003060_number_of_data_points";
    private static final String NUMBER_OF_PEAKS = "MS_1003059_number_of_peaks";
    /** The count columns of the split spectra_metadata table. */
    private static final String SPLIT_DATA_POINTS = "number_of_data_points";
    private static final String SPLIT_NUMBER_OF_PEAKS = "number_of_peaks";

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /**
     * Every spectrum of legacy small is read back with its centroids in both {@code mz()}/{@code intensity()} and
     * {@code peaks()}. Its 34 CENTROID spectra are peak copies and go to spectra_peaks only; its 14 PROFILE spectra
     * go to spectra_data. The counts are rows/spectra of each file.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak"})
    void legacySmallRowsPerFile(String fixture, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(Fixtures.legacy(fixture));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertThat(summary(rowsPerSpectrum(out.resolve(DATA), "point"))).isEqualTo("217710/14");
        assertThat(summary(rowsPerSpectrum(out.resolve(PEAKS), "point"))).isEqualTo("25344/34");
        assertCountsMatchTheRowsWritten(out, "point");
    }

    @Test
    void legacySmallNumpressSpectraPerFile(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);

        assertThat(rowsPerSpectrum(out.resolve(DATA), "chunk")).hasSize(14);
        assertThat(rowsPerSpectrum(out.resolve(PEAKS), "chunk")).hasSize(34);
    }

    /**
     * Every spectrum of has_uv is a CENTROID peak copy: the point layout writes 212 peak lists and a spectra_data
     * without a row, listed under its manifest role, because both Rust reference readers open spectra_data
     * unconditionally (FN-10; before, it was not written and the output was unreadable for them).
     */
    @Test
    void hasUvRowsPerFile(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        try (MzPeakSource source = MzPeakSource.open(out)) {
            assertThat(MzPeakManifest.fromSource(source).find(MzPeakManifest.EntityType.SPECTRUM,
                    MzPeakManifest.DataKind.DATA_ARRAYS)).map(MzPeakManifest.Entry::name).contains(DATA);
        }
        assertThat(ParquetFooter.read(new LocalInputFile(out.resolve(DATA))).rowCount()).isZero();
        assertThat(summary(rowsPerSpectrum(out.resolve(PEAKS), "point"))).isEqualTo("24310/212");
        assertCountsMatchTheRowsWritten(out, "point");
    }

    /** A centroid-only write re-reads equal: mz() falls back to spectra_peaks, as for Numpress output. */
    @Test
    void aCentroidOnlyWriteReadsBackEqual(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.size()).isEqualTo(s.spectra.size()).isEqualTo(212);
            for (Spectrum source : s.spectra) {
                Spectrum back = r.getSpectrum(source.index()).orElseThrow();
                assertThat(back.mz()).as("m/z of %d", source.index()).isEqualTo(source.mz());
                assertThat(back.intensity()).as("intensity of %d", source.index()).isEqualTo(source.intensity());
                assertThat(back.peaks()).as("peaks of %d", source.index()).isEqualTo(source.peaks());
            }
        }
    }

    // ---- spectra built by hand ----------------------------------------------------------------------------------

    /**
     * Spectrum 4 of small.unpacked is CENTROID; here it keeps its arrays and loses its peak list (the SRM shape). Its
     * arrays go to spectra_peaks, and the reader builds the peak list from them. Written alone, it is spectrum 0
     * (WR-8).
     */
    @Test
    void arrayOnlyCentroidSpectrum(@TempDir Path tmp) {
        Spectrum four = spectrum(ForwardBackwardCorpusTest.readSource(SMALL), 4);
        assertThat(four.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        Spectrum srm = new Spectrum(four.description(), four.mz(), four.intensity(), List.of());
        int n = srm.mz().length;
        assertThat(n).isPositive();

        Path out = write(tmp, srm);

        assertThat(route(out, 0)).isEqualTo("data=- peaks=" + n + " number_of_data_points=- number_of_peaks=" + n);
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).isEqualTo(srm.mz());
            assertThat(back.intensity()).isEqualTo(srm.intensity());
            assertThat(back.peaks()).isEqualTo(copyOf(srm.mz(), srm.intensity()));
        }
    }

    /**
     * FN-16: the opt-in forward-backward harness compares the peaks of an array-only CENTROID spectrum with the
     * source's arrays, since WR-3 builds its re-read peak list from them; this runs that comparison in the default
     * build, on both layouts, and checks that it still catches a wrong peak. Written alone, it is spectrum 0 (WR-8).
     */
    @ParameterizedTest
    @EnumSource(ForwardBackwardCorpusTest.Layout.class)
    void theForwardBackwardPeakComparisonOfAnArrayOnlyCentroidSpectrum(ForwardBackwardCorpusTest.Layout layout,
                                                                       @TempDir Path tmp) {
        Spectrum four = spectrum(ForwardBackwardCorpusTest.readSource(SMALL), 4);
        Spectrum srm = new Spectrum(four.description(), four.mz(), four.intensity(), List.of());
        Path out = tmp.resolve("out.mzpeak");
        if (layout == ForwardBackwardCorpusTest.Layout.POINT) {
            MzPeakWriter.writeDirectory(out, List.of(srm), List.of(), List.of(), List.of(), FileMetadata.EMPTY);
        } else {
            MzPeakWriter.writeDirectoryNumpress(out, List.of(srm), List.of(), List.of(), List.of(),
                    FileMetadata.EMPTY, NumpressIntensityEncoding.SLOF);
        }
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            // the count comparison alone would report 0 source peaks against the re-read ones
            assertThat(srm.peaks()).isEmpty();
            assertThat(back.peaks()).hasSize(srm.mz().length);

            List<String> diffs = new ArrayList<>();
            ForwardBackwardCorpusTest.comparePeaks(diffs, layout, "spectrum[4]", srm, back);
            assertThat(diffs).isEmpty();

            double[] other = srm.intensity().clone();
            other[0] += 1000.0 * (1 + Arrays.stream(other).max().orElse(0));
            Spectrum wrong = new Spectrum(srm.description(), srm.mz(), other, List.of());
            List<String> caught = new ArrayList<>();
            ForwardBackwardCorpusTest.comparePeaks(caught, layout, "spectrum[4]", wrong, back);
            assertThat(caught).singleElement().asString().startsWith("spectrum[4].peaks.intensity");
        }
    }

    @Test
    void profileSpectrumWithoutPeaksStaysInSpectraData(@TempDir Path tmp) {
        Spectrum profile = new Spectrum(description(0, SignalContinuity.PROFILE), new double[] {100.0, 200.0, 300.0},
                new double[] {1.0, 2.0, 3.0}, List.of());

        assertThat(route(write(tmp, profile), 0)).isEqualTo("data=3 peaks=- number_of_data_points=3 number_of_peaks=-");
    }

    /** UNKNOWN and null continuity: arrays only, and arrays plus an exact copy of them as the peak list. */
    @Test
    void unknownAndNullContinuityStayInSpectraData(@TempDir Path tmp) {
        double[] mz = {100.0, 200.0, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        List<Spectrum> spectra = List.of(
                new Spectrum(description(0, SignalContinuity.UNKNOWN), mz, intensity, List.of()),
                new Spectrum(description(1, null), mz, intensity, List.of()),
                new Spectrum(description(2, SignalContinuity.UNKNOWN), mz, intensity, copyOf(mz, intensity)),
                new Spectrum(description(3, null), mz, intensity, copyOf(mz, intensity)));

        Path out = write(tmp, spectra);

        assertThat(route(out, 0)).isEqualTo("data=3 peaks=- number_of_data_points=3 number_of_peaks=-");
        assertThat(route(out, 1)).isEqualTo("data=3 peaks=- number_of_data_points=3 number_of_peaks=-");
        assertThat(route(out, 2)).isEqualTo("data=3 peaks=3 number_of_data_points=3 number_of_peaks=3");
        assertThat(route(out, 3)).isEqualTo("data=3 peaks=3 number_of_data_points=3 number_of_peaks=3");
    }

    /**
     * The peak list matches every row but the one whose m/z is non-finite, which {@link Spectrum} filters out: a peak
     * copy, but the reader cannot give a stored non-finite m/z back (its null fill, design §8.3, interpolates NaN, and
     * Numpress linear cannot encode it), so such a spectrum keeps both files: its arrays, non-finite row included, go
     * to spectra_data and its peak list to spectra_peaks. Re-read, {@code peaks()} equals the source's (m/z and
     * intensity; {@link CentroidPeak#index()} is the position in the stored peak list), the stored spectra_data rows
     * are {@code Arrays.equals} to the source, and so are the re-read arrays apart from a NaN m/z.
     */
    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void centroidPeakCopyWithANonFiniteMzRowKeepsBothFiles(double nonFinite, @TempDir Path tmp) {
        double[] mz = {100.0, nonFinite, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, copyOf(mz, intensity));
        assertThat(s.peaks()).hasSize(2);

        Path out = write(tmp, s);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(mzs(back.peaks())).containsExactly(mzs(s.peaks()));
            assertThat(intensities(back.peaks())).containsExactly(intensities(s.peaks()));
            assertThat(back.intensity()).isEqualTo(intensity);
            if (Double.isNaN(nonFinite)) {
                assertThat(back.mz()).hasSize(3);
                assertThat(back.mz()[0]).isEqualTo(100.0);
                assertThat(back.mz()[2]).isEqualTo(300.0);
            } else {
                assertThat(Arrays.equals(back.mz(), mz)).as("re-read m/z %s", Arrays.toString(back.mz())).isTrue();
            }
        }
        assertThat(WritePlanner.isPeakCopy(s)).isTrue();
        assertThat(WritePlanner.disposition(s)).isEqualTo(new SpectrumEntry(s, 0, true, true, false));
        assertThat(route(out, 0)).isEqualTo("data=3 peaks=2 number_of_data_points=3 number_of_peaks=2");
        double[][] data = storedRows(out.resolve(DATA), 0);
        assertThat(Arrays.equals(data[0], mz)).as("stored m/z %s", Arrays.toString(data[0])).isTrue();
        assertThat(Arrays.equals(data[1], intensity)).as("stored intensity %s", Arrays.toString(data[1])).isTrue();
        double[][] peaks = storedRows(out.resolve(PEAKS), 0);
        assertThat(peaks[0]).containsExactly(100.0, 300.0);
        assertThat(peaks[1]).containsExactly(1.0, 3.0);
    }

    /**
     * An array-only CENTROID spectrum (the SRM shape) with a non-finite m/z row stays in spectra_data, for the same
     * reason: in spectra_peaks the reader would turn that row into a peak.
     */
    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void arrayOnlyCentroidSpectrumWithANonFiniteMzRowStaysInSpectraData(double nonFinite, @TempDir Path tmp) {
        double[] mz = {100.0, nonFinite, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, List.of());

        Path out = write(tmp, s);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.peaks()).isEmpty();
            assertThat(back.intensity()).isEqualTo(intensity);
        }
        assertThat(WritePlanner.disposition(s)).isEqualTo(new SpectrumEntry(s, 0, true, false, false));
        assertThat(route(out, 0)).isEqualTo("data=3 peaks=- number_of_data_points=3 number_of_peaks=-");
        double[][] data = storedRows(out.resolve(DATA), 0);
        assertThat(Arrays.equals(data[0], mz)).as("stored m/z %s", Arrays.toString(data[0])).isTrue();
        assertThat(Arrays.equals(data[1], intensity)).as("stored intensity %s", Arrays.toString(data[1])).isTrue();
    }

    /**
     * The point layout writes a peak copy from {@code mz()}/{@code intensity()} to spectra_peaks only. Arrays whose m/z
     * is not ascending are re-sorted on the way (WR-14), each intensity with its m/z, so the re-read peaks equal the
     * source's apart from {@link CentroidPeak#index()}, which is the sorted position.
     */
    @Test
    void pointLayoutWritesAnUnsortedPeakCopyReSorted(@TempDir Path tmp) {
        double[] mz = {300.0, 100.0, 200.0};
        double[] intensity = {3.0, 1.0, 2.0};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, copyOf(mz, intensity));
        assertThat(mzs(s.peaks())).containsExactly(100.0, 200.0, 300.0);

        Path out = write(tmp, s);

        assertThat(ParquetFooter.read(new LocalInputFile(out.resolve(DATA))).rowCount()).isZero();
        assertThat(route(out, 0)).isEqualTo("data=- peaks=3 number_of_data_points=- number_of_peaks=3");
        double[][] peaks = storedRows(out.resolve(PEAKS), 0);
        assertThat(peaks[0]).containsExactly(100.0, 200.0, 300.0);
        assertThat(peaks[1]).containsExactly(1.0, 2.0, 3.0);
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).containsExactly(100.0, 200.0, 300.0);
            assertThat(back.intensity()).containsExactly(1.0, 2.0, 3.0);
            assertThat(mzs(back.peaks())).containsExactly(mzs(s.peaks()));
            assertThat(intensities(back.peaks())).containsExactly(intensities(s.peaks()));
            assertThat(back.peaks()).extracting(CentroidPeak::index).containsExactly(0, 1, 2);
        }
    }

    // ---- Numpress -----------------------------------------------------------------------------------------------

    /**
     * Numpress writes a peak copy from {@code mz()}/{@code intensity()} to spectra_peaks only, re-sorted by m/z when it
     * is not ascending (WR-14); number_of_peaks is the rows decoded from it.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void numpressWritesAnUnsortedPeakCopyReSorted(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        double[] mz = {300.0, 100.0, 200.0};
        double[] intensity = {5e8, 3e8, 4e8};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, copyOf(mz, intensity));
        assertThat(mzs(s.peaks())).containsExactly(100.0, 200.0, 300.0);

        Path out = writeNumpress(tmp, s, encoding);

        assertThat(decoded(out.resolve(DATA), 0)).isNull();
        SpectrumArrayStore.Arrays peaks = decoded(out.resolve(PEAKS), 0);
        assertThat(peaks.mz()).containsExactly(100.0, 200.0, 300.0);
        assertNumpressIntensities(peaks.intensity(), 3e8, 4e8, 5e8);
        Group spectrum = metadataRows(out).get(0L);
        assertThat(dataPoints(spectrum)).isNull();
        assertThat(numberOfPeaks(spectrum)).isEqualTo((long) peaks.mz().length);
    }

    static Stream<Arguments> numpressNonFiniteMzRows() {
        List<Arguments> cases = new ArrayList<>();
        for (NumpressIntensityEncoding encoding : NumpressIntensityEncoding.values()) {
            for (double nonFinite : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                cases.add(Arguments.of(encoding, nonFinite, true));
                cases.add(Arguments.of(encoding, nonFinite, false));
            }
        }
        return cases.stream();
    }

    /**
     * A CENTROID spectrum with a non-finite m/z row, as a peak copy and array-only, in Numpress: its arrays go to
     * spectra_data and its peak list, if any, to spectra_peaks, but Numpress linear cannot encode the non-finite m/z,
     * so the write is refused before anything is written (MZ-23, design Q27; on the base code the m/z was written as
     * another value). The point layout writes such a spectrum
     * ({@link #centroidPeakCopyWithANonFiniteMzRowKeepsBothFiles}).
     */
    @ParameterizedTest(name = "{0} {1} peak copy={2}")
    @MethodSource("numpressNonFiniteMzRows")
    void numpressCentroidSpectrumWithANonFiniteMzRow(NumpressIntensityEncoding encoding, double nonFinite,
                                                     boolean peakCopy, @TempDir Path tmp) {
        double[] mz = {100.0, nonFinite, 300.0};
        double[] intensity = {3e8, 4e8, 5e8};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity,
                peakCopy ? copyOf(mz, intensity) : List.of());
        assertThat(WritePlanner.disposition(s)).isEqualTo(new SpectrumEntry(s, 0, true, peakCopy, false));

        assertThatThrownBy(() -> writeNumpress(tmp, s, encoding))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 0: m/z " + nonFinite + " in spectra_data cannot be encoded with Numpress linear,"
                        + " which takes only finite m/z >= 0; use SignalEncoding.POINT");
        assertThat(tmp.resolve("out.mzpeak")).doesNotExist();
    }

    /**
     * A PROFILE spectrum whose arrays equal its peak list element by element, with one and with three points, keeps
     * both files: it is not a peak copy.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void profileSpectrumEqualToItsPeaksKeepsBothFiles(int points, @TempDir Path tmp) {
        double[] mz = new double[points];
        double[] intensity = new double[points];
        for (int i = 0; i < points; i++) {
            mz[i] = 100.0 * (i + 1);
            intensity[i] = 10.0 + i;
        }
        Spectrum s = new Spectrum(description(0, SignalContinuity.PROFILE), mz, intensity, copyOf(mz, intensity));

        Path out = write(tmp, s);

        assertThat(route(out, 0)).isEqualTo("data=" + points + " peaks=" + points + " number_of_data_points="
                + points + " number_of_peaks=" + points);
        try (MzPeakReader r = MzPeakReader.open(out, MzPeakReader.Options.defaults()
                .withSignalMode(MzPeakReader.SignalMode.BOTH))) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).isEqualTo(mz);
            assertThat(back.intensity()).isEqualTo(intensity);
            assertThat(back.peaks()).isEqualTo(s.peaks());
        }
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(out.resolve(DATA)), true,
                Map.of())) {
            SpectrumArrayStore.Arrays decoded = store.get(0);
            assertThat(decoded.mz()).isEqualTo(mz);
            assertThat(decoded.intensity()).isEqualTo(intensity);
        }
    }

    /** A CENTROID spectrum whose peak list is not a copy of its arrays keeps both files, so nothing is dropped. */
    @Test
    void centroidSpectrumThatIsNotAPeakCopyKeepsBothFiles(@TempDir Path tmp) {
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), new double[] {100.0, 200.0, 300.0},
                new double[] {1.0, 2.0, 3.0}, List.of(peak(150.0, 4.0, 0), peak(250.0, 5.0, 1)));

        assertThat(route(write(tmp, s), 0)).isEqualTo("data=3 peaks=2 number_of_data_points=3 number_of_peaks=2");
    }

    // ---- isPeakCopy and the disposition table -------------------------------------------------------------------

    /** {@link Spectrum} sorts {@code peaks()} by m/z, so a copy of unsorted arrays is matched through its indices. */
    @Test
    void aCopyOfUnsortedArraysIsAPeakCopy() {
        double[] mz = {300.0, 100.0, 200.0};
        double[] intensity = {3.0, 1.0, 2.0};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, copyOf(mz, intensity));
        assertThat(s.peaks()).extracting(CentroidPeak::index).containsExactly(1, 2, 0);

        assertThat(WritePlanner.isPeakCopy(s)).isTrue();
        assertThat(WritePlanner.disposition(s)).isEqualTo(new SpectrumEntry(s, 0, false, true, true));
        assertThat(WritePlanner.disposition(s).peakRows()).isEqualTo(3);
        assertThat(WritePlanner.disposition(s).dataRows()).isZero();
    }

    @Test
    void onlyCentroidContinuityMakesAPeakCopy() {
        double[] mz = {100.0, 200.0, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        for (SignalContinuity continuity : Arrays.asList(SignalContinuity.PROFILE, SignalContinuity.UNKNOWN, null)) {
            Spectrum s = new Spectrum(description(0, continuity), mz, intensity, copyOf(mz, intensity));
            assertThat(WritePlanner.isPeakCopy(s)).as("%s", continuity).isFalse();
            assertThat(WritePlanner.disposition(s)).as("%s", continuity)
                    .isEqualTo(new SpectrumEntry(s, 0, true, true, false));
        }
    }

    @Test
    void peakListsThatAreNotACopyOfTheArrays() {
        double[] mz = {100.0, 200.0, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        Map<String, List<CentroidPeak>> cases = new LinkedHashMap<>();
        cases.put("an intensity differs", List.of(peak(100.0, 1.0, 0), peak(200.0, 2.5, 1), peak(300.0, 3.0, 2)));
        cases.put("an m/z differs", List.of(peak(100.0, 1.0, 0), peak(200.5, 2.0, 1), peak(300.0, 3.0, 2)));
        cases.put("a finite row has no peak", List.of(peak(100.0, 1.0, 0), peak(300.0, 3.0, 2)));
        cases.put("two peaks name one row", List.of(peak(100.0, 1.0, 0), peak(100.0, 1.0, 0), peak(200.0, 2.0, 1),
                peak(300.0, 3.0, 2)));
        cases.put("an index is unset", List.of(peak(100.0, 1.0, -1), peak(200.0, 2.0, 1), peak(300.0, 3.0, 2)));
        cases.put("an index is past the arrays", List.of(peak(100.0, 1.0, 0), peak(200.0, 2.0, 1),
                peak(300.0, 3.0, 2), peak(400.0, 4.0, 3)));
        cases.forEach((what, peaks) -> {
            Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, peaks);
            assertThat(WritePlanner.isPeakCopy(s)).as(what).isFalse();
            assertThat(WritePlanner.disposition(s)).as(what).isEqualTo(new SpectrumEntry(s, 0, true, true, false));
        });

        // Values are compared exactly: 0.0 and -0.0 would be written differently.
        Spectrum signedZero = new Spectrum(description(0, SignalContinuity.CENTROID), mz, new double[] {1.0, 0.0, 3.0},
                List.of(peak(100.0, 1.0, 0), peak(200.0, -0.0, 1), peak(300.0, 3.0, 2)));
        assertThat(WritePlanner.isPeakCopy(signedZero)).isFalse();
    }

    /** CENTROID peaks without arrays are written from {@code peaks()}, arrays without peaks from the arrays. */
    @Test
    void centroidSpectraWithOnlyOneOfTheTwo() {
        double[] mz = {100.0, 200.0};
        double[] intensity = {1.0, 2.0};
        Spectrum peaksOnly = new Spectrum(description(0, SignalContinuity.CENTROID), new double[0], new double[0],
                copyOf(mz, intensity));
        Spectrum arraysOnly = new Spectrum(description(1, SignalContinuity.CENTROID), mz, intensity, List.of());
        Spectrum empty = new Spectrum(description(2, SignalContinuity.CENTROID), new double[0], new double[0],
                List.of());

        assertThat(WritePlanner.disposition(peaksOnly)).isEqualTo(new SpectrumEntry(peaksOnly, 0, false, true, false));
        assertThat(WritePlanner.disposition(arraysOnly)).isEqualTo(new SpectrumEntry(arraysOnly, 1, false, true, true));
        assertThat(WritePlanner.disposition(empty).data()).isFalse();
        assertThat(WritePlanner.disposition(empty).peaks()).isFalse();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static CentroidPeak peak(double mz, double intensity, int index) {
        return new CentroidPeak(mz, intensity, index);
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity) {
        return new SpectrumDescription(index, "synthetic=" + index, 1, 1.0, null, continuity, 0, 0, List.of(),
                List.of(), List.of());
    }

    /** The peak list a reader builds from {@code mz}/{@code intensity}: one peak per row, indexed by its row. */
    private static List<CentroidPeak> copyOf(double[] mz, double[] intensity) {
        List<CentroidPeak> peaks = new ArrayList<>(mz.length);
        for (int i = 0; i < mz.length; i++) {
            peaks.add(new CentroidPeak(mz[i], intensity[i], i));
        }
        return peaks;
    }

    private static Spectrum spectrum(Source source, long index) {
        return source.spectra.stream().filter(s -> s.index() == index).findFirst().orElseThrow();
    }

    private static Path write(Path tmp, Spectrum spectrum) {
        return write(tmp, List.of(spectrum));
    }

    private static Path write(Path tmp, List<Spectrum> spectra) {
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY);
        return out;
    }

    private static Path writeNumpress(Path tmp, Spectrum spectrum, NumpressIntensityEncoding encoding) {
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, List.of(spectrum), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY, encoding);
        return out;
    }

    /** The rows of spectrum {@code index} in a signal file, decoded by {@link SpectrumArrayStore}; null if none. */
    private static SpectrumArrayStore.Arrays decoded(Path file, long index) {
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true, Map.of())) {
            return store.get(index);
        }
    }

    private static double[] mzs(List<CentroidPeak> peaks) {
        return peaks.stream().mapToDouble(CentroidPeak::mz).toArray();
    }

    private static double[] intensities(List<CentroidPeak> peaks) {
        return peaks.stream().mapToDouble(CentroidPeak::intensity).toArray();
    }

    /**
     * Numpress intensities equal {@code expected} in order: PIC rounds to integers, which these are, and SLOF is
     * lossy (about 1e-4 relative). They are large because the vendored decoder rejects a PIC payload under 8 bytes.
     */
    private static void assertNumpressIntensities(double[] actual, double... expected) {
        assertThat(actual).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(actual[i]).as("intensity %d", i).isCloseTo(expected[i], withinPercentage(0.1));
        }
    }

    /**
     * The rows of spectrum {@code index} in spectra_data and spectra_peaks of the point layout, and the counts its
     * spectra_metadata row declares; {@code -} for none.
     */
    private static String route(Path out, long index) {
        Group spectrum = metadataRows(out).get(index);
        return "data=" + orDash(rowsPerSpectrum(out.resolve(DATA), "point").get(index))
                + " peaks=" + orDash(rowsPerSpectrum(out.resolve(PEAKS), "point").get(index))
                + " number_of_data_points=" + orDash(dataPoints(spectrum))
                + " number_of_peaks=" + orDash(numberOfPeaks(spectrum));
    }

    private static String orDash(Long value) {
        return value == null ? "-" : value.toString();
    }

    /** Total rows and the number of spectra with rows, as {@code rows/spectra}. */
    private static String summary(Map<Long, Long> rowsPerSpectrum) {
        return rowsPerSpectrum.values().stream().mapToLong(Long::longValue).sum() + "/" + rowsPerSpectrum.size();
    }

    /** Each spectrum's rows in a signal file whose rows are {@code group} structs; empty if the file is absent. */
    private static Map<Long, Long> rowsPerSpectrum(Path file, String group) {
        Map<Long, Long> rows = new TreeMap<>();
        if (Files.exists(file)) {
            ParquetGroups.forEach(file, row -> rows.merge(
                    ParquetGroups.optLong(ParquetGroups.optGroup(row, group), "spectrum_index"), 1L, Long::sum));
        }
        return rows;
    }

    /**
     * The spectrum facet of every spectra_metadata row, by index: the spectrum struct of the packed layout, or the row
     * itself in the split layout.
     */
    private static Map<Long, Group> metadataRows(Path out) {
        Map<Long, Group> rows = new TreeMap<>();
        ParquetGroups.forEach(out.resolve(METADATA), row -> {
            Group packed = ParquetGroups.optGroup(row, "spectrum");
            Group spectrum = packed != null ? packed : row;
            rows.put(ParquetGroups.optLong(spectrum, "index"), spectrum);
        });
        return rows;
    }

    /** The number_of_data_points of a {@link #metadataRows} facet in either layout; null if absent. */
    private static Long dataPoints(Group spectrum) {
        Long packed = ParquetGroups.optLong(spectrum, DATA_POINTS);
        return packed != null ? packed : ParquetGroups.optLong(spectrum, SPLIT_DATA_POINTS);
    }

    /** The number_of_peaks of a {@link #metadataRows} facet in either layout; null if absent. */
    private static Long numberOfPeaks(Group spectrum) {
        Long packed = ParquetGroups.optLong(spectrum, NUMBER_OF_PEAKS);
        return packed != null ? packed : ParquetGroups.optLong(spectrum, SPLIT_NUMBER_OF_PEAKS);
    }

    /** number_of_data_points and number_of_peaks of every spectrum equal its rows in spectra_data and spectra_peaks. */
    private static void assertCountsMatchTheRowsWritten(Path out, String group) {
        Map<Long, Long> dataPoints = new TreeMap<>();
        Map<Long, Long> peaks = new TreeMap<>();
        metadataRows(out).forEach((index, spectrum) -> {
            Long n = dataPoints(spectrum);
            if (n != null) {
                dataPoints.put(index, n);
            }
            Long p = numberOfPeaks(spectrum);
            if (p != null) {
                peaks.put(index, p);
            }
        });
        assertThat(dataPoints).as("number_of_data_points").isEqualTo(rowsPerSpectrum(out.resolve(DATA), group));
        assertThat(peaks).as("number_of_peaks").isEqualTo(rowsPerSpectrum(out.resolve(PEAKS), group));
    }

    /** The m/z and intensity rows of spectrum {@code index} in a point-layout signal file, in file order. */
    private static double[][] storedRows(Path file, long index) {
        List<double[]> rows = new ArrayList<>();
        ParquetGroups.forEach(file, row -> {
            Group point = ParquetGroups.optGroup(row, "point");
            if (ParquetGroups.optLong(point, "spectrum_index") == index) {
                rows.add(new double[] {ParquetGroups.optDouble(point, "mz"),
                        ParquetGroups.optDouble(point, "intensity")});
            }
        });
        double[][] out = new double[2][rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[0][i] = rows.get(i)[0];
            out[1][i] = rows.get(i)[1];
        }
        return out;
    }
}
