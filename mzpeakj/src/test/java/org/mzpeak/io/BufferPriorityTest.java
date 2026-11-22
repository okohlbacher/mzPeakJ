package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
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
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W6 (D13-25; spec signal-data.md "Buffer priority and naming", schema/array_index.json): the buffer priority of
 * the array index entries the writer records. Priority is assigned per logical array (array type, name and unit): the
 * first logical array of each array type in an index is primary, every further one secondary (signal-data.md
 * l.172-176), and the companions of a chunked array (chunk start, end and encoding, its transforms) have the priority
 * of its chunk values entry (l.130-133).
 * <ul>
 *   <li>Written from the legacy {@code small.unpacked} fixture in the point layout and in both Numpress intensity
 *       encodings: the m/z, time and intensity arrays are primary.
 *   <li>has_uv written split: the absorbance intensity column of its DAD chromatogram is secondary.
 *   <li>small.unpacked with spectrum 0 given the extra arrays {@code foo} and {@code bar} (both MS:1000786
 *       non-standard data arrays): {@code foo} is primary, {@code bar} secondary.
 *   <li>No written entry has a null priority, each array type has one primary logical array, and every written index
 *       validates against the specification's {@code array_index.json} with its enums ({@link SpecSchemaCheck}).
 * </ul>
 */
class BufferPriorityTest {

