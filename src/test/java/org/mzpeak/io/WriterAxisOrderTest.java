package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * The writer re-sorts a main axis that is not ascending (design §11.5, WR-14; spec signal-data.md:26-34, "Re-sort
 * unsorted ranked arrays"): the m/z of a spectrum, the time of a chromatogram and the wavelength axis. One stable
 * permutation, by {@link Double#compare} (NaN last), orders the axis and its intensity, the caller's arrays are left
 * alone, and the plan's {@link WriteReport} gets one warning per re-sorted entity. An axis whose finite values are
 * non-decreasing is written as given, non-finite rows in place. The warning names the caller's index; the only entity
 * of its kind is written as index 0 (WR-8), where the stored rows are read back.
 */
class WriterAxisOrderTest {

    private static final String DATA = "spectra_data.parquet";
    private static final String PEAKS = "spectra_peaks.parquet";
    private static final String CHROMATOGRAMS = "chromatograms_data.parquet";
    private static final String WAVELENGTH = "wavelength_spectra_data.parquet";
    private static final Options NUMPRESS_SLOF = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
            .withNumpressIntensity(NumpressIntensityEncoding.SLOF);

    // ---- re-sorted ----------------------------------------------------------------------------------------------

    @Test
    void reversedProfileSpectrumIsReSortedInPointLayout(@TempDir Path tmp) {
        Spectrum s = profile(7, new double[] {5, 4, 3, 2, 1}, new double[] {50, 40, 30, 20, 10});

        assertThat(warnings(Options.defaults(), List.of(s), List.of(), List.of()))
                .containsExactly("spectrum 7: main axis not ascending; re-sorted 5 rows");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY);

        double[][] stored = storedRows(out.resolve(DATA), "spectrum_index", "mz", 0);
        assertThat(stored[0]).containsExactly(1, 2, 3, 4, 5);
        assertThat(stored[1]).containsExactly(10, 20, 30, 40, 50);
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).containsExactly(1, 2, 3, 4, 5);
            assertThat(back.intensity()).containsExactly(10, 20, 30, 40, 50);
        }
    }

    /** Numpress linear m/z is near-lossless at its fixed point of 1e5, SLOF intensity about 1e-4 relative. */
    @Test
    void reversedProfileSpectrumIsReSortedInNumpressLayout(@TempDir Path tmp) {
        Spectrum s = profile(7, new double[] {5, 4, 3, 2, 1}, new double[] {50, 40, 30, 20, 10});

        assertThat(warnings(NUMPRESS_SLOF, List.of(s), List.of(), List.of()))
                .containsExactly("spectrum 7: main axis not ascending; re-sorted 5 rows");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                NumpressIntensityEncoding.SLOF);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).hasSize(5);
            assertThat(back.intensity()).hasSize(5);
            for (int i = 0; i < 5; i++) {
                assertThat(back.mz()[i]).as("m/z %d", i).isCloseTo(i + 1, within(1e-5));
                assertThat(back.intensity()[i]).as("intensity %d", i).isCloseTo(10.0 * (i + 1), withinPercentage(0.1));
            }
        }
    }

    @Test
    void reversedChromatogramAndWavelengthSpectrumAreReSorted(@TempDir Path tmp) {
        Spectrum ascending = profile(0, new double[] {100, 200}, new double[] {1, 2});
        Chromatogram tic = new Chromatogram(3, "TIC", "MS:1000235", new double[] {3.0, 2.0, 1.0},
                new double[] {30, 20, 10});
        WavelengthSpectrum uv = new WavelengthSpectrum(2, "uv=2", 1.5, null, new double[] {300, 250, 200},
                new double[] {0.3, -0.1, 0.2});

        assertThat(warnings(Options.defaults(), List.of(ascending), List.of(tic), List.of(uv))).containsExactly(
                "chromatogram 3: main axis not ascending; re-sorted 3 rows",
                "wavelength spectrum 2: main axis not ascending; re-sorted 3 rows");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(ascending), List.of(tic), List.of(uv), List.of(),
                FileMetadata.EMPTY);

        double[][] time = storedRows(out.resolve(CHROMATOGRAMS), "chromatogram_index", "time", 0);
        assertThat(time[0]).containsExactly(1.0, 2.0, 3.0);
        assertThat(time[1]).containsExactly(10, 20, 30);
        double[][] wavelength = storedRows(out.resolve(WAVELENGTH), "wavelength_spectrum_index", "wavelength", 0);
        assertThat(wavelength[0]).containsExactly(200, 250, 300);
        assertThat(wavelength[1]).containsExactly(0.2, -0.1, 0.3);
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Chromatogram c = r.getChromatogram(0).orElseThrow();
            assertThat(c.time()).containsExactly(1.0, 2.0, 3.0);
            assertThat(c.intensity()).containsExactly(10, 20, 30);
            WavelengthSpectrum w = r.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w.wavelength()).containsExactly(200, 250, 300);
            assertThat(w.intensity()).containsExactly(0.2, -0.1, 0.3);
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).containsExactly(100, 200);
            assertThat(back.intensity()).containsExactly(1, 2);
        }
    }

    /**
     * The permutation is stable (equal m/z keep their order) and orders by {@link Double#compare}: -Infinity first,
     * then the finite values, +Infinity, NaN last.
     */
    @Test
    void reSortIsStableAndPutsNaNLast(@TempDir Path tmp) {
        double[] mz = {2.0, Double.NaN, 1.0, 2.0, Double.POSITIVE_INFINITY, 1.0, Double.NEGATIVE_INFINITY};
        double[] intensity = {1, 2, 3, 4, 5, 6, 7};
        Spectrum s = profile(0, mz, intensity);

        assertThat(warnings(Options.defaults(), List.of(s), List.of(), List.of()))
                .containsExactly("spectrum 0: main axis not ascending; re-sorted 7 rows");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY);

        double[][] stored = storedRows(out.resolve(DATA), "spectrum_index", "mz", 0);
        double[] expectedMz = {Double.NEGATIVE_INFINITY, 1.0, 1.0, 2.0, 2.0, Double.POSITIVE_INFINITY, Double.NaN};
        assertThat(Arrays.equals(stored[0], expectedMz)).as("stored m/z %s", Arrays.toString(stored[0])).isTrue();
        assertThat(stored[1]).containsExactly(7, 3, 6, 1, 4, 5, 2);
    }

    /**
     * A CENTROID spectrum with arrays and no peak list (the SRM shape) writes its arrays to spectra_peaks; they are
     * re-sorted there too, and the reader builds its peaks from the sorted rows.
     */
    @Test
    void unsortedArrayOnlyCentroidSpectrumIsReSortedInSpectraPeaks(@TempDir Path tmp) {
        Spectrum s = new Spectrum(description(4, SignalContinuity.CENTROID), new double[] {300, 100, 200},
                new double[] {3, 1, 2}, List.of());

        assertThat(warnings(Options.defaults(), List.of(s), List.of(), List.of()))
                .containsExactly("spectrum 4: main axis not ascending; re-sorted 3 rows");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY);

        assertThat(out.resolve(DATA)).doesNotExist();
        double[][] stored = storedRows(out.resolve(PEAKS), "spectrum_index", "mz", 0);
        assertThat(stored[0]).containsExactly(100, 200, 300);
        assertThat(stored[1]).containsExactly(1, 2, 3);
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(0).orElseThrow();
            assertThat(back.mz()).containsExactly(100, 200, 300);
            assertThat(back.intensity()).containsExactly(1, 2, 3);
            assertThat(back.peaks()).containsExactly(new CentroidPeak(100, 1, 0), new CentroidPeak(200, 2, 1),
                    new CentroidPeak(300, 3, 2));
        }
    }

    // ---- written as given ---------------------------------------------------------------------------------------

    static Stream<double[]> axesWrittenAsGiven() {
        return Stream.of(
                new double[] {1, Double.NaN, 3},
                new double[] {1, 1, 2},
                new double[] {Double.NaN, 2, Double.POSITIVE_INFINITY, 2, 3},
                new double[] {Double.POSITIVE_INFINITY, 1, Double.NEGATIVE_INFINITY, 2});
    }

    /** Only the finite values are checked: when they do not decrease, every array is written exactly as given. */
    @ParameterizedTest
    @MethodSource("axesWrittenAsGiven")
    void axisWhoseFiniteValuesDoNotDecreaseIsWrittenAsGiven(double[] axis, @TempDir Path tmp) {
        double[] intensity = new double[axis.length];
        for (int i = 0; i < axis.length; i++) {
            intensity[i] = 10.0 * (i + 1);
        }
        Spectrum s = profile(0, axis, intensity);
        Chromatogram c = new Chromatogram(0, "TIC", "MS:1000235", axis, intensity);
        WavelengthSpectrum w = new WavelengthSpectrum(0, "uv=0", 1.0, null, axis, intensity);

        assertThat(warnings(Options.defaults(), List.of(s), List.of(c), List.of(w))).isEmpty();
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(c), List.of(w), List.of(), FileMetadata.EMPTY);

        assertStoredAsGiven(storedRows(out.resolve(DATA), "spectrum_index", "mz", 0), axis, intensity);
        assertStoredAsGiven(storedRows(out.resolve(CHROMATOGRAMS), "chromatogram_index", "time", 0), axis,
                intensity);
        assertStoredAsGiven(storedRows(out.resolve(WAVELENGTH), "wavelength_spectrum_index", "wavelength", 0), axis,
                intensity);
    }

    // ---- the caller's arrays ------------------------------------------------------------------------------------

    @Test
    void callerArraysAreNotModified(@TempDir Path tmp) {
        double[] mz = {5, 4, 3, 2, 1};
        double[] intensity = {5e8, 4e8, 3e8, 2e8, 1e8};
        double[] time = {3.0, Double.NaN, 1.0};
        double[] ticIntensity = {30, 20, 10};
        double[] wavelength = {300, 250, 200};
        double[] absorbance = {0.3, -0.1, 0.2};
        List<double[]> arrays = List.of(mz, intensity, time, ticIntensity, wavelength, absorbance);
        List<double[]> copies = new ArrayList<>();
        for (double[] a : arrays) {
            copies.add(a.clone());
        }
        List<Spectrum> spectra = List.of(profile(1, mz, intensity));
        List<Chromatogram> chromatograms = List.of(new Chromatogram(0, "TIC", "MS:1000235", time, ticIntensity));
        List<WavelengthSpectrum> uv = List.of(new WavelengthSpectrum(0, "uv=0", 1.0, null, wavelength, absorbance));

        MzPeakWriter.writeDirectory(tmp.resolve("point.mzpeak"), spectra, chromatograms, uv, List.of(),
                FileMetadata.EMPTY);
        MzPeakWriter.writeDirectoryNumpress(tmp.resolve("numpress.mzpeak"), spectra, chromatograms, uv, List.of(),
                FileMetadata.EMPTY, NumpressIntensityEncoding.PIC);
        MzPeakWriter.writeArchive(tmp.resolve("archive.mzpeak"), spectra, chromatograms, uv, List.of(),
                FileMetadata.EMPTY);

        for (int i = 0; i < arrays.size(); i++) {
            assertThat(Arrays.equals(arrays.get(i), copies.get(i)))
                    .as("caller array %d: %s", i, Arrays.toString(arrays.get(i))).isTrue();
        }
        assertThat(spectra.get(0).mz()).isSameAs(mz);
        assertThat(spectra.get(0).intensity()).isSameAs(intensity);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    static Stream<Path> fixtures() {
        return Stream.concat(Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy),
                Fixtures.CURRENT_NAMES.stream().map(Fixtures::current));
    }

    /**
     * The fixtures' main axes are already ascending: planning their write re-sorts nothing. (The diaPASEF fixtures
     * get the warning about their extra signal arrays, which the writer drops until WR-11.)
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void fixtureAxesAreAlreadyAscending(Path fixture) {
        Source s = ForwardBackwardCorpusTest.readSource(fixture);

        assertThat(WritePlanner.plan(s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, Options.defaults()).report().warnings())
                .filteredOn(w -> !w.contains("extra signal arrays")).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static List<String> warnings(Options options, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                         List<WavelengthSpectrum> wavelengthSpectra) {
        return WritePlanner.plan(spectra, chromatograms, wavelengthSpectra, List.of(), FileMetadata.EMPTY, options)
                .report().warnings();
    }

    private static Spectrum profile(long index, double[] mz, double[] intensity) {
        return new Spectrum(description(index, SignalContinuity.PROFILE), mz, intensity, List.of());
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity) {
        return new SpectrumDescription(index, "synthetic=" + index, 1, 1.0, null, continuity, 0, 0, List.of(),
                List.of(), List.of());
    }

    private static void assertStoredAsGiven(double[][] stored, double[] axis, double[] intensity) {
        assertThat(Arrays.equals(stored[0], axis)).as("stored axis %s", Arrays.toString(stored[0])).isTrue();
        assertThat(Arrays.equals(stored[1], intensity)).as("stored intensity %s", Arrays.toString(stored[1]))
                .isTrue();
    }

    /** The axis and intensity rows of entity {@code index} in a point-layout signal file, in file order. */
    private static double[][] storedRows(Path file, String indexField, String axisField, long index) {
        List<double[]> rows = new ArrayList<>();
        ParquetGroups.forEach(file, row -> {
            Group point = ParquetGroups.optGroup(row, "point");
            if (ParquetGroups.optLong(point, indexField) == index) {
                rows.add(new double[] {ParquetGroups.optDouble(point, axisField),
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
