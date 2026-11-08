package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.SignalSnapshotTest.SignalDigest;
import org.mzpeak.io.WriterSnapshotTest.Overload;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The value ranges of the mzPeak Numpress chunk layout (MZ-23, design §5.7).
 *
 * <p>Characterization, recorded on the base code (da74f60) before MZ-23 changed the PIC decode: the arrays
 * {@link MzPeakReader} reads back from the PIC outputs of legacy {@code small.unpacked} that
 * {@link WriterSnapshotTest} pins ({@link Overload#WRITE_DIRECTORY_NUMPRESS_PIC} and
 * {@link Overload#WRITE_DIRECTORY_NUMPRESS_PIC_PACKED}), every spectrum's m/z, intensity, centroid m/z and centroid
 * intensity digested like {@link SignalSnapshotTest} (raw IEEE-754 bits), in both reconstructProfile modes. Every
 * PIC payload of these outputs has 8 bytes or more (the base code decodes them) and every count is below 2^31, so the
 * PIC decode of MZ-23 must read them back unchanged.
 *
 * <p>The Numpress chunk layout has one intensity transform column and no plain list, so a chunk cannot fall back to
 * unencoded values, and on the base code the codecs wrote what they cannot encode as other values (linear: NaN as 0.0,
 * a first m/z of -0.038 as 42949.63; SLOF: -0.5 as 1.45e28, NaN as 0). The writer now refuses, before any I/O, an m/z
 * or intensity that is not finite or below 0, and with PIC an intensity whose rounded count {@code (long) (v + 0.5)}
 * exceeds 2147483647 (design Q26). The point layout writes the same spectra. A PIC payload under 8 bytes, which the
 * base code could not read back, now reads back.
 */
class NumpressRangeTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String DATA = "spectra_data.parquet";
    private static final String PIC_COLUMN = "intensity_numpress_pic_bytes";
    /** The index of every constructed spectrum, which each error names; written alone, it is written as 0. */
    private static final long INDEX = 3;
    /** Numpress linear with the writer's fixed point 1e5 rounds each m/z to 5e-6; this bound leaves room. */
    private static final double LINEAR_TOLERANCE = 1e-4;
    private static final double[] MZ = {100.0, 100.5};
    /** 2147483647.5 rounds to the count 2^31, one above the largest one PIC is written with. */
    private static final double ABOVE_PIC = 2147483647.5;

    /**
     * The read-back digest recorded on the base code (da74f60), the same for both outputs and both modes: the metadata
     * layout does not change the signal, and Numpress chunks carry no null markers.
     */
    private static final String PIC_READ_BACK = "entries=48 absent=none values=536796"
            + " sha256=d48b2d8b74bde14d859dcee4b43fcda6ffc8155cd7820ff9ff96578f7ed18e5f";

    private static Source source;

    static Stream<Arguments> picOutputs() {
        return Stream.of(Overload.WRITE_DIRECTORY_NUMPRESS_PIC, Overload.WRITE_DIRECTORY_NUMPRESS_PIC_PACKED)
                .flatMap(o -> Stream.of(Arguments.of(o, false), Arguments.of(o, true)));
    }

    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("picOutputs")
    void picOutputsOfLegacySmallReadBackAsOnTheBaseCode(Overload overload, boolean reconstructProfile,
                                                         @TempDir Path tmp) {
        Path out = tmp.resolve("out.mzpeak");
        overload.write.accept(out, source());
        SignalDigest digest = new SignalDigest("");
        try (MzPeakReader reader = MzPeakReader.open(out, reconstructProfile)) {
            for (Spectrum s : reader) {
                digest.entry(s.index(), s.mz(), s.intensity(), peakMz(s), peakIntensity(s));
            }
        }
        assertThat(digest.summary()).isEqualTo(PIC_READ_BACK);
    }

    // ---- PIC read-back ------------------------------------------------------------------------------------------

    /** Two counts take 3 bytes of PIC, which the base code refused to decode (MSNumpress.decode asks for 8). */
    @Test
    void aPicPayloadUnderEightBytesReadsBack(@TempDir Path tmp) {
        Path out = write(tmp.resolve("out.mzpeak"), profile(MZ, new double[] {3.0, 23.0}), pic());

        List<Group> chunks = NumpressConformanceTest.chunksBySpectrum(out.resolve(DATA)).get(0L);
        assertThat(chunks).hasSize(1);
        assertThat(ParquetGroups.byteList(chunks.get(0), PIC_COLUMN)).hasSizeLessThan(8);
        Spectrum back = readBack(out);
        assertThat(back.intensity()).containsExactly(3.0, 23.0);
        assertThat(back.mz()).containsExactly(MZ, within(LINEAR_TOLERANCE));
    }

    /** 2147483647.4 rounds to 2147483647, the largest count PIC is written with, and reads back as that count. */
    @Test
    void theLargestPicCountIsWrittenAndReadsBack(@TempDir Path tmp) {
        Path out = write(tmp.resolve("out.mzpeak"), profile(MZ, new double[] {3.0, 2147483647.4}), pic());

        assertThat(readBack(out).intensity()).containsExactly(3.0, 2147483647.0);
    }

    // ---- refused values -----------------------------------------------------------------------------------------

    /** A value the Numpress codecs cannot encode in a spectrum's arrays: the array and the value the error names. */
    record Unencodable(String name, double[] mz, double[] intensity, String array, double value) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Unencodable> unencodable() {
        return List.of(
                new Unencodable("intensity -0.5", MZ, new double[] {3.0, -0.5}, "intensity", -0.5),
                new Unencodable("intensity NaN", MZ, new double[] {3.0, Double.NaN}, "intensity", Double.NaN),
                new Unencodable("intensity +Infinity", MZ, new double[] {3.0, Double.POSITIVE_INFINITY}, "intensity",
                        Double.POSITIVE_INFINITY),
                new Unencodable("intensity -Infinity", MZ, new double[] {Double.NEGATIVE_INFINITY, 3.0}, "intensity",
                        Double.NEGATIVE_INFINITY),
                new Unencodable("m/z NaN", new double[] {100.0, Double.NaN, 100.5}, new double[] {3.0, 4.0, 5.0},
                        "m/z", Double.NaN),
                new Unencodable("m/z +Infinity", new double[] {100.0, 100.5, Double.POSITIVE_INFINITY},
                        new double[] {3.0, 4.0, 5.0}, "m/z", Double.POSITIVE_INFINITY),
                new Unencodable("first m/z -0.038", new double[] {-0.038, 100.0}, new double[] {3.0, 23.0}, "m/z",
                        -0.038));
    }

    static Stream<Arguments> unencodableByEncoding() {
        List<Arguments> cases = new ArrayList<>();
        for (NumpressIntensityEncoding encoding : NumpressIntensityEncoding.values()) {
            for (Unencodable u : unencodable()) {
                cases.add(Arguments.of(encoding, u));
            }
        }
        return cases.stream();
    }

    /**
     * Each value is refused in both intensity encodings by the plan, before any I/O: the error names the spectrum, the
     * array, the value, the member it would be written to and the point layout as the alternative, and neither a
     * directory nor an archive is created.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unencodableByEncoding")
    void aValueTheCodecsCannotEncodeIsRefusedBeforeAnyIo(NumpressIntensityEncoding encoding, Unencodable u,
                                                        @TempDir Path tmp) {
        Spectrum s = profile(u.mz(), u.intensity());
        String message = refusal(u.array(), u.value(), "spectra_data", encoding);

        assertRefused(s, numpress(encoding), message, tmp);
    }

    /**
     * 2147483647.5 rounds to the count 2^31: PIC refuses it naming SLOF and the point layout as alternatives, in both
     * containers, and creates no target. SLOF writes it.
     */
    @Test
    void aPicCountAbove2147483647IsRefused(@TempDir Path tmp) {
        Spectrum s = profile(MZ, new double[] {3.0, ABOVE_PIC});

        assertRefused(s, pic(), "spectrum 3: intensity 2.1474836475E9 in spectra_data cannot be encoded with Numpress"
                + " PIC, which takes only finite intensities >= 0 that round to at most 2147483647; use"
                + " NumpressIntensityEncoding.SLOF or SignalEncoding.POINT", tmp);
        Spectrum back = readBack(write(tmp.resolve("slof.mzpeak"), s, numpress(NumpressIntensityEncoding.SLOF)));
        assertThat(back.intensity()[1]).isCloseTo(ABOVE_PIC, within(1e-3 * ABOVE_PIC));
    }

    /**
     * The values checked are those written: a CENTROID peak copy goes to spectra_peaks from its arrays, and the peak
     * list of a PROFILE spectrum goes to spectra_peaks from {@code peaks()}; in both the error names spectra_peaks.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void valuesWrittenToSpectraPeaksAreChecked(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        double[] intensity = {3.0, -0.5};
        Spectrum copy = new Spectrum(description(SignalContinuity.CENTROID), MZ, intensity, peaks(MZ, intensity));
        assertThat(WritePlanner.disposition(copy).peaksFromArrays()).isTrue();
        assertRefused(copy, numpress(encoding), refusal("intensity", -0.5, "spectra_peaks", encoding),
                tmp.resolve("copy"));

        Spectrum peakList = new Spectrum(description(SignalContinuity.PROFILE), MZ, new double[] {3.0, 23.0},
                peaks(new double[] {-1.0, 100.0}, new double[] {3.0, 23.0}));
        assertThat(WritePlanner.disposition(peakList).peaksFromArrays()).isFalse();
        assertRefused(peakList, numpress(encoding), refusal("m/z", -1.0, "spectra_peaks", encoding),
                tmp.resolve("list"));

        Spectrum listOnly = new Spectrum(description(SignalContinuity.PROFILE), new double[0], new double[0],
                peaks(MZ, new double[] {3.0, ABOVE_PIC}));
        if (encoding == NumpressIntensityEncoding.PIC) {
            assertRefused(listOnly, numpress(encoding), "spectrum 3: intensity 2.1474836475E9 in spectra_peaks cannot"
                    + " be encoded with Numpress PIC, which takes only finite intensities >= 0 that round to at most"
                    + " 2147483647; use NumpressIntensityEncoding.SLOF or SignalEncoding.POINT", tmp.resolve("only"));
        } else {
            Spectrum back = readBack(write(tmp.resolve("only.mzpeak"), listOnly, numpress(encoding)));
            assertThat(back.peaks()).hasSize(2);
        }
    }

    /**
     * The bounds are accepted: m/z and intensity 0 (and -0.0), and with PIC the count 2147483647. A spectrum without
     * arrays or peaks has nothing to check.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void theBoundsAreAccepted(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        Spectrum s = profile(new double[] {-0.0, 0.0, 100.0}, new double[] {-0.0, 0.0, 2147483647.4});
        Spectrum empty = new Spectrum(new SpectrumDescription(0, "scan=1", 1, 0.5, Polarity.POSITIVE,
                SignalContinuity.PROFILE, 0, 0, List.of(), List.of(), List.of()), new double[0], new double[0],
                List.of());

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(empty, s), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                numpress(encoding));

        Spectrum back = readBack(out);
        assertThat(back.mz()).containsExactly(new double[] {0.0, 0.0, 100.0}, within(LINEAR_TOLERANCE));
        assertThat(back.intensity()[0]).isEqualTo(0.0);
        assertThat(back.intensity()[1]).isEqualTo(0.0);
        if (encoding == NumpressIntensityEncoding.PIC) {
            assertThat(back.intensity()[2]).isEqualTo(2147483647.0);
        }
    }

    /**
     * The point layout writes every refused spectrum, and each reads back as written, apart from the NaN m/z: the
     * reader takes a stored NaN m/z for its null placeholder and interpolates it (design §6.1 B-34).
     */
    @Test
    void thePointLayoutWritesTheRefusedSpectra(@TempDir Path tmp) {
        List<Unencodable> cases = new ArrayList<>(unencodable());
        cases.add(new Unencodable("PIC count 2^31", MZ, new double[] {3.0, ABOVE_PIC}, "intensity", ABOVE_PIC));
        for (int i = 0; i < cases.size(); i++) {
            Unencodable u = cases.get(i);
            Spectrum back = readBack(write(tmp.resolve(i + ".mzpeak"), profile(u.mz(), u.intensity()),
                    Options.defaults()));
            assertThat(Arrays.equals(back.intensity(), u.intensity())).as("intensity of %s: %s", u,
                    Arrays.toString(back.intensity())).isTrue();
            if (u.name().equals("m/z NaN")) {
                assertThat(back.mz()).hasSize(3);
                assertThat(back.mz()[0]).isEqualTo(100.0);
                assertThat(back.mz()[1]).as("interpolated m/z").isEqualTo(100.25);
                assertThat(back.mz()[2]).isEqualTo(100.5);
            } else {
                assertThat(Arrays.equals(back.mz(), u.mz())).as("m/z of %s: %s", u, Arrays.toString(back.mz()))
                        .isTrue();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Asserts that writing {@code s} with {@code options} throws {@link MzPeakException} with {@code message}: from
     * the plan, and from writeDirectory and writeArchive to new paths below {@code dir}, which do not exist
     * afterwards.
     */
    private static void assertRefused(Spectrum s, Options options, String message, Path dir) {
        assertThatThrownBy(() -> WritePlanner.plan(List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                options)).isExactlyInstanceOf(MzPeakException.class).hasMessage(message);
        Path directory = dir.resolve("refused.mzpeak");
        assertThatThrownBy(() -> write(directory, s, options))
                .isExactlyInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(directory).doesNotExist();
        Path archive = dir.resolve("refused-archive.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, List.of(s), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY, options)).isExactlyInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(archive).doesNotExist();
    }

    /** The error for a refused non-finite or negative {@code value} of {@code array} in {@code member}. */
    private static String refusal(String array, double value, String member, NumpressIntensityEncoding encoding) {
        String codec = array.equals("m/z") ? "Numpress linear, which takes only finite m/z >= 0"
                : encoding == NumpressIntensityEncoding.PIC
                ? "Numpress PIC, which takes only finite intensities >= 0 that round to at most 2147483647"
                : "Numpress SLOF, which takes only finite intensities >= 0";
        return "spectrum 3: " + array + " " + value + " in " + member + " cannot be encoded with " + codec
                + "; use SignalEncoding.POINT";
    }

    private static Options numpress(NumpressIntensityEncoding encoding) {
        return Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(encoding);
    }

    private static Options pic() {
        return numpress(NumpressIntensityEncoding.PIC);
    }

    /** Writes {@code s} alone into the directory {@code out} with {@code options}. */
    private static Path write(Path out, Spectrum s, Options options) {
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(), List.of(), List.of(), FileMetadata.EMPTY, options);
        return out;
    }

    /** The last spectrum of {@code out}, the one with {@link #INDEX} in each write of this class. */
    private static Spectrum readBack(Path out) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            return reader.getSpectrumAt(reader.size() - 1);
        }
    }

    /** A PROFILE MS1 spectrum with {@link #INDEX} and these arrays, without peaks. */
    private static Spectrum profile(double[] mz, double[] intensity) {
        return new Spectrum(description(SignalContinuity.PROFILE), mz, intensity, List.of());
    }

    private static SpectrumDescription description(SignalContinuity continuity) {
        return new SpectrumDescription(INDEX, "scan=" + (INDEX + 1), 1, 1.0, Polarity.POSITIVE, continuity, 0, 0,
                List.of(), List.of(), List.of());
    }

    /** One peak per row of {@code mz}/{@code intensity}, indexed by its row. */
    private static List<CentroidPeak> peaks(double[] mz, double[] intensity) {
        List<CentroidPeak> out = new ArrayList<>();
        for (int i = 0; i < mz.length; i++) {
            out.add(new CentroidPeak(mz[i], intensity[i], i));
        }
        return out;
    }

    private static synchronized Source source() {
        if (source == null) {
            source = ForwardBackwardCorpusTest.readSource(SMALL);
        }
        return source;
    }

    private static double[] peakMz(Spectrum s) {
        List<CentroidPeak> peaks = s.peaks();
        double[] out = new double[peaks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = peaks.get(i).mz();
        }
        return out;
    }

    private static double[] peakIntensity(Spectrum s) {
        List<CentroidPeak> peaks = s.peaks();
        double[] out = new double[peaks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = peaks.get(i).intensity();
        }
        return out;
    }
}