    private static final String MZ = "MS:1000514";
    private static final String TIME = "MS:1000595";
    private static final String INTENSITY = "MS:1000515";
    private static final String NON_STANDARD = "MS:1000786";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    void hasUvWrittenSplitHasASecondaryAbsorbanceIntensity() {
        Path out = write(source("has_uv.mzpeak"), "point");
        ArrayIndex chromatograms = indexes(out).stream()
                .filter(i -> i.entity() == SignalEntity.CHROMATOGRAM).findFirst().orElseThrow();

        assertThat(chromatograms.entries()).filteredOn(e -> INTENSITY.equals(e.arrayType()))
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", "MS:1000131", "primary"),
                        tuple("intensity_UO_0000269", "UO:0000269", "secondary"));
        assertThat(chromatograms.entries()).filteredOn(e -> NON_STANDARD.equals(e.arrayType()))
                .extracting(Entry::arrayName, Entry::bufferPriority)
                .containsExactly(tuple("ms level", "primary"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"point", "SLOF", "PIC"})
    void extrasFooAndBarGiveOnePrimaryAndOneSecondaryNonStandardArray(String encoding) {
        Path out = write(smallWithExtras(), encoding);
        BufferFormat format = encoding.equals("point") ? BufferFormat.POINT : BufferFormat.CHUNK_SECONDARY;

        List<ArrayIndex> spectra = indexes(out).stream().filter(i -> i.entity() == SignalEntity.SPECTRUM).toList();
        assertThat(spectra).hasSize(2);
        for (ArrayIndex index : spectra) {
            assertThat(index.entries()).filteredOn(e -> NON_STANDARD.equals(e.arrayType()))
                    .extracting(Entry::field, Entry::arrayName, Entry::format, Entry::bufferPriority)
                    .containsExactly(tuple("foo", "foo", format, "primary"), tuple("bar", "bar", format, "secondary"));
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            Spectrum zero = reader.getSpectrumAt(0);
            Spectrum given = smallWithExtras().spectra.get(0);
            assertThat(zero.arrays()).containsOnlyKeys("foo", "bar");
            assertThat(zero.arrays().get("foo")).containsExactly(given.arrays().get("foo"));
            assertThat(zero.arrays().get("bar")).containsExactly(given.arrays().get("bar"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small point", "small SLOF", "small PIC", "has_uv point", "has_uv SLOF", "extras point",
            "extras SLOF", "extras PIC"})
    void noWrittenEntryHasANullPriorityAndEveryIndexValidates(String write) throws IOException {
        String[] parts = write.split(" ");
        ForwardBackwardCorpusTest.Source s = switch (parts[0]) {
            case "small" -> source("small.unpacked.mzpeak");
            case "has_uv" -> source("has_uv.mzpeak");
            default -> smallWithExtras();
        };
        Path out = write(s, parts[1]);

        List<Member> members = members(out);
        assertThat(members).hasSizeGreaterThanOrEqualTo(3);
        for (Member m : members) {
            assertThat(SpecSchemaCheck.violations(m.name(), "array_index.json", m.json())).as(m.name()).isEmpty();
            JsonNode entries = MAPPER.readTree(m.json()).get("entries");
            assertThat(entries).as(m.name()).isNotEmpty();
            Map<String, List<List<String>>> primaryArrays = new HashMap<>();
            for (JsonNode entry : entries) {
                JsonNode priority = entry.get("buffer_priority");
                String what = m.name() + " " + entry.get("path").asText();
                assertThat(priority).as(what).isNotNull();
                assertThat(priority.isTextual()).as("%s priority %s", what, priority).isTrue();
                assertThat(priority.asText()).as(what).isIn("primary", "secondary");
                if (priority.asText().equals("primary")) {
                    List<String> array = Arrays.asList(entry.get("array_name").asText(),
                            entry.get("unit").isNull() ? null : entry.get("unit").asText());
                    List<List<String>> arrays = primaryArrays.computeIfAbsent(entry.get("array_type").asText(),
                            t -> new ArrayList<>());
                    if (!arrays.contains(array)) {
                        arrays.add(array);
                    }
                }
            }
            // one primary logical array per array type
            primaryArrays.forEach((type, arrays) -> assertThat(arrays).as("%s primary %s", m.name(), type)
                    .hasSize(1));
        }
    }

    /**
     * The builder: the chunk start, values and encoding of the m/z share its priority, as does a transform of the
     * intensity; a second intensity unit is a secondary array; a second value-bearing entry with the identity of an
     * earlier array (a non-standard array {@code same}, as two extra keys can name) holds another array, secondary,
     * whose companion follows it; the first array of another type is primary again.
     */
    @Test
    void theBuilderAssignsPriorityPerLogicalArray() {
        ArrayIndex index = ArrayIndex.builder(ArrayIndex.CHUNK_PREFIX, SignalEntity.SPECTRUM)
                .index("spectrum_index")
                .axis("mz_chunk_start", BufferFormat.CHUNK_START, null, ArrayIndex.MZ_UNIT)
                .axis("mz_chunk_values", BufferFormat.CHUNK_VALUES, null, ArrayIndex.MZ_UNIT)
                .axis("chunk_encoding", BufferFormat.CHUNK_ENCODING, null, ArrayIndex.MZ_UNIT)
                .intensity("intensity", BufferFormat.CHUNK_SECONDARY, null, ArrayIndex.COUNTS_UNIT)
                .intensity("intensity_slof", BufferFormat.CHUNK_TRANSFORM, "MS:1002314", ArrayIndex.COUNTS_UNIT)
                .intensity("intensity_UO_0000269", BufferFormat.CHUNK_SECONDARY, null, "UO:0000269")
                .extra("same", NON_STANDARD, "same", null, BufferFormat.CHUNK_SECONDARY)
                .extra("same_2", NON_STANDARD, "same", null, BufferFormat.CHUNK_SECONDARY)
                .extra("same_2_bytes", NON_STANDARD, "same", null, BufferFormat.CHUNK_TRANSFORM)
                .extra("snr", "MS:1000517", "signal to noise array", null, BufferFormat.CHUNK_SECONDARY)
                .build();

        assertThat(index.entries()).extracting(Entry::field, Entry::bufferPriority).containsExactly(
                tuple("spectrum_index", "primary"),
                tuple("mz_chunk_start", "primary"),
                tuple("mz_chunk_values", "primary"),
                tuple("chunk_encoding", "primary"),
                tuple("intensity", "primary"),
                tuple("intensity_slof", "primary"),
                tuple("intensity_UO_0000269", "secondary"),
                tuple("same", "primary"),
                tuple("same_2", "secondary"),
                tuple("same_2_bytes", "secondary"),
                tuple("snr", "primary"));
    }

    /** The schema check fails a null or unknown priority, so the validation above checks the enum. */
    @Test
    void theSchemaCheckRejectsANullOrAnUnknownPriority() throws IOException {
        Member member = members(write(source("small.unpacked.mzpeak"), "point")).get(0);
        ObjectNode root = (ObjectNode) MAPPER.readTree(member.json());
        assertThat(SpecSchemaCheck.violations("index", "array_index.json", root.toString())).isEmpty();

        ((ObjectNode) root.get("entries").get(2)).putNull("buffer_priority");
        assertThat(SpecSchemaCheck.violations("index", "array_index.json", root.toString())).isNotEmpty();
        ((ObjectNode) root.get("entries").get(2)).put("buffer_priority", "tertiary");
        assertThat(SpecSchemaCheck.violations("index", "array_index.json", root.toString())).isNotEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * small.unpacked with spectrum 0 given the extra arrays {@code foo} (its m/z plus one) and {@code bar} (its
     * intensities times two), in that order, parallel to its m/z.
     */
    private static ForwardBackwardCorpusTest.Source smallWithExtras() {
        ForwardBackwardCorpusTest.Source small = source("small.unpacked.mzpeak");
        ForwardBackwardCorpusTest.Source s = new ForwardBackwardCorpusTest.Source();
        s.spectra.addAll(small.spectra);
        Spectrum zero = small.spectra.get(0);
        Map<String, double[]> arrays = new LinkedHashMap<>();
        arrays.put("foo", Arrays.stream(zero.mz()).map(v -> v + 1).toArray());
        arrays.put("bar", Arrays.stream(zero.intensity()).map(v -> v * 2).toArray());
        s.spectra.set(0, new Spectrum(zero.description(), zero.mz(), zero.intensity(), zero.peaks(), arrays,
                Map.of()));
        s.chromatograms = small.chromatograms;
        s.wavelengthSpectra = small.wavelengthSpectra;
        s.scanSettings = small.scanSettings;
        s.fileMetadata = small.fileMetadata;
        return s;
    }

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
