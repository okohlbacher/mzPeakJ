package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MemberRewriter.Edit;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 (design D13-20): a spectrum reads the unit of the intensity column that supplied {@link Spectrum#intensity()}
 * as {@link Spectrum#intensityUnit()}, and the unit of the spectra_peaks intensity column as
 * {@link Spectrum#peakIntensityUnit()}, null when not declared.
 *
 * <p>The members are copies of the vendored fixtures' with a second intensity column {@code intensity_MS_1000132}
 * (array index entry MS:1000515 in MS:1000132, percent of base peak, buffer priority secondary) added: current
 * small.unpacked's {@code spectra_data} (point layout) and current small.chunked's (chunk layout). In both, spectrum 1
 * is cut to two points whose intensities [25, 100] are only in the percent column; every other row keeps its
 * {@code intensity} (MS:1000131, number of detector counts), but for the point layout's spectrum 7, whose odd rows
 * have their intensity moved to the percent column.
 */
class SpectrumIntensityUnitTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final Path SMALL_CHUNKED = Fixtures.current("small.chunked.mzpeak");
    private static final Path SMALL_NUMPRESS = Fixtures.current("small.numpress.mzpeak");
    private static final String DATA = "spectra_data.parquet";
    private static final String COUNTS = "MS:1000131";
    private static final String PERCENT = "MS:1000132";
    private static final String PERCENT_FIELD = "intensity_MS_1000132";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void pointSpectraReadTheUnitOfTheColumnThatSuppliedTheirIntensities() throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        double[] spectrum1Mz = rewritePoint(copy.resolve(DATA));

        // the member is one row group: the first read decodes every spectrum, spectrum 7 with its WARNING
        List<LogRecord> warnings = MemberRewrite.warnings(() -> {
            try (MzPeakReader reader = MzPeakReader.open(copy);
                 MzPeakReader original = MzPeakReader.open(SMALL)) {
                Spectrum s0 = reader.getSpectrum(0).orElseThrow();
                assertThat(s0.intensity()).isEqualTo(original.getSpectrum(0).orElseThrow().intensity());
                assertThat(s0.intensityUnit()).isEqualTo(COUNTS);
                assertThat(s0.peakIntensityUnit()).as("spectrum 0 has no peaks").isNull();

                Spectrum s1 = reader.getSpectrum(1).orElseThrow();
                assertThat(s1.mz()).containsExactly(spectrum1Mz);
                assertThat(s1.intensity()).containsExactly(25.0, 100.0);
                assertThat(s1.intensityUnit()).isEqualTo(PERCENT);

                Spectrum s7 = reader.getSpectrum(7).orElseThrow();
                assertThat(s7.intensity()).isEqualTo(original.getSpectrum(7).orElseThrow().intensity());
                assertThat(s7.intensityUnit()).as("intensities from columns of two units").isNull();

                // spectrum 2 has peaks only: its intensity() comes from spectra_peaks, as do its peaks
                Spectrum s2 = reader.getSpectrum(2).orElseThrow();
                assertThat(s2.hasPeaks()).isTrue();
                assertThat(s2.intensityUnit()).isEqualTo(COUNTS);
                assertThat(s2.peakIntensityUnit()).isEqualTo(COUNTS);
            }
        });
        assertThat(warnings).extracting(LogRecord::getMessage).containsExactly(
                "spectra_data.parquet: the intensities of spectrum_index 7 come from columns of different units ["
                        + COUNTS + ", " + PERCENT + "]; the intensity unit is left unset");
    }

    @Test
    void smallSpectraWithPeaksReadThePeakIntensityUnit() {
        try (MzPeakReader reader = MzPeakReader.open(SMALL)) {
            int withPeaks = 0;
            for (int i = 0; i < reader.size(); i++) {
                Spectrum s = reader.getSpectrumAt(i);
                if (s.hasPeaks()) {
                    withPeaks++;
                    assertThat(s.peakIntensityUnit()).as("spectrum %d", s.index()).isEqualTo(COUNTS);
                } else {
                    assertThat(s.peakIntensityUnit()).as("spectrum %d", s.index()).isNull();
                }
                assertThat(s.intensityUnit()).as("spectrum %d", s.index()).isEqualTo(COUNTS);
            }
            assertThat(withPeaks).isEqualTo(34);
        }
    }

    @Test
    void chunkSpectraReadTheUnitOfTheColumnThatSuppliedTheirIntensities() throws IOException {
        Path rewritten = tmp.resolve(DATA);
        double[] spectrum1Mz;
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            spectrum1Mz = rewriteChunk(source, rewritten);
        }
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED);
             SpectrumArrayStore original = SpectrumArrayStore.load(source.inputFile(DATA), true, null);
             SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(rewritten), true, null)) {
            assertThat(store.plan().layout()).isEqualTo(SignalPlan.Layout.CHUNK);
            SpectrumArrayStore.Arrays s0 = store.get(0);
            assertThat(s0.intensity()).isEqualTo(original.get(0).intensity());
            assertThat(s0.intensityUnit()).isEqualTo(COUNTS);

            SpectrumArrayStore.Arrays s1 = store.get(1);
            assertThat(s1.mz()).containsExactly(spectrum1Mz);
            assertThat(s1.intensity()).containsExactly(25.0, 100.0);
            assertThat(s1.intensityUnit()).isEqualTo(PERCENT);
        }
    }

    /** The unit of a SLOF intensity column is that of the spectra whose intensities it supplies. */
    @Test
    void numpressSpectraReadTheUnitOfTheirSlofColumn() {
        Path rewritten = tmp.resolve(DATA);
        try (MzPeakSource source = MzPeakSource.open(SMALL_NUMPRESS)) {
            MemberRewriter.rewrite(source.inputFile(DATA), rewritten, SignalEntity.SPECTRUM,
                    Map.of("chunk.intensity_numpress_slof_bytes", new Edit(PERCENT, null)));
        }
        try (MzPeakSource source = MzPeakSource.open(SMALL_NUMPRESS);
             SpectrumArrayStore original = SpectrumArrayStore.load(source.inputFile(DATA), true, null);
             SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(rewritten), true, null)) {
            assertThat(original.get(0).intensityUnit()).isEqualTo(COUNTS);
            SpectrumArrayStore.Arrays s0 = store.get(0);
            assertThat(s0.intensity()).isNotEmpty().isEqualTo(original.get(0).intensity());
            assertThat(s0.intensityUnit()).isEqualTo(PERCENT);
        }
    }

    /**
     * Adds {@code point.intensity_MS_1000132} to the point member {@code file}: spectrum 1 keeps its first two rows
     * with an m/z, their intensities 25 and 100 in the percent column only; the odd rows of spectrum 7 that have an
     * intensity have it in the percent column instead. Returns spectrum 1's two m/z.
     */
    private static double[] rewritePoint(Path file) throws IOException {
        MemberRewrite.Member m = MemberRewrite.read(file);
        GroupType point = m.schema.getType("point").asGroupType();
        List<Type> fields = new ArrayList<>(point.getFields());
        fields.add(Types.optional(PrimitiveTypeName.DOUBLE).named(PERCENT_FIELD));
        MessageType schema = new MessageType(m.schema.getName(), point.withNewFields(fields));
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);

        List<Group> rows = new ArrayList<>();
        double[] mz = new double[2];
        int kept1 = 0;
        int row7 = 0;
        for (Group row : m.rows) {
            Group p = row.getGroup("point", 0);
            long index = ParquetGroups.optLong(p, "spectrum_index");
            if (index == 1) {
                if (kept1 < 2 && ParquetGroups.has(p, "mz")) {
                    mz[kept1] = p.getDouble("mz", 0);
                    Group out = factory.newGroup();
                    Group q = out.addGroup("point");
                    q.add("spectrum_index", 1L);
                    q.add("mz", mz[kept1]);
                    q.add(PERCENT_FIELD, kept1 == 0 ? 25.0 : 100.0);
                    rows.add(out);
                    kept1++;
                }
                continue;
            }
            if (index == 7 && row7++ % 2 == 1 && ParquetGroups.has(p, "intensity")) {
                double intensity = p.getFloat("intensity", 0);
                rows.add(MemberRewrite.copyRow(row, schema, "point", g -> {
                    Group q = g.addGroup("point");
                    q.add("spectrum_index", index);
                    if (ParquetGroups.has(p, "mz")) {
                        q.add("mz", p.getDouble("mz", 0));
                    }
                    q.add(PERCENT_FIELD, intensity);
                    return g;
                }));
                continue;
            }
            rows.add(MemberRewrite.copyRow(row, schema, null, g -> g));
        }
        assertThat(kept1).isEqualTo(2);
        assertThat(row7).isEqualTo(10329);
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
        addPercentEntry(m, "point." + PERCENT_FIELD, "point");
        MemberRewrite.write(m, file);
        return mz;
    }

    /**
     * Writes small.chunked's spectra_data with {@code chunk.intensity_MS_1000132} added to {@code out}: spectrum 1 is
     * its first chunk row cut to the chunk start and one delta, with the intensities 25 and 100 in the percent column
     * only. Returns spectrum 1's two m/z.
     */
    private static double[] rewriteChunk(MzPeakSource source, Path out) throws IOException {
        Path in = out.resolveSibling("chunked." + DATA);
        try (var stream = source.inputFile(DATA).newStream()) {
            Files.write(in, stream.readAllBytes());
        }
        MemberRewrite.Member m = MemberRewrite.read(in);
        GroupType chunk = m.schema.getType("chunk").asGroupType();
        List<Type> fields = new ArrayList<>(chunk.getFields());
        fields.add(Types.optionalList()
                .optionalElement(PrimitiveTypeName.DOUBLE).named(PERCENT_FIELD));
        MessageType schema = new MessageType(m.schema.getName(), chunk.withNewFields(fields));
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);

        List<Group> rows = new ArrayList<>();
        double[] mz = null;
        for (Group row : m.rows) {
            Group c = row.getGroup("chunk", 0);
            long index = ParquetGroups.optLong(c, "spectrum_index");
            if (index != 1) {
                rows.add(MemberRewrite.copyRow(row, schema, null, g -> g));
                continue;
            }
            if (mz != null) {
                continue;
            }
            double start = c.getDouble("mz_chunk_start", 0);
            double delta = ParquetGroups.doubleListNullable(c, "mz_chunk_values")[0];
            mz = new double[] {start, start + delta};
            Group outRow = factory.newGroup();
            Group q = outRow.addGroup("chunk");
            q.add("spectrum_index", 1L);
            q.add("mz_chunk_start", start);
            q.add("mz_chunk_end", start + delta);
            q.addGroup("mz_chunk_values").addGroup("list").add("item", delta);
            q.add("chunk_encoding", c.getString("chunk_encoding", 0));
            Group percent = q.addGroup(PERCENT_FIELD);
            percent.addGroup("list").add("element", 25.0);
            percent.addGroup("list").add("element", 100.0);
            rows.add(outRow);
        }
        assertThat(mz).isNotNull();
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
        addPercentEntry(m, "chunk." + PERCENT_FIELD, "chunk_secondary");
        MemberRewrite.write(m, out);
        return mz;
    }

    /** Adds the array index entry of the percent column {@code path}, in {@code bufferFormat}, to {@code m}. */
    private static void addPercentEntry(MemberRewrite.Member m, String path, String bufferFormat) throws IOException {
        String key = SignalEntity.SPECTRUM.arrayIndexKey();
        ObjectNode index = (ObjectNode) MAPPER.readTree(m.keyValues.get(key));
        ObjectNode entry = ((ArrayNode) index.get("entries")).addObject();
        entry.put("context", "spectrum");
        entry.put("path", path);
        entry.put("data_type", "MS:1000523");
        entry.put("array_type", ArrayIndex.INTENSITY_ARRAY);
        entry.put("array_name", "intensity array");
        entry.put("unit", PERCENT);
        entry.put("buffer_format", bufferFormat);
        entry.putNull("transform");
        entry.putNull("data_processing_id");
        entry.put("buffer_priority", "secondary");
        entry.putNull("sorting_rank");
        m.keyValues.put(key, MAPPER.writeValueAsString(index));
    }
}
