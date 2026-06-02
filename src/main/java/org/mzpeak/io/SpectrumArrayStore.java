package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads a spectrum signal file ({@code spectra_data.parquet} / {@code spectra_peaks.parquet}) and groups its
 * rows by {@code spectrum_index} into parallel {@code double[]} m/z + intensity arrays. Supports the
 * {@code point} layout, the delta-encoded {@code chunk} layout, and MS-Numpress chunks.
 *
 * <p><b>Streaming:</b> the file is <em>not</em> read whole. On open we read per-row-group min/max statistics
 * for the {@code spectrum_index} column; {@link #get} decodes only the row group(s) covering the requested
 * index, caching the last-decoded contiguous block run. For a single-row-group file this is equivalent to
 * reading the file once; for a large multi-row-group file, memory is bounded to one block run rather than the
 * whole file.
 */
final class SpectrumArrayStore implements AutoCloseable {

    record Arrays(double[] mz, double[] intensity) {
    }

    private final ParquetFileReader reader;
    private final MessageType schema;
    private final boolean reconstructProfile;
    private final long[] rgMin;
    private final long[] rgMax;
    private final ColumnIOFactory columnIO = new ColumnIOFactory();

    // cache of the last-decoded contiguous block run [cachedFirst, cachedLast]
    private int cachedFirst = -1;
    private int cachedLast = -2;
    private Map<Long, Arrays> cache = Map.of();

    private SpectrumArrayStore(ParquetFileReader reader, MessageType schema, boolean reconstructProfile,
                               long[] rgMin, long[] rgMax) {
        this.reader = reader;
        this.schema = schema;
        this.reconstructProfile = reconstructProfile;
        this.rgMin = rgMin;
        this.rgMax = rgMax;
    }

    static SpectrumArrayStore load(InputFile file, boolean reconstructProfile) {
        try {
            ParquetFileReader reader = ParquetFileReader.open(file, ParquetGroups.readOptions());
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            List<BlockMetaData> blocks = reader.getRowGroups();
            long[] rgMin = new long[blocks.size()];
            long[] rgMax = new long[blocks.size()];
            for (int b = 0; b < blocks.size(); b++) {
                long[] range = indexRange(blocks.get(b));
                rgMin[b] = range[0];
                rgMax[b] = range[1];
            }
            return new SpectrumArrayStore(reader, schema, reconstructProfile, rgMin, rgMax);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed opening spectrum signal file", e);
        }
    }

    /** min/max of the {@code *.spectrum_index} column for a block; full range if stats are missing. */
    private static long[] indexRange(BlockMetaData block) {
        for (ColumnChunkMetaData col : block.getColumns()) {
            if (col.getPath().toDotString().endsWith("spectrum_index")) {
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
        Accumulator acc = new Accumulator(result, reconstructProfile);
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
        private long current = Long.MIN_VALUE;
        private boolean started = false;
        private DoubleArrayBuilder mz = new DoubleArrayBuilder();
        private DoubleArrayBuilder intensity = new DoubleArrayBuilder();

        Accumulator(Map<Long, Arrays> result, boolean reconstructProfile) {
            this.result = result;
            this.reconstructProfile = reconstructProfile;
        }

        @Override
        public void accept(Group record) {
            Group point = ParquetGroups.optGroup(record, "point");
            if (point != null) {
                acceptPoint(point);
                return;
            }
            Group chunk = ParquetGroups.optGroup(record, "chunk");
            if (chunk != null) {
                acceptChunk(chunk);
                return;
            }
            throw new MzPeakException("spectrum signal row has neither 'point' nor 'chunk' struct");
        }

        private void acceptPoint(Group point) {
            long si = requireIndex(point);
            transition(si);
            Double mzVal = ParquetGroups.optDouble(point, "mz");
            Double inVal = ParquetGroups.optDouble(point, "intensity");
            // Null-marked point: NaN m/z + 0 intensity placeholder, resolved on flush.
            mz.add(mzVal == null ? Double.NaN : mzVal);
            intensity.add(inVal == null ? 0.0 : inVal);
        }

        private void acceptChunk(Group chunk) {
            long si = requireIndex(chunk);
            transition(si);
            if (ParquetGroups.has(chunk, "mz_numpress_linear_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_pic_bytes")) {
                acceptNumpressChunk(chunk, si);
                return;
            }
            double start = orZero(ParquetGroups.optDouble(chunk, "mz_chunk_start"));
            String encoding = ParquetGroups.optString(chunk, "chunk_encoding");
            double[] rawMz = ParquetGroups.doubleListNullable(chunk, "mz_chunk_values");
            double[] chunkMz = decodeChunkMz(rawMz, start, encoding);
            double[] chunkIntensity = ParquetGroups.doubleListNullable(chunk, "intensity");
            // A chunk whose intensity list is one longer than its m/z values emits chunk_start as a real point
            // (its intensity is intensity[0]); the delta-decoded values then pair with intensity[1..]. A chunk
            // with equal lengths pairs its values one-to-one with intensities and has no separate start point.
            int extra = chunkIntensity.length - chunkMz.length;
            if (extra == 1) {
                mz.add(start);
                intensity.add(nonNullIntensity(chunkIntensity[0]));
                for (int i = 0; i < chunkMz.length; i++) {
                    mz.add(chunkMz[i]);
                    intensity.add(nonNullIntensity(chunkIntensity[i + 1]));
                }
            } else if (extra == 0) {
                for (int i = 0; i < chunkMz.length; i++) {
                    mz.add(chunkMz[i]);
                    intensity.add(nonNullIntensity(chunkIntensity[i]));
                }
            } else {
                throw new MzPeakException("chunk intensity length (" + chunkIntensity.length
                        + ") must equal m/z values (" + chunkMz.length + ") or values+1, for spectrum_index " + si);
            }
        }

        /**
         * Numpress chunk: m/z is MS-Numpress linear, intensity is MS-Numpress SLOF (or PIC). These encode the
         * full absolute arrays directly — no chunk_start, no delta, no null-marking — so we decode and append 1:1.
         */
        private void acceptNumpressChunk(Group chunk, long si) {
            if (!ParquetGroups.has(chunk, "mz_numpress_linear_bytes")) {
                throw new MzPeakException("Numpress chunk without mz_numpress_linear_bytes is not supported "
                        + "(spectrum_index " + si + ")");
            }
            double[] chunkMz = decodeNumpress(chunk, "mz_numpress_linear_bytes",
                    MSNumpress.ACC_NUMPRESS_LINEAR, si, "m/z");
            double[] chunkIntensity;
            if (ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")) {
                chunkIntensity = decodeNumpress(chunk, "intensity_numpress_slof_bytes",
                        MSNumpress.ACC_NUMPRESS_SLOF, si, "intensity");
            } else if (ParquetGroups.has(chunk, "intensity_numpress_pic_bytes")) {
                chunkIntensity = decodeNumpress(chunk, "intensity_numpress_pic_bytes",
                        MSNumpress.ACC_NUMPRESS_PIC, si, "intensity");
            } else {
                throw new MzPeakException("Numpress chunk missing an intensity buffer for spectrum_index " + si);
            }
            if (chunkMz.length != chunkIntensity.length) {
                throw new MzPeakException("Numpress chunk m/z (" + chunkMz.length + ") and intensity ("
                        + chunkIntensity.length + ") lengths differ for spectrum_index " + si);
            }
            for (int i = 0; i < chunkMz.length; i++) {
                mz.add(chunkMz[i]);
                intensity.add(chunkIntensity[i]);
            }
        }

        private static double[] decodeNumpress(Group chunk, String field, String cvAccession, long si, String what) {
            byte[] bytes = ParquetGroups.byteList(chunk, field);
            if (bytes.length == 0) {
                return new double[0];
            }
            try {
                return MSNumpress.decode(cvAccession, bytes, bytes.length);
            } catch (RuntimeException e) {
                throw new MzPeakException("Failed to Numpress-decode " + what + " for spectrum_index " + si, e);
            }
        }

        /** Delta-decode chunk m/z values (NaN preserved as gaps); "no compression" values are absolute. */
        private static double[] decodeChunkMz(double[] values, double start, String encoding) {
            boolean noCompression = encoding != null && encoding.contains("1003088");
            double[] out = new double[values.length];
            double acc = start;
            for (int i = 0; i < values.length; i++) {
                double v = values[i];
                if (Double.isNaN(v)) {
                    out[i] = Double.NaN; // gap; accumulator unchanged
                } else if (noCompression) {
                    out[i] = v;
                } else {
                    acc += v;
                    out[i] = acc;
                }
            }
            return out;
        }

        private long requireIndex(Group g) {
            Long si = ParquetGroups.optLong(g, "spectrum_index");
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
            Arrays arrays = finalizeArrays(mz.toArray(), intensity.toArray(), reconstructProfile);
            if (result.putIfAbsent(current, arrays) != null) {
                throw new MzPeakException("signal file is not sorted by spectrum_index (index " + current
                        + " appears in multiple non-contiguous runs)");
            }
            mz = new DoubleArrayBuilder();
            intensity = new DoubleArrayBuilder();
        }

        private static double orZero(Double d) {
            return d == null ? 0.0 : d;
        }

        private static double nonNullIntensity(double v) {
            return Double.isNaN(v) ? 0.0 : v;
        }
    }

    /**
     * Resolve NaN m/z placeholders. With reconstruction on, interpolate gaps so the point count matches the
     * declared {@code number_of_data_points}; otherwise drop them (and their intensities).
     */
    static Arrays finalizeArrays(double[] mz, double[] intensity, boolean reconstructProfile) {
        if (reconstructProfile) {
            interpolateGaps(mz);
            return new Arrays(mz, intensity);
        }
        int kept = 0;
        for (double v : mz) {
            if (!Double.isNaN(v)) {
                kept++;
            }
        }
        if (kept == mz.length) {
            return new Arrays(mz, intensity);
        }
        double[] cm = new double[kept];
        double[] ci = new double[kept];
        int j = 0;
        for (int i = 0; i < mz.length; i++) {
            if (!Double.isNaN(mz[i])) {
                cm[j] = mz[i];
                ci[j] = intensity[i];
                j++;
            }
        }
        return new Arrays(cm, ci);
    }

    /** Fill NaN m/z by linear interpolation between bracketing values; flatten leading/trailing runs. */
    static void interpolateGaps(double[] m) {
        int n = m.length;
        int firstKnown = 0;
        while (firstKnown < n && Double.isNaN(m[firstKnown])) {
            firstKnown++;
        }
        if (firstKnown == n) {
            return;
        }
        for (int k = 0; k < firstKnown; k++) {
            m[k] = m[firstKnown];
        }
        int i = firstKnown;
        while (i < n) {
            if (!Double.isNaN(m[i])) {
                i++;
                continue;
            }
            int left = i - 1;
            int right = i;
            while (right < n && Double.isNaN(m[right])) {
                right++;
            }
            if (right == n) {
                for (int k = i; k < n; k++) {
                    m[k] = m[left];
                }
                break;
            }
            double a = m[left];
            double b = m[right];
            int gap = right - left;
            for (int k = left + 1; k < right; k++) {
                m[k] = a + (b - a) * (k - left) / gap;
            }
            i = right + 1;
        }
    }
}
