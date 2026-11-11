package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.ParquetOutput.SignalFile;
import org.mzpeak.io.WritePlan.SignalArrays;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.io.WritePlan.SpectrumSignal;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Numpress encoder's own guard (design §4.1, D-11): {@link NumpressSignalWriter#appendNumpressChunks} checks the
 * values of a spectrum with {@link NumpressRange#check}, the check the planner makes before any I/O, and throws the
 * same {@link MzPeakException} instead of encoding a value the codecs would write as another one (on the base code
 * b6aa051 it encoded -0.5 with SLOF as a symbol reading back as 1.45e28). The writes through {@link MzPeakWriter} are
 * still refused by the planner, before any I/O, in batch and in a session, and leave no target.
 *
 * <p>Oracle: spectrum 3 of current {@code small.unpacked}, a CENTROID spectrum whose arrays the writer writes to
 * spectra_data (its peak list is not a copy of them), with one value replaced.
 */
class NumpressEncoderGuardTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final int SPECTRUM = 3;
    private static final String SLOF = "Numpress SLOF, which takes only finite intensities >= 0";
    private static final String PIC = "Numpress PIC, which takes only finite intensities >= 0 that round to at most"
            + " 2147483647";
    private static final String LINEAR = "Numpress linear, which takes only finite m/z >= 0 that round to at most"
            + " 4294967295 at its fixed point 100000 (m/z 42949.67295)";

    private static Source source;

    /** A replaced value of spectrum 3, the encoding it is written with and the error it gets. */
    record Case(String name, boolean mz, int at, double value, NumpressIntensityEncoding encoding, String message) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                intensity(-0.5, NumpressIntensityEncoding.SLOF, SLOF, "SignalEncoding.POINT"),
                intensity(Double.NaN, NumpressIntensityEncoding.SLOF, SLOF, "SignalEncoding.POINT"),
                intensity(Double.POSITIVE_INFINITY, NumpressIntensityEncoding.SLOF, SLOF, "SignalEncoding.POINT"),
                new Case("SLOF m/z[1] NaN", true, 1, Double.NaN, NumpressIntensityEncoding.SLOF,
                        "spectrum 3: m/z NaN in spectra_data cannot be encoded with " + LINEAR
                                + "; use SignalEncoding.POINT"),
                intensity(-1.0, NumpressIntensityEncoding.PIC, PIC, "SignalEncoding.POINT"),
                intensity(2147483648.0, NumpressIntensityEncoding.PIC, PIC,
                        "NumpressIntensityEncoding.SLOF or SignalEncoding.POINT"))
                .map(Arguments::of);
    }

    private static Case intensity(double value, NumpressIntensityEncoding encoding, String codec, String alternative) {
        return new Case(encoding + " intensity[0] " + value, false, 0, value, encoding,
                "spectrum 3: intensity " + value + " in spectra_data cannot be encoded with " + codec + "; use "
                        + alternative);
    }

    /** (a, c) writeArchive refuses the spectrum naming spectrum 3, the value and spectra_data, and leaves no target. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void writeArchiveRefusesTheValue(Case c, @TempDir Path tmp) {
        Source src = source();
        List<Spectrum> spectra = new ArrayList<>(src.spectra);
        spectra.set(SPECTRUM, replaced(spectra.get(SPECTRUM), c));
        Path target = tmp.resolve("out.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeArchive(target, spectra, src.chromatograms, src.wavelengthSpectra,
                src.scanSettings, src.fileMetadata, numpress(c.encoding())))
                .isExactlyInstanceOf(MzPeakException.class).hasMessage(c.message());
        assertThat(target).doesNotExist();
    }

    /** (b, c) A session throws from addSpectrum(3), and close() leaves no target. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aSessionRefusesTheValueAtAddSpectrum(Case c, @TempDir Path tmp) {
        Source src = source();
        Path target = tmp.resolve("out.mzpeak");
        MzPeakWriter.Session session = MzPeakWriter.openArchive(target, src.fileMetadata, src.scanSettings,
                arrayKeys(src.spectra), numpress(c.encoding()));
        try {
            for (int i = 0; i < SPECTRUM; i++) {
                session.addSpectrum(src.spectra.get(i));
            }
            Spectrum bad = replaced(src.spectra.get(SPECTRUM), c);
            assertThatThrownBy(() -> session.addSpectrum(bad))
                    .isExactlyInstanceOf(MzPeakException.class).hasMessage(c.message());
        } finally {
            session.close();
        }
        assertThat(target).doesNotExist();
    }

    /**
     * (d) appendNumpressChunks, called directly with a spectrum the planner would refuse, throws the planner's error
     * and writes no row; on the base code it encoded the value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theEncoderRefusesTheValueAndWritesNoRow(Case c, @TempDir Path tmp) throws Exception {
        Spectrum s = replaced(source().spectra.get(SPECTRUM), c);
        SpectrumEntry entry = WritePlanner.disposition(s);
        assertThat(entry.data()).isTrue();
        SpectrumSignal signal = new SpectrumSignal(s, entry, SignalArrays.asGiven(s.mz(), s.intensity()));

        assertRefusedByTheEncoder(signal, false, c.encoding(), c.message(), tmp.resolve("spectra_data.parquet"));
    }

    /** (d) The same for the peak list a PROFILE spectrum writes to spectra_peaks from {@code peaks()}. */
    @Test
    void theEncoderChecksThePeakListItWritesToSpectraPeaks(@TempDir Path tmp) throws Exception {
        Spectrum s = new Spectrum(new SpectrumDescription(SPECTRUM, "scan=4", 1, 1.0, Polarity.POSITIVE,
                SignalContinuity.PROFILE, 0, 0, List.of(), List.of(), List.of()), new double[] {100.0, 100.5},
                new double[] {3.0, 23.0}, List.of(new CentroidPeak(100.0, 3.0, 0), new CentroidPeak(100.5, -0.5, 1)));
        SpectrumEntry entry = WritePlanner.disposition(s);
        assertThat(entry.peaks()).isTrue();
        assertThat(entry.peaksFromArrays()).isFalse();
        SpectrumSignal signal = new SpectrumSignal(s, entry, SignalArrays.asGiven(s.mz(), s.intensity()));

        assertRefusedByTheEncoder(signal, true, NumpressIntensityEncoding.SLOF, "spectrum 3: intensity -0.5 in"
                + " spectra_peaks cannot be encoded with " + SLOF + "; use SignalEncoding.POINT",
                tmp.resolve("spectra_peaks.parquet"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Asserts that appending {@code signal} to a new Numpress chunk file at {@code file} throws {@code message}, and
     * that the file, closed afterwards, holds no row.
     */
    private static void assertRefusedByTheEncoder(SpectrumSignal signal, boolean peaksFile,
                                                  NumpressIntensityEncoding encoding, String message, Path file)
            throws Exception {
        try (SignalFile out = NumpressSignalWriter.openNumpressChunks(file, List.of(), numpress(encoding))) {
            assertThatThrownBy(() -> NumpressSignalWriter.appendNumpressChunks(out, signal, List.of(), peaksFile,
                    encoding)).isExactlyInstanceOf(MzPeakException.class).hasMessage(message);
        }
        AtomicInteger rows = new AtomicInteger();
        ParquetGroups.forEach(file, row -> rows.incrementAndGet());
        assertThat(rows).hasValue(0);
    }

    /** {@code s} with the value of {@code c} in place of its m/z or intensity at {@code c.at()}. */
    private static Spectrum replaced(Spectrum s, Case c) {
        assertThat(s.index()).isEqualTo(SPECTRUM);
        assertThat(s.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        double[] mz = s.mz().clone();
        double[] intensity = s.intensity().clone();
        (c.mz() ? mz : intensity)[c.at()] = c.value();
        return new Spectrum(s.description(), mz, intensity, s.peaks(), s.arrays(), s.peakArrays());
    }

    /** The keys of the arrays of {@code spectra} other than m/z and intensity, as a session is opened with them. */
    private static Set<String> arrayKeys(List<Spectrum> spectra) {
        Set<String> keys = new LinkedHashSet<>();
        for (Spectrum s : spectra) {
            keys.addAll(s.arrays().keySet());
            keys.addAll(s.peakArrays().keySet());
        }
        return keys;
    }

    private static Options numpress(NumpressIntensityEncoding encoding) {
        return Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(encoding);
    }

    private static synchronized Source source() {
        if (source == null) {
            source = ForwardBackwardCorpusTest.readSource(SMALL);
        }
        return source;
    }
}
