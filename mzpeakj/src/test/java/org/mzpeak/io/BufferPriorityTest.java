package org.mzpeak.io;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 (D13-25; spec signal-data.md "Buffer priority and naming", schema/array_index.json): the buffer priority of
 * the array index entries the writer records.
 *
 * <p>Written from the legacy {@code small.unpacked} fixture in the point layout and in both Numpress intensity
 * encodings: the m/z, time and intensity arrays are primary, and the companions of a chunked array (chunk start, end
 * and encoding, its transforms) have the priority of its chunk values entry.
 */
class BufferPriorityTest {

    private static final String MZ = "MS:1000514";
    private static final String TIME = "MS:1000595";
    private static final String INTENSITY = "MS:1000515";

    /** The signal members by role. */
    private static final List<Role> ROLES = List.of(
            new Role(EntityType.SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.SPECTRUM),
            new Role(EntityType.SPECTRUM, DataKind.PEAKS, SignalEntity.SPECTRUM),
            new Role(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, SignalEntity.CHROMATOGRAM),
            new Role(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.WAVELENGTH_SPECTRUM));

    private record Role(EntityType entity, DataKind kind, SignalEntity signal) {
    }

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<String, ForwardBackwardCorpusTest.Source> SOURCES = new ConcurrentHashMap<>();

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"point", "SLOF", "PIC"})
    void smallMainArraysArePrimaryAndCompanionsHaveTheirChunkValuesPriority(String encoding) {
        Path out = write(source("small.unpacked.mzpeak"), encoding);
        List<ArrayIndex> indexes = indexes(out);
        assertThat(indexes).hasSize(3);
        boolean chunked = false;
        for (ArrayIndex index : indexes) {
            assertThat(index.entries()).filteredOn(e -> List.of(MZ, TIME, INTENSITY).contains(e.arrayType()))
                    .isNotEmpty().allSatisfy(e -> assertThat(e.bufferPriority()).as(e.path()).isEqualTo("primary"));
            for (Entry values : index.entries()) {
                if (values.format() != BufferFormat.CHUNK_VALUES) {
                    continue;
                }
                chunked = true;
                List<Entry> companions = companions(index, values);
                assertThat(companions).as("companions of %s", values.path()).hasSize(4)
                        .allSatisfy(c -> assertThat(c.bufferPriority()).as(c.path())
                                .isEqualTo(values.bufferPriority()));
            }
        }
        assertThat(chunked).isEqualTo(!encoding.equals("point"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The entries of the logical array of {@code values} other than itself: the chunk start, end, encoding and
     * transform entries of the same array type, name and unit.
     */
    static List<Entry> companions(ArrayIndex index, Entry values) {
        return index.entries().stream()
                .filter(e -> e != values && Objects.equals(e.arrayType(), values.arrayType())
                        && Objects.equals(e.arrayName(), values.arrayName())
                        && Objects.equals(e.unit(), values.unit()))
                .filter(e -> switch (e.format()) {
                    case CHUNK_START, CHUNK_END, CHUNK_ENCODING, CHUNK_TRANSFORM -> true;
                    default -> false;
                })
                .toList();
    }

    /** The array index of every signal member of {@code dataset}, in {@link #ROLES} order. */
    static List<ArrayIndex> indexes(Path dataset) {
        return members(dataset).stream().map(m -> ArrayIndex.parse(m.json(), m.signal())).toList();
    }

    /** A signal member and its array index footer JSON. */
    record Member(String name, SignalEntity signal, String json) {
    }

    /** Every signal member of {@code dataset} with its array index footer JSON, in {@link #ROLES} order. */
    static List<Member> members(Path dataset) {
        List<Member> out = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            for (Role role : ROLES) {
                manifest.find(role.entity(), role.kind()).ifPresent(member -> {
                    String json = ParquetFooter.read(source.inputFile(member.name())).keyValue()
                            .get(role.signal().arrayIndexKey());
                    assertThat(json).as("%s array index", member.name()).isNotNull();
                    out.add(new Member(member.name(), role.signal(), json));
                });
            }
        }
        return out;
    }

    private static ForwardBackwardCorpusTest.Source source(String fixture) {
        return SOURCES.computeIfAbsent(fixture, f -> ForwardBackwardCorpusTest.readSource(Fixtures.legacy(f)));
    }

    private Path write(ForwardBackwardCorpusTest.Source s, String encoding) {
        Options options = switch (encoding) {
            case "point" -> Options.defaults();
            case "SLOF" -> Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                    .withNumpressIntensity(NumpressIntensityEncoding.SLOF);
            case "PIC" -> Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                    .withNumpressIntensity(NumpressIntensityEncoding.PIC);
            default -> throw new IllegalArgumentException(encoding);
        };
        Path out = tmp.resolve(encoding + "-" + System.nanoTime() + ".mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, options);
        return out;
    }
}
