package org.mzpeak.io;

import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads a spectrum signal file ({@code spectra_data.parquet} / {@code spectra_peaks.parquet}) in a streaming,
 * memory-bounded fashion. The columns are the ones the member's {@link SignalPlan} names: from its
 * {@code spectrum_array_index} footer, or inferred from the column names when it has none (as in mzPeakJ output).
 * Supports the {@code point} layout, delta-encoded {@code chunk} layout, and MS-Numpress chunks (linear m/z +
 * SLOF intensity); chunk rows are decoded by {@link ChunkRowDecoder}. A chunk it cannot decode (a coordinate grid,
 * an unknown {@code chunk_encoding}, Numpress intensity without Numpress m/z, or an unknown Numpress column with a
 * value) throws an {@link MzPeakException} instead of being dropped or misdecoded. Null-marked m/z is resolved by
 * {@link NullFill}.
 *
 * <p><b>Extra arrays:</b> every other array the plan lists ({@link SignalPlan#extras()}), such as diaPASEF's ion
 * mobility, is read parallel to the m/z, with null cells as NaN. A spectrum whose cells of an extra are all null
 * (NaN) gets no array for it. An extra that cannot be decoded for a spectrum (a chunk list whose length does not match
 * its m/z, a transform column that carries a value, cells that are not numbers) is dropped from that spectrum with a
 * WARNING, logged once per member and extra; the m/z and intensity are read as without it.
 *
 * <p><b>Streaming:</b> per-row-group min/max statistics of the plan's index column ({@code spectrum_index}) are
 * read at open time; {@link #get} decodes only the row group(s) covering the requested index, caching the last
 * decoded block run. Memory usage is bounded to one row-group run rather than the whole file.
 */
final class SpectrumArrayStore implements AutoCloseable {

    /**
     * The decoded arrays of one spectrum.
     *
     * @param extras the extra arrays, each parallel to {@code mz}, keyed as in {@link SignalPlan#extras()} and in
     *               plan order; empty when the member has none
     */
    record Arrays(double[] mz, double[] intensity, Map<String, double[]> extras) {

        Arrays(double[] mz, double[] intensity) {
            this(mz, intensity, Map.of());
        }
    }

    private static final System.Logger LOG = System.getLogger(SpectrumArrayStore.class.getName());

    private final ParquetFileReader reader;
    private final MessageType schema;
    private final SignalPlan plan;
    private final ChunkRowDecoder chunks;
    private final boolean reconstructProfile;
    private final Map<Long, double[]> deltaModels;
    private final long[] rgMin;
    private final long[] rgMax;
    private final ColumnIOFactory columnIO = new ColumnIOFactory();
    /** The member name in warnings. */
    private final String member;
    /** The extras a WARNING has been logged for. */
    private final Set<String> warnedExtras = new HashSet<>();

    // cache of the last-decoded contiguous block run [cachedFirst, cachedLast]
    private int cachedFirst = -1;
    private int cachedLast = -2;
    private Map<Long, Arrays> cache = Map.of();

    private SpectrumArrayStore(ParquetFileReader reader, MessageType schema, SignalPlan plan,
                               boolean reconstructProfile, Map<Long, double[]> deltaModels, long[] rgMin,
                               long[] rgMax, String member) {
        this.reader = reader;
        this.schema = schema;
        this.plan = plan;
        this.chunks = plan.layout() == SignalPlan.Layout.CHUNK ? new ChunkRowDecoder(plan) : null;
        this.reconstructProfile = reconstructProfile;
        this.deltaModels = deltaModels;
        this.rgMin = rgMin;
        this.rgMax = rgMax;
        this.member = member;
    }

    /**
     * Opens a spectrum signal member and builds its {@link SignalPlan} from the footer.
     *
     * @throws MzPeakException when the plan cannot be built: the array index names another main axis than m/z, or
     *                         two m/z value columns
     */
    static SpectrumArrayStore load(InputFile file, boolean reconstructProfile, Map<Long, double[]> deltaModels) {
        return load(file, "spectrum signal member", reconstructProfile, deltaModels);
    }

    /** {@link #load(InputFile, boolean, Map)} for the member named {@code member}, which warnings name. */
    static SpectrumArrayStore load(InputFile file, String member, boolean reconstructProfile,
                                   Map<Long, double[]> deltaModels) {
        try {
            ParquetFileReader reader = ParquetFileReader.open(file, ParquetGroups.readOptions());
            try {
                FileMetaData footer = reader.getFooter().getFileMetaData();
                MessageType schema = footer.getSchema();
                SignalPlan plan = SignalPlan.of(
                        ArrayIndex.of(footer.getKeyValueMetaData(), schema, SignalEntity.SPECTRUM), schema,
                        SignalEntity.SPECTRUM);
                String indexPath = plan.prefix() + "." + plan.indexField();
                List<BlockMetaData> blocks = reader.getRowGroups();
                long[] rgMin = new long[blocks.size()];
                long[] rgMax = new long[blocks.size()];
                for (int b = 0; b < blocks.size(); b++) {
                    long[] range = indexRange(blocks.get(b), indexPath);
                    rgMin[b] = range[0];
                    rgMax[b] = range[1];
                }
                return new SpectrumArrayStore(reader, schema, plan, reconstructProfile, deltaModels, rgMin, rgMax,
                        member);
            } catch (RuntimeException e) {
                try {
                    reader.close();
                } catch (IOException closing) {
                    e.addSuppressed(closing);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed opening spectrum signal file", e);
        }
    }

    /** The plan the member's columns are resolved with. */
    SignalPlan plan() {
        return plan;
    }

    /** min/max of the index column at {@code indexPath} for a block; full range if stats are missing. */
    private static long[] indexRange(BlockMetaData block, String indexPath) {
        for (ColumnChunkMetaData col : block.getColumns()) {
            if (col.getPath().toDotString().equals(indexPath)) {
                Statistics<?> stats = col.getStatistics();
                if (stats != null && stats.hasNonNullValue()
                        && stats.genericGetMin() instanceof Long min && stats.genericGetMax() instanceof Long max) {
                    return new long[] {min, max};
                }
            }
        }
        return new long[] {Long.MIN_VALUE, Long.MAX_VALUE}; // no usable stats -> always decode this block
    }

    synchronized Arrays get(long spectrumIndex) {
        int first = firstBlock(spectrumIndex);
        if (first < 0) {
            return null;
        }
        int last = lastBlock(spectrumIndex, first);
        if (first != cachedFirst || last != cachedLast) {
            cache = decodeBlocks(first, last);
            cachedFirst = first;
            cachedLast = last;
        }
        return cache.get(spectrumIndex);
    }

    boolean contains(long spectrumIndex) {
        return firstBlock(spectrumIndex) >= 0;
    }

    private int firstBlock(long index) {
        for (int b = 0; b < rgMin.length; b++) {
            if (index >= rgMin[b] && index <= rgMax[b]) {
                return b;
            }
        }
        return -1;
    }

    /** A spectrum may span consecutive blocks (its rows cross a row-group boundary); find the last such block. */
    private int lastBlock(long index, int first) {
        int last = first;
        while (last + 1 < rgMin.length && index >= rgMin[last + 1] && index <= rgMax[last + 1]) {
            last++;
        }
        return last;
    }

    private Map<Long, Arrays> decodeBlocks(int first, int last) {
        Map<Long, Arrays> result = new HashMap<>();
        Accumulator acc = new Accumulator(result, reconstructProfile, deltaModels, plan, chunks, this::dropExtra);
        try {
            for (int b = first; b <= last; b++) {
                PageReadStore pages = reader.readRowGroup(b);
                MessageColumnIO cio = columnIO.getColumnIO(schema);
                RecordReader<Group> rr = cio.getRecordReader(pages, new GroupRecordConverter(schema));
                long rows = pages.getRowCount();
                for (long i = 0; i < rows; i++) {
                    acc.accept(rr.read());
                }
            }
            acc.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading row groups " + first + ".." + last, e);
        }
        return result;
    }

    /** Logs that extra {@code key} is dropped from spectrum {@code si}, the first time for {@code key}. */
    private void dropExtra(String key, long si, String reason) {
        if (warnedExtras.add(key)) {
            LOG.log(Level.WARNING, () -> member + ": the extra array " + key + " of spectrum_index " + si + " "
                    + reason + "; it is dropped from that spectrum, whose m/z and intensity are kept (logged once per"
                    + " member and extra array)");
        }
    }

    /** Receives the extras the accumulator drops from a spectrum. */
    @FunctionalInterface
    private interface ExtraDrops {
        void drop(String key, long si, String reason);
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed closing spectrum signal reader", e);
        }
    }

    /** Buffers one spectrum's points/chunks and flushes when the sorted {@code spectrum_index} changes. */
    private static final class Accumulator implements Consumer<Group> {
        private final Map<Long, Arrays> result;
        private final boolean reconstructProfile;
        private final Map<Long, double[]> deltaModels;
        private final String prefix;
        private final String indexField;
        /** Point layout: the m/z values column, or null when the plan names none. */
        private final String mzField;
        /** Point layout: the plain intensity column, or null when the plan names none. */
        private final String intensityField;
        /** Chunk layout: the row decoder; null for the point layout. */
        private final ChunkRowDecoder chunks;
        /** The plan's extras: key to the point values column, or to null when only a transform column holds it. */
        private final Map<String, String> extraFields = new LinkedHashMap<>();
        private final ExtraDrops drops;
        private long current = Long.MIN_VALUE;
        private boolean started = false;
        private DoubleArrayBuilder mz = new DoubleArrayBuilder();
        private DoubleArrayBuilder intensity = new DoubleArrayBuilder();
        /** The current spectrum's extras not dropped so far, in plan order. */
        private Map<String, DoubleArrayBuilder> extras;

        Accumulator(Map<Long, Arrays> result, boolean reconstructProfile, Map<Long, double[]> deltaModels,
                    SignalPlan plan, ChunkRowDecoder chunks, ExtraDrops drops) {
            this.result = result;
            this.reconstructProfile = reconstructProfile;
            this.deltaModels = deltaModels;
            this.prefix = plan.prefix();
            this.indexField = plan.indexField();
            this.mzField = plan.axis().values() == null ? null : plan.axis().values().field();
            this.intensityField = plan.plainIntensity().map(Column::field).orElse(null);
            this.chunks = chunks;
            plan.extras().forEach((key, extra) -> extraFields.put(key,
                    extra.values() == null ? null : extra.values().field()));
            this.drops = drops;
            this.extras = newExtras();
        }

        /** Empty builders for every extra of the plan; an unmodifiable empty map when it has none. */
        private Map<String, DoubleArrayBuilder> newExtras() {
            if (extraFields.isEmpty()) {
                return Map.of();
            }
            Map<String, DoubleArrayBuilder> out = new LinkedHashMap<>();
            for (String key : extraFields.keySet()) {
                out.put(key, new DoubleArrayBuilder());
            }
            return out;
        }

        @Override
        public void accept(Group record) {
            Group row = ParquetGroups.optGroup(record, prefix);
            if (row == null) {
                throw new MzPeakException("spectrum signal row has neither 'point' nor 'chunk' struct");
            }
            long si = requireIndex(row);
            transition(si);
            if (chunks == null) {
                acceptPoint(row);
                return;
            }
            ChunkRowDecoder.Row decoded = chunks.decode(row, si);
            double[] axis = decoded.axis();
            double[] values = decoded.intensity();
            for (int i = 0; i < axis.length; i++) {
                mz.add(axis[i]);
                intensity.add(values[i]);
            }
            for (Iterator<Map.Entry<String, DoubleArrayBuilder>> it = extras.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, DoubleArrayBuilder> extra = it.next();
                String reason = decoded.dropped().get(extra.getKey());
                if (reason != null) {
                    it.remove();
                    drops.drop(extra.getKey(), si, reason);
                    continue;
                }
                for (double v : decoded.extras().get(extra.getKey())) {
                    extra.getValue().add(v);
                }
            }
        }

        private void acceptPoint(Group point) {
            Double mzVal = mzField == null ? null : ParquetGroups.optDouble(point, mzField);
            Double inVal = intensityField == null ? null : ParquetGroups.optDouble(point, intensityField);
            // Null-marked point: NaN m/z + 0 intensity placeholder, resolved on flush.
            mz.add(mzVal == null ? Double.NaN : mzVal);
            intensity.add(inVal == null ? 0.0 : inVal);
            for (Iterator<Map.Entry<String, DoubleArrayBuilder>> it = extras.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, DoubleArrayBuilder> extra = it.next();
                String field = extraFields.get(extra.getKey());
                Double v;
                try {
                    v = field == null ? null : ParquetGroups.optDouble(point, field);
                } catch (RuntimeException e) {
                    it.remove();
                    drops.drop(extra.getKey(), current, "cannot be read as a number (" + e.getMessage() + ")");
                    continue;
                }
                extra.getValue().add(v == null ? Double.NaN : v);
            }
        }

        private long requireIndex(Group g) {
            Long si = ParquetGroups.optLong(g, indexField);
            if (si == null) {
                throw new MzPeakException("signal row missing spectrum_index");
            }
            if (si < 0) {
                throw new MzPeakException("spectrum_index exceeds supported range (uint64 high bit set): " + si);
            }
            return si;
        }

        private void transition(long si) {
            if (!started) {
                started = true;
                current = si;
            } else if (si != current) {
                flush();
                current = si;
            }
        }

        void flush() {
            if (!started) {
                return;
            }
            double[] mzArray = mz.toArray();
            double[] intensityArray = intensity.toArray();
            Map<String, double[]> extraArrays = extraArrays();
            double[] model = deltaModels == null ? null : deltaModels.get(current);
            Arrays arrays;
            if (reconstructProfile && model != null && model.length > 0) {
                // Exact reconstruction: fill null-marked m/z by stepping with the stored polynomial delta model.
                NullFill.polynomialFill(mzArray, model);
                arrays = new Arrays(mzArray, intensityArray, extraArrays);
            } else {
                arrays = NullFill.finalizeArrays(mzArray, intensityArray, extraArrays, reconstructProfile);
            }
            if (result.putIfAbsent(current, arrays) != null) {
                throw new MzPeakException("signal file is not sorted by spectrum_index (index " + current
                        + " appears in multiple non-contiguous runs)");
            }
            mz = new DoubleArrayBuilder();
            intensity = new DoubleArrayBuilder();
            extras = newExtras();
        }

        /** The current spectrum's extras that have a value other than NaN; an unmodifiable empty map if none. */
        private Map<String, double[]> extraArrays() {
            Map<String, double[]> out = new LinkedHashMap<>();
            for (Map.Entry<String, DoubleArrayBuilder> extra : extras.entrySet()) {
                double[] values = extra.getValue().toArray();
                for (double v : values) {
                    if (!Double.isNaN(v)) {
                        out.put(extra.getKey(), values);
                        break;
                    }
                }
            }
            return out.isEmpty() ? Map.of() : out;
        }
    }
}
