package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final String DATA_POINTS = "MS_1003060_number_of_data_points";
    private static final String NUMBER_OF_PEAKS = "MS_1003059_number_of_peaks";

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /**
     * Every spectrum of legacy small is read back with its centroids in both {@code mz()}/{@code intensity()} and
     * {@code peaks()}; the counts are rows/spectra of each file.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak"})
    void legacySmallRowsPerFile(String fixture, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(Fixtures.legacy(fixture));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertThat(summary(rowsPerSpectrum(out.resolve(DATA), "point"))).isEqualTo("243054/48");
        assertThat(summary(rowsPerSpectrum(out.resolve(PEAKS), "point"))).isEqualTo("25344/34");
        assertCountsMatchTheRowsWritten(out, "point");
    }

    @Test
    void legacySmallNumpressSpectraPerFile(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);

        assertThat(rowsPerSpectrum(out.resolve(DATA), "chunk")).hasSize(48);
        assertThat(rowsPerSpectrum(out.resolve(PEAKS), "chunk")).hasSize(34);
    }

    @Test
    void hasUvRowsPerFile(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertThat(summary(rowsPerSpectrum(out.resolve(DATA), "point"))).isEqualTo("24310/212");
        assertThat(summary(rowsPerSpectrum(out.resolve(PEAKS), "point"))).isEqualTo("24310/212");
        assertCountsMatchTheRowsWritten(out, "point");
    }

    // ---- spectra built by hand ----------------------------------------------------------------------------------

    /** Spectrum 4 of small.unpacked is CENTROID; here it keeps its arrays and loses its peak list. */
    @Test
    void arrayOnlyCentroidSpectrum(@TempDir Path tmp) {
        Spectrum four = spectrum(ForwardBackwardCorpusTest.readSource(SMALL), 4);
        assertThat(four.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        Spectrum srm = new Spectrum(four.description(), four.mz(), four.intensity(), List.of());
        int n = srm.mz().length;
        assertThat(n).isPositive();

        Path out = write(tmp, srm);

        assertThat(route(out, 4)).isEqualTo("data=" + n + " peaks=- number_of_data_points=" + n
                + " number_of_peaks=-");
        try (MzPeakReader r = MzPeakReader.open(out)) {
            Spectrum back = r.getSpectrum(4).orElseThrow();
            assertThat(back.mz()).isEqualTo(srm.mz());
            assertThat(back.intensity()).isEqualTo(srm.intensity());
            assertThat(back.peaks()).isEmpty();
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

    /** The peak list matches every row but the one whose m/z is NaN, which {@link Spectrum} filters out. */
    @Test
    void centroidPeakCopyWithANonFiniteMzRow(@TempDir Path tmp) {
        double[] mz = {100.0, Double.NaN, 300.0};
        double[] intensity = {1.0, 2.0, 3.0};
        Spectrum s = new Spectrum(description(0, SignalContinuity.CENTROID), mz, intensity, copyOf(mz, intensity));
        assertThat(s.peaks()).hasSize(2);

        Path out = write(tmp, s);

        assertThat(route(out, 0)).isEqualTo("data=3 peaks=2 number_of_data_points=3 number_of_peaks=2");
        double[][] stored = storedRows(out.resolve(DATA), 0);
        assertThat(Arrays.equals(stored[0], mz)).as("stored m/z %s", Arrays.toString(stored[0])).isTrue();
        assertThat(Arrays.equals(stored[1], intensity)).isTrue();
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

    // ---- helpers ------------------------------------------------------------------------------------------------

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

    /**
     * The rows of spectrum {@code index} in spectra_data and spectra_peaks of the point layout, and the counts its
     * spectra_metadata row declares; {@code -} for none.
     */
    private static String route(Path out, long index) {
        Group spectrum = metadataRows(out).get(index);
        return "data=" + orDash(rowsPerSpectrum(out.resolve(DATA), "point").get(index))
                + " peaks=" + orDash(rowsPerSpectrum(out.resolve(PEAKS), "point").get(index))
                + " number_of_data_points=" + orDash(ParquetGroups.optLong(spectrum, DATA_POINTS))
                + " number_of_peaks=" + orDash(ParquetGroups.optLong(spectrum, NUMBER_OF_PEAKS));
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

    /** The spectrum facet of every spectra_metadata row, by index. */
    private static Map<Long, Group> metadataRows(Path out) {
        Map<Long, Group> rows = new TreeMap<>();
        ParquetGroups.forEach(out.resolve(METADATA), row -> {
            Group spectrum = ParquetGroups.optGroup(row, "spectrum");
            rows.put(ParquetGroups.optLong(spectrum, "index"), spectrum);
        });
        return rows;
    }

    /** number_of_data_points and number_of_peaks of every spectrum equal its rows in spectra_data and spectra_peaks. */
    private static void assertCountsMatchTheRowsWritten(Path out, String group) {
        Map<Long, Long> dataPoints = new TreeMap<>();
        Map<Long, Long> peaks = new TreeMap<>();
        metadataRows(out).forEach((index, spectrum) -> {
            Long n = ParquetGroups.optLong(spectrum, DATA_POINTS);
            if (n != null) {
                dataPoints.put(index, n);
            }
            Long p = ParquetGroups.optLong(spectrum, NUMBER_OF_PEAKS);
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
