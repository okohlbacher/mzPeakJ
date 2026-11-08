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
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.SignalSnapshotTest.SignalDigest;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Numpress chunk layout {@link NumpressSignalWriter} writes for spectra_data and spectra_peaks (WR-10, design
 * §11.7), on legacy {@code small.unpacked} written with the full-form {@code writeDirectoryNumpress} overload in each
 * intensity encoding.
 *
 * <p>Characterization, recorded on the base code (agent-team/upstream-p1 aee4e0f) before WR-10 changed the schema:
 * <ul>
 *   <li>the decoded read-back through the public API, every spectrum's m/z, intensity, centroid m/z and centroid
 *       intensity digested like {@link SignalSnapshotTest} (raw IEEE-754 bits), in both reconstructProfile modes;
 *   <li>the chunking: each spectrum's points in stored order, 500 per chunk with a shorter last one, every row's
 *       chunk_encoding MS:1002312, and the inclusive chunk bounds the reference writes (chunk_series.rs:281-289):
 *       {@code mz_chunk_start} is the chunk's first input m/z and {@code mz_chunk_end} its last, not the next
 *       chunk's first.
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
     * Numpress chunks carry no null markers, so both modes read the same arrays.
     */
    private static final Map<String, String> READ_BACK = Map.of(
            "SLOF reconstructProfile=false", "entries=48 absent=none values=536796"
                    + " sha256=756967bdb24235ca4115e4d69ac7338dfb0364b4b501afedc148e968a4a209de",
            "SLOF reconstructProfile=true", "entries=48 absent=none values=536796"
                    + " sha256=756967bdb24235ca4115e4d69ac7338dfb0364b4b501afedc148e968a4a209de",
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
        try (MzPeakReader reader = MzPeakReader.open(out, reconstructProfile)) {
            for (Spectrum s : reader) {
                digest.entry(s.index(), s.mz(), s.intensity(), peakMz(s), peakIntensity(s));
            }
        }
        assertThat(digest.summary()).isEqualTo(READ_BACK.get(encoding + " reconstructProfile=" + reconstructProfile));
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
                for (int c = 0; c < decoded.size(); c++) {
                    assertThat(decoded.get(c).length).as("points in chunk %d of spectrum %d in %s", c, index, member)
                            .isEqualTo(c < decoded.size() - 1 ? CHUNK_SIZE : n - CHUNK_SIZE * c);
                }
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
