package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.SignalSnapshotTest.SignalDigest;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Numpress chunk layout {@link NumpressSignalWriter} writes for spectra_data and spectra_peaks (WR-10, design
 * §11.7), on legacy {@code small.unpacked} written with the full-form {@code writeDirectoryNumpress} overload in each
 * intensity encoding.
 *
 * <p>Characterization, recorded on the base code (agent-team/upstream-p1 aee4e0f) before WR-10 changed the schema:
 * <ul>
 *   <li>the decoded read-back through the public API, every spectrum's m/z, intensity, centroid m/z and centroid
 *       intensity digested like {@link SignalSnapshotTest} (raw IEEE-754 bits), in both reconstructProfile modes;
 *   <li>the chunking: each spectrum's points in stored order, 500 per chunk with a shorter last one (499 + 2 for the
 *       last two where a single point would be left over, which no spectrum of small.unpacked has; constructed
 *       spectra cover it), every row's chunk_encoding MS:1002312, and the inclusive chunk bounds the reference
 *       writes (chunk_series.rs:281-289): {@code mz_chunk_start} is the chunk's first input m/z and
 *       {@code mz_chunk_end} its last, not the next chunk's first.
 * </ul>
 *
 * <p>Schema conformance (WR-10): the byte lists are {@code list<uint8>} (INT32 annotated {@code intType(8, false)}),
 * every row carries an empty {@code mz_chunk_values} list, and a file holds only the intensity transform column of
 * its encoding, so the SLOF output has no PIC column and the PIC output no SLOF column.
 */
class NumpressConformanceTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final List<String> SIGNAL_MEMBERS = List.of("spectra_data.parquet", "spectra_peaks.parquet");
    private static final String VALUES_COLUMN = "mz_chunk_values";
    private static final String LINEAR_COLUMN = "mz_numpress_linear_bytes";
    private static final int CHUNK_SIZE = 500;
    /** Numpress linear with the writer's fixed point 1e5 rounds each m/z to 5e-6; this bound leaves room. */
    private static final double LINEAR_TOLERANCE = 1e-4;

    /**
     * {@code <encoding> reconstructProfile=<mode>} to the read-back digest recorded on the base code (aee4e0f).
     * Numpress chunks carry no null markers, so both modes read the same arrays. SLOF intensities are decoded with
     * {@link Math#exp}, whose last bit depends on the platform, so they are hashed rounded to float32
     * ({@link SignalDigest#float32}; SLOF digests recorded so on aarch64 and checked on x86_64); PIC intensities and
     * the linear m/z are exact and hashed raw.
     */
    private static final Map<String, String> READ_BACK = Map.of(
            "SLOF reconstructProfile=false", "entries=48 absent=none values=536796"
                    + " sha256=79e804866a84e38783df10d1bb1cdf78640c606070c95b8fc982cece31d14e22",
            "SLOF reconstructProfile=true", "entries=48 absent=none values=536796"
                    + " sha256=79e804866a84e38783df10d1bb1cdf78640c606070c95b8fc982cece31d14e22",
            "PIC reconstructProfile=false", "entries=48 absent=none values=536796"
                    + " sha256=d48b2d8b74bde14d859dcee4b43fcda6ffc8155cd7820ff9ff96578f7ed18e5f",
            "PIC reconstructProfile=true", "entries=48 absent=none values=536796"
                    + " sha256=d48b2d8b74bde14d859dcee4b43fcda6ffc8155cd7820ff9ff96578f7ed18e5f");

    private static Source source;

    static Stream<Arguments> readBackCases() {
        return Stream.of(NumpressIntensityEncoding.values())
                .flatMap(e -> Stream.of(Arguments.of(e, false), Arguments.of(e, true)));
    }

    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("readBackCases")
    void readBackMatchesTheBaseCode(NumpressIntensityEncoding encoding, boolean reconstructProfile,
                                    @TempDir Path tmp) {
        Path out = write(encoding, tmp);
        SignalDigest digest = new SignalDigest("");
        UnaryOperator<double[]> intensity = encoding == NumpressIntensityEncoding.SLOF ? SignalDigest::float32 : v -> v;
        try (MzPeakReader reader = MzPeakReader.open(out, reconstructProfile)) {
            for (Spectrum s : reader) {
                digest.entry(s.index(), s.mz(), intensity.apply(s.intensity()), peakMz(s),
                        intensity.apply(peakIntensity(s)));
            }
        }
        assertThat(digest.summary()).isEqualTo(READ_BACK.get(encoding + " reconstructProfile=" + reconstructProfile));
    }

    /**
     * D-5: SLOF intensities read back as the full doubles the Numpress decoder returns, not narrowed to float32 (the
     * digests above round them to float32 only because their last bit depends on {@link Math#exp}). On current
     * small.numpress, whose spectra_data stores SLOF intensities, at least one intensity of spectrum 0 is not
     * representable as a float, in both reconstructProfile modes.
     */
    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {false, true})
    void slofIntensitiesOfCurrentSmallNumpressAreNotNarrowedToFloat(boolean reconstructProfile) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.numpress.mzpeak"), reconstructProfile)) {
            double[] intensity = reader.getSpectrum(0).orElseThrow().intensity();
            assertThat(intensity).isNotEmpty();
            assertThat(Arrays.stream(intensity).filter(v -> (double) (float) v != v).count())
                    .as("intensities of spectrum 0 that float32 cannot hold").isPositive();
        }
    }

    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void chunksHold500InputPointsBoundedByTheirFirstAndLastMz(NumpressIntensityEncoding encoding,
                                                             @TempDir Path tmp) {
        Path out = write(encoding, tmp);
        Map<Long, Spectrum> input = new LinkedHashMap<>();
        for (Spectrum s : source().spectra) {
            input.put(s.index(), s);
        }
        for (String member : SIGNAL_MEMBERS) {
            Map<Long, List<Group>> bySpectrum = chunksBySpectrum(out.resolve(member));
            assertThat(bySpectrum).as("spectra in %s", member).isNotEmpty();
            bySpectrum.forEach((index, chunks) -> {
                List<double[]> decoded = new ArrayList<>();
                int n = 0;
                for (Group chunk : chunks) {
                    assertThat(ParquetGroups.optString(chunk, "chunk_encoding")).isEqualTo("MS:1002312");
                    byte[] bytes = ParquetGroups.byteList(chunk, LINEAR_COLUMN);
                    double[] mz = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length);
                    decoded.add(mz);
                    n += mz.length;
                }
                assertThat(decoded).extracting(mz -> mz.length).as("points per chunk of spectrum %d in %s", index,
                        member).isEqualTo(chunkSizes(n));
                List<double[]> candidates = matchingInputs(input.get(index), decoded, n);
                assertThat(candidates).as("input m/z of spectrum %d stored in %s", index, member).isNotEmpty();
                assertThat(candidates).as("chunk bounds of spectrum %d in %s", index, member)
                        .anySatisfy(mz -> {
                            int pos = 0;
                            for (int c = 0; c < chunks.size(); c++) {
                                int end = pos + decoded.get(c).length;
                                assertThat(ParquetGroups.optDouble(chunks.get(c), "mz_chunk_start"))
                                        .isEqualTo(mz[pos]);
                                assertThat(ParquetGroups.optDouble(chunks.get(c), "mz_chunk_end"))
                                        .as("inclusive end of chunk %d", c).isEqualTo(mz[end - 1]);
                                pos = end;
                            }
                        });
            });
        }
    }

    /**
     * The chunk sizes for constructed spectra of each length, in both intensity encodings: chunks of up to 500 points,
     * but never a chunk of one point unless the spectrum has one point, since the HEAD Python reader (pynumpress)
     * cannot decode the 12-byte linear payload of a single value. 501 points are written as 499 + 2 and 1001 as
     * 500 + 499 + 2. Every chunk is bounded by its first and last input m/z, and every spectrum reads back within the
     * Numpress tolerances, the one-point spectrum included, whose PIC payload is under 8 bytes (MZ-23).
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void noChunkHoldsASinglePointUnlessTheSpectrumHasOne(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        Map<Integer, List<Integer>> chunking = new LinkedHashMap<>();
        chunking.put(1, List.of(1));
        chunking.put(2, List.of(2));
        chunking.put(500, List.of(500));
        chunking.put(501, List.of(499, 2));
        chunking.put(502, List.of(500, 2));
        chunking.put(1000, List.of(500, 500));
        chunking.put(1001, List.of(500, 499, 2));
        List<Integer> sizes = new ArrayList<>(chunking.keySet());
        List<Spectrum> spectra = profiles(sizes);
        Path out = writeConstructed(spectra, encoding, tmp.resolve("constructed-" + encoding + ".mzpeak"));

        Map<Long, List<Group>> bySpectrum = chunksBySpectrum(out.resolve("spectra_data.parquet"));
        for (Spectrum s : spectra) {
            List<Integer> lengths = assertChunkBounds(s, bySpectrum.get(s.index()), encoding);
            assertThat(lengths).as("%s chunk sizes of a spectrum of %d points", encoding, s.mz().length)
                    .isEqualTo(chunking.get(s.mz().length));
        }
        assertReadBackWithinTolerance(out, spectra, encoding);
    }

    /**
     * A spectrum whose 1200 points come in descending m/z order is re-sorted as a whole before it is cut into chunks:
     * the decoded m/z ascend across all three chunks, each chunk is bounded by its first and last m/z of the sorted
     * input, and the read-back is the sorted input within the Numpress tolerances, in both intensity encodings.
     * Sorting each chunk on its own would leave the chunks in descending order.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void aReversedSpectrumIsReSortedAcrossItsChunks(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        Spectrum sorted = profile(0, 1200);
        Spectrum reversed = new Spectrum(sorted.description(), reversed(sorted.mz()), reversed(sorted.intensity()),
                List.of());
        Path out = writeConstructed(List.of(reversed), encoding, tmp.resolve("reversed-" + encoding + ".mzpeak"));

        List<Group> chunks = chunksBySpectrum(out.resolve("spectra_data.parquet")).get(0L);
        assertThat(assertChunkBounds(sorted, chunks, encoding)).as("%s chunk sizes", encoding)
                .containsExactly(500, 500, 200);
        double previous = Double.NEGATIVE_INFINITY;
        int point = 0;
        for (Group chunk : chunks) {
            byte[] bytes = ParquetGroups.byteList(chunk, LINEAR_COLUMN);
            for (double mz : MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length)) {
                assertThat(mz).as("%s m/z of point %d", encoding, point++).isGreaterThan(previous);
                previous = mz;
            }
        }
        assertReadBackWithinTolerance(out, List.of(sorted), encoding);
    }

    // ---- schema conformance (WR-10) -------------------------------------------------------------------------------

    /** The reference accepts Numpress bytes only as UInt8 (chunk_series.rs:430-464): pyarrow list<uint8>. */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void byteListsAreListsOfUint8(NumpressIntensityEncoding encoding, @TempDir Path tmp) throws IOException {
        Path out = write(encoding, tmp);
        for (String member : SIGNAL_MEMBERS) {
            GroupType chunk = chunkType(out.resolve(member));
            List<String> byteLists = new ArrayList<>();
            for (Type field : chunk.getFields()) {
                if (!field.getName().endsWith("_bytes")) {
                    continue;
                }
                byteLists.add(field.getName());
                PrimitiveType item = listItem(field);
                assertThat(item.getPrimitiveTypeName()).as("%s %s item", member, field.getName())
                        .isEqualTo(PrimitiveTypeName.INT32);
                assertThat(item.getLogicalTypeAnnotation()).as("%s %s item", member, field.getName())
                        .isEqualTo(LogicalTypeAnnotation.intType(8, false));
            }
            assertThat(byteLists).as("byte lists of %s", member)
                    .contains(LINEAR_COLUMN, intensityColumn(encoding));
        }
    }

    /**
     * chunk_values must be present exactly once (signal-data.md:110-124); the Numpress rows keep their m/z in the
     * linear transform, so the list is written empty, not null.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void everyRowCarriesAnEmptyMzChunkValuesList(NumpressIntensityEncoding encoding, @TempDir Path tmp)
            throws IOException {
        Path out = write(encoding, tmp);
        for (String member : SIGNAL_MEMBERS) {
            GroupType chunk = chunkType(out.resolve(member));
            assertThat(chunk.containsField(VALUES_COLUMN)).as("%s has %s", member, VALUES_COLUMN).isTrue();
            Type values = chunk.getType(VALUES_COLUMN);
            assertThat(values.isRepetition(Type.Repetition.OPTIONAL)).as("%s is optional", VALUES_COLUMN).isTrue();
            assertThat(listItem(values).getPrimitiveTypeName()).as("%s item", VALUES_COLUMN)
                    .isEqualTo(PrimitiveTypeName.DOUBLE);
            List<Group> rows = new ArrayList<>();
            chunksBySpectrum(out.resolve(member)).values().forEach(rows::addAll);
            assertThat(rows).as("rows of %s", member).isNotEmpty().allSatisfy(row -> {
                assertThat(ParquetGroups.has(row, VALUES_COLUMN)).as("%s is not null", VALUES_COLUMN).isTrue();
                assertThat(row.getGroup(VALUES_COLUMN, 0).getFieldRepetitionCount(0)).as("%s elements", VALUES_COLUMN)
                        .isZero();
            });
        }
    }

    /** Each column needs an array-index entry, so a file holds only the intensity transform the Options chose. */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void onlyTheChosenIntensityTransformIsAColumn(NumpressIntensityEncoding encoding, @TempDir Path tmp)
            throws IOException {
        Path out = write(encoding, tmp);
        for (String member : SIGNAL_MEMBERS) {
            assertThat(chunkType(out.resolve(member)).getFields()).extracting(Type::getName).as("columns of %s", member)
                    .containsExactly("spectrum_index", "mz_chunk_start", "mz_chunk_end", VALUES_COLUMN,
                            "chunk_encoding", LINEAR_COLUMN, intensityColumn(encoding));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * A PROFILE MS1 spectrum with {@code index} and {@code n} points: ascending m/z from 100 in steps of about 0.01,
     * and integer intensities between 2^24 and 2^28, which PIC stores exactly in 4 bytes each.
     */
    private static Spectrum profile(long index, int n) {
        double[] mz = new double[n];
        double[] intensity = new double[n];
        for (int i = 0; i < n; i++) {
            mz[i] = 100.0 + i * 0.01 + (i % 3) * 1e-3;
            intensity[i] = 20_000_000 + (i * 7919L) % 10_000;
        }
        SpectrumDescription d = new SpectrumDescription(index, "scan=" + (index + 1), 1, index / 10.0,
                Polarity.POSITIVE, SignalContinuity.PROFILE, n, 0, List.of(), List.of(), List.of());
        return new Spectrum(d, mz, intensity, List.of());
    }

    /**
     * The chunk sizes of a spectrum of {@code n} points: 500 each and the rest, except that a rest of one point after
     * a full chunk is written as 499 + 2.
     */
    private static List<Integer> chunkSizes(int n) {
        List<Integer> sizes = new ArrayList<>(Collections.nCopies(n / CHUNK_SIZE, CHUNK_SIZE));
        if (n % CHUNK_SIZE == 1 && n > 1) {
            sizes.set(sizes.size() - 1, CHUNK_SIZE - 1);
            sizes.add(2);
        } else if (n % CHUNK_SIZE != 0) {
            sizes.add(n % CHUNK_SIZE);
        }
        return sizes;
    }

    /** A copy of {@code values} in reverse order. */
    private static double[] reversed(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[values.length - 1 - i];
        }
        return out;
    }

    /** One {@link #profile} spectrum of each of {@code sizes} points, indexed 0..n-1 in that order. */
    private static List<Spectrum> profiles(List<Integer> sizes) {
        List<Spectrum> spectra = new ArrayList<>();
        for (int n : sizes) {
            spectra.add(profile(spectra.size(), n));
        }
        return spectra;
    }

    /** Writes {@code spectra} into {@code out} as a Numpress directory with {@code encoding} intensities. */
    private static Path writeConstructed(List<Spectrum> spectra, NumpressIntensityEncoding encoding, Path out) {
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(encoding));
        return out;
    }

    /**
     * Asserts that each of {@code chunks}, the spectra_data rows of {@code s} in file order, has the first and last
     * m/z of {@code s} it holds as {@code mz_chunk_start} and {@code mz_chunk_end}, and that together they hold every
     * point of {@code s}; returns the number of points of each chunk.
     */
    private static List<Integer> assertChunkBounds(Spectrum s, List<Group> chunks, NumpressIntensityEncoding encoding) {
        double[] mz = s.mz();
        List<Integer> lengths = new ArrayList<>();
        int pos = 0;
        for (Group chunk : chunks) {
            byte[] bytes = ParquetGroups.byteList(chunk, LINEAR_COLUMN);
            int length = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length).length;
            String what = encoding + " chunk " + lengths.size() + " of spectrum " + s.index();
            assertThat(length).as("points in %s", what).isPositive();
            assertThat(ParquetGroups.optDouble(chunk, "mz_chunk_start")).as("start of %s", what).isEqualTo(mz[pos]);
            assertThat(ParquetGroups.optDouble(chunk, "mz_chunk_end")).as("end of %s", what)
                    .isEqualTo(mz[pos + length - 1]);
            lengths.add(length);
            pos += length;
        }
        assertThat(pos).as("%s points stored of spectrum %d", encoding, s.index()).isEqualTo(mz.length);
        return lengths;
    }

    /**
     * Asserts that every spectrum of {@code expected} reads back from {@code out} with its m/z within Numpress linear
     * precision and its intensities within 1e-3 of {@code max(|x|, 1)}.
     */
    private static void assertReadBackWithinTolerance(Path out, List<Spectrum> expected,
                                                      NumpressIntensityEncoding encoding) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            for (Spectrum s : expected) {
                Spectrum back = reader.getSpectrumAt((int) s.index());
                String what = encoding + " spectrum " + s.index();
                assertThat(back.mz()).as("m/z of %s", what).hasSameSizeAs(s.mz());
                assertThat(back.intensity()).as("intensity of %s", what).hasSameSizeAs(s.intensity());
                for (int i = 0; i < s.mz().length; i++) {
                    assertThat(back.mz()[i]).as("m/z %d of %s", i, what).isCloseTo(s.mz()[i],
                            within(LINEAR_TOLERANCE));
                    double x = s.intensity()[i];
                    assertThat(back.intensity()[i]).as("intensity %d of %s", i, what).isCloseTo(x,
                            within(1e-3 * Math.max(Math.abs(x), 1.0)));
                }
            }
        }
    }

    private static String intensityColumn(NumpressIntensityEncoding encoding) {
        return encoding == NumpressIntensityEncoding.PIC ? "intensity_numpress_pic_bytes"
                : "intensity_numpress_slof_bytes";
    }

    /** The {@code chunk} struct of a signal member's schema. */
    private static GroupType chunkType(Path member) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(member),
                ParquetGroups.readOptions())) {
            return reader.getFooter().getFileMetaData().getSchema().getType("chunk").asGroupType();
        }
    }

    /** The element of a three-level LIST field ({@code <list> -> repeated list -> item}). */
    private static PrimitiveType listItem(Type field) {
        assertThat(field.getLogicalTypeAnnotation()).as("%s annotation", field.getName())
                .isEqualTo(LogicalTypeAnnotation.listType());
        GroupType repeated = field.asGroupType().getType(0).asGroupType();
        assertThat(repeated.isRepetition(Type.Repetition.REPEATED)).as("%s level 2", field.getName()).isTrue();
        return repeated.getType(0).asPrimitiveType();
    }

    private static synchronized Source source() {
        if (source == null) {
            source = ForwardBackwardCorpusTest.readSource(SMALL);
        }
        return source;
    }

    static Path write(NumpressIntensityEncoding encoding, Path tmp) {
        Source s = source();
        Path out = tmp.resolve(encoding + ".mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, encoding);
        return out;
    }

    /** The chunk structs of a signal member by spectrum index, each spectrum's in file order. */
    static Map<Long, List<Group>> chunksBySpectrum(Path member) {
        Map<Long, List<Group>> out = new LinkedHashMap<>();
        ParquetGroups.forEach(member, row -> {
            Group chunk = ParquetGroups.optGroup(row, "chunk");
            out.computeIfAbsent(ParquetGroups.optLong(chunk, "spectrum_index"), k -> new ArrayList<>()).add(chunk);
        });
        return out;
    }

    /**
     * The input arrays of {@code s} (its m/z and its centroid m/z) that the decoded chunks reproduce within Numpress
     * linear precision: the one the writer stored.
     */
    private static List<double[]> matchingInputs(Spectrum s, List<double[]> decoded, int n) {
        List<double[]> out = new ArrayList<>();
        for (double[] candidate : List.of(s.mz(), peakMz(s))) {
            if (candidate.length != n) {
                continue;
            }
            boolean close = true;
            int i = 0;
            for (double[] chunk : decoded) {
                for (double v : chunk) {
                    close &= Math.abs(v - candidate[i++]) <= LINEAR_TOLERANCE;
                }
            }
            if (close) {
                out.add(candidate);
            }
        }
        return out;
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
