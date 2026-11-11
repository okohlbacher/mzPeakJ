package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-3 (design §3.13): an extra array keeps its stored cell at a point whose m/z is null. A copy of current
 * small.unpacked whose spectra_data gets an extra column {@code point.probe} (array index entry MS:1000786, non-standard
 * data array, named {@code probe}, buffer format {@code point}) with a distinct value on every row, the null-marked
 * profile rows of spectrum 0 (null m/z and intensity) included, reads {@code arrays().get("probe")}:
 * <ul>
 *   <li>with {@code reconstructProfile=true}, parallel to the filled {@code mz()}: every position holds its stored
 *       cell, the filled positions too (not NaN, not interpolated);
 *   <li>with {@code reconstructProfile=false}, the null-marked positions are dropped from the extra as from
 *       {@code mz()}: the stored cells of the rows with an m/z, in stored order.
 * </ul>
 */
class NullFilledExtraArrayTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String DATA = "spectra_data.parquet";
    private static final String PROBE = "probe";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path tmp;

    private static Path copy;
    /** Per spectrum index, the stored probe cell of each of its rows, in stored order. */
    private static final Map<Long, List<Double>> PROBE_CELLS = new LinkedHashMap<>();
    /** Per spectrum index, whether each of its rows has an m/z, in stored order. */
    private static final Map<Long, List<Boolean>> HAS_MZ = new LinkedHashMap<>();

    @BeforeAll
    static void writeCopy() throws IOException {
        copy = MemberRewrite.copy(SMALL, tmp);
        Path file = copy.resolve(DATA);
        MemberRewrite.Member m = MemberRewrite.read(file);
        GroupType point = m.schema.getType("point").asGroupType();
        assertThat(point.containsField(PROBE)).isFalse();
        List<Type> fields = new ArrayList<>(point.getFields());
        fields.add(Types.optional(PrimitiveTypeName.DOUBLE).named(PROBE));
        MessageType schema = new MessageType(m.schema.getName(), point.withNewFields(fields));

        List<Group> rows = new ArrayList<>();
        for (int i = 0; i < m.rows.size(); i++) {
            Group row = m.rows.get(i);
            Group p = row.getGroup("point", 0);
            long index = ParquetGroups.optLong(p, "spectrum_index");
            double cell = index * 1_000_000.0 + PROBE_CELLS.computeIfAbsent(index, k -> new ArrayList<>()).size()
                    + 0.25;
            PROBE_CELLS.get(index).add(cell);
            HAS_MZ.computeIfAbsent(index, k -> new ArrayList<>()).add(ParquetGroups.has(p, "mz"));
            rows.add(MemberRewrite.copyRow(row, schema, null, g -> {
                g.getGroup("point", 0).add(PROBE, cell);
                return g;
            }));
        }
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);

        ObjectNode index = (ObjectNode) MAPPER.readTree(m.keyValues.get(SignalEntity.SPECTRUM.arrayIndexKey()));
        ObjectNode entry = ((ArrayNode) index.get("entries")).addObject();
        entry.put("context", "spectrum");
        entry.put("path", "point." + PROBE);
        entry.put("data_type", "MS:1000523");
        entry.put("array_type", "MS:1000786");
        entry.put("array_name", PROBE);
        entry.putNull("unit");
        entry.put("buffer_format", "point");
        entry.putNull("transform");
        entry.putNull("data_processing_id");
        entry.put("buffer_priority", "primary");
        entry.putNull("sorting_rank");
        m.keyValues.put(SignalEntity.SPECTRUM.arrayIndexKey(), MAPPER.writeValueAsString(index));
        MemberRewrite.write(m, file);

        // spectrum 0 has null-marked rows, which carry a probe cell but neither m/z nor intensity
        assertThat(HAS_MZ.get(0L)).hasSize(13589).contains(false);
        assertThat(HAS_MZ.get(0L).stream().filter(b -> !b).count()).isEqualTo(2376);
    }

    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void theProbeHoldsItsStoredCellsParallelToTheMz(boolean reconstructProfile) {
        try (MzPeakReader reader = MzPeakReader.open(copy, reconstructProfile);
             MzPeakReader original = MzPeakReader.open(SMALL, reconstructProfile)) {
            assertThat(reader.size()).isEqualTo(48);
            for (int ordinal = 0; ordinal < reader.size(); ordinal++) {
                Spectrum s = reader.getSpectrumAt(ordinal);
                Spectrum o = original.getSpectrumAt(ordinal);
                String what = "spectrum " + s.index() + " reconstructProfile=" + reconstructProfile;
                assertThat(s.mz()).as("m/z of %s", what).isEqualTo(o.mz());
                assertThat(s.intensity()).as("intensity of %s", what).isEqualTo(o.intensity());
                if (!PROBE_CELLS.containsKey(s.index())) {
                    // its m/z come from spectra_peaks, which has no probe
                    assertThat(s.arrays()).as("arrays of %s", what).isEmpty();
                    continue;
                }
                assertThat(s.arrays().keySet()).as("arrays of %s", what).containsExactly(PROBE);
                assertThat(s.arrays().get(PROBE)).as("probe of %s", what)
                        .isEqualTo(expected(s.index(), reconstructProfile));
            }
        }
    }

    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void atTheNullMarkedRowsOfSpectrum0TheProbeIsTheStoredCellOrDropped(boolean reconstructProfile) {
        List<Boolean> hasMz = HAS_MZ.get(0L);
        List<Double> cells = PROBE_CELLS.get(0L);
        try (MzPeakReader reader = MzPeakReader.open(copy, reconstructProfile)) {
            Spectrum s = reader.getSpectrum(0).orElseThrow();
            double[] probe = s.arrays().get(PROBE);
            assertThat(probe).hasSameSizeAs(s.mz());
            if (reconstructProfile) {
                assertThat(probe).hasSize(hasMz.size());
                int filled = 0;
                for (int i = 0; i < probe.length; i++) {
                    if (!hasMz.get(i)) {
                        filled++;
                        assertThat(probe[i]).as("probe at filled position %d", i).isNotNaN()
                                .isEqualTo(cells.get(i));
                    }
                }
                assertThat(filled).isEqualTo(2376);
            } else {
                assertThat(probe).hasSize(hasMz.size() - 2376);
                for (int i = 0; i < hasMz.size(); i++) {
                    if (!hasMz.get(i)) {
                        double dropped = cells.get(i);
                        assertThat(probe).as("cell %s of the null-marked row %d", dropped, i)
                                .doesNotContain(dropped);
                    }
                }
            }
        }
    }

    /** The stored probe cells of spectrum {@code index}: all of them, or those of the rows with an m/z. */
    private static double[] expected(long index, boolean reconstructProfile) {
        List<Double> cells = PROBE_CELLS.get(index);
        List<Boolean> hasMz = HAS_MZ.get(index);
        List<Double> kept = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (reconstructProfile || hasMz.get(i)) {
                kept.add(cells.get(i));
            }
        }
        return kept.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
