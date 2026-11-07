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
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads a spectrum signal file ({@code spectra_data.parquet} / {@code spectra_peaks.parquet}) in a streaming,
 * memory-bounded fashion. Supports the {@code point} layout, delta-encoded {@code chunk} layout, and
 * MS-Numpress chunks (linear m/z + SLOF intensity). A chunk it cannot decode (a coordinate grid, an unknown
 * {@code chunk_encoding}, Numpress intensity without Numpress m/z, or an unknown Numpress column with a value)
 * throws an {@link MzPeakException} instead of being dropped or misdecoded.
 *
 * <p><b>Streaming:</b> per-row-group {@code spectrum_index} min/max statistics are read at open time;
 * {@link #get} decodes only the row group(s) covering the requested index, caching the last decoded block run.
 * Memory usage is bounded to one row-group run rather than the whole file.
 */
final class SpectrumArrayStore implements AutoCloseable {

    record Arrays(double[] mz, double[] intensity) {
    }

    private final ParquetFileReader reader;
    private final MessageType schema;
    private final boolean reconstructProfile;
    private final Map<Long, double[]> deltaModels;
    private final long[] rgMin;
    private final long[] rgMax;
    private final ColumnIOFactory columnIO = new ColumnIOFactory();

    // cache of the last-decoded contiguous block run [cachedFirst, cachedLast]
    private int cachedFirst = -1;
    private int cachedLast = -2;
    private Map<Long, Arrays> cache = Map.of();

    private SpectrumArrayStore(ParquetFileReader reader, MessageType schema, boolean reconstructProfile,
                               Map<Long, double[]> deltaModels, long[] rgMin, long[] rgMax) {
        this.reader = reader;
        this.schema = schema;
        this.reconstructProfile = reconstructProfile;
        this.deltaModels = deltaModels;
        this.rgMin = rgMin;
        this.rgMax = rgMax;
    }

    static SpectrumArrayStore load(InputFile file, boolean reconstructProfile, Map<Long, double[]> deltaModels) {
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
            return new SpectrumArrayStore(reader, schema, reconstructProfile, deltaModels, rgMin, rgMax);
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
        Accumulator acc = new Accumulator(result, reconstructProfile, deltaModels);
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
        private static final String DELTA_ENCODING = "MS:1003089";
        private static final String NO_COMPRESSION = "MS:1000576";
        private static final String TRUNCATION_ZLIB = "MS:1003088";
        private static final String GRID_ENCODING = "MS:1003826";
        /** The Numpress columns decoded here; any other column whose name contains "numpress" is not understood. */
        private static final Set<String> NUMPRESS_COLUMNS = Set.of(
                "mz_numpress_linear_bytes", "intensity_numpress_slof_bytes", "intensity_numpress_pic_bytes");

        private final Map<Long, Arrays> result;
        private final boolean reconstructProfile;
        private final Map<Long, double[]> deltaModels;
        private long current = Long.MIN_VALUE;
        private boolean started = false;
        private DoubleArrayBuilder mz = new DoubleArrayBuilder();
        private DoubleArrayBuilder intensity = new DoubleArrayBuilder();

        Accumulator(Map<Long, Arrays> result, boolean reconstructProfile, Map<Long, double[]> deltaModels) {
            this.result = result;
            this.reconstructProfile = reconstructProfile;
            this.deltaModels = deltaModels;
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
            rejectUnknownNumpressColumns(chunk, si);
            if (ParquetGroups.has(chunk, "mz_numpress_linear_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_pic_bytes")) {
                acceptNumpressChunk(chunk, si);
                return;
            }
            double start = orZero(ParquetGroups.optDouble(chunk, "mz_chunk_start"));
            String encoding = ParquetGroups.optString(chunk, "chunk_encoding");
            double[] rawMz = ParquetGroups.doubleListNullable(chunk, "mz_chunk_values");
            double[] chunkIntensity = ParquetGroups.doubleListNullable(chunk, "intensity");
            rejectGrid(chunk, encoding, rawMz, chunkIntensity, si);
            double[] chunkMz = decodeChunkMz(rawMz, start, isAbsolute(encoding, si));
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
         * Numpress chunk: m/z is MS-Numpress linear, intensity is MS-Numpress SLOF, PIC, or (mixed format)
         * a regular double LIST. These encode the full absolute arrays directly — no chunk_start, no delta,
         * no null-marking — so we decode and append 1:1.
         *
         * <p>Some writers (e.g. Thermo Orbitrap profile exports via mzML2mzPeak) use mixed-format chunks
         * where m/z is Numpress-encoded but intensity is stored as a plain {@code intensity} LIST. We detect
         * this by falling back to the regular intensity column when no intensity Numpress bytes are present.
         *
         * <p>Numpress intensity without Numpress m/z has no m/z to pair with: an empty payload is skipped, and
         * one that decodes to values throws rather than dropping them.
         */
        private void acceptNumpressChunk(Group chunk, long si) {
            if (!ParquetGroups.has(chunk, "mz_numpress_linear_bytes")) {
                rejectUnpairedNumpressIntensity(chunk, "intensity_numpress_slof_bytes",
                        MSNumpress.ACC_NUMPRESS_SLOF, "SLOF", si);
                rejectUnpairedNumpressIntensity(chunk, "intensity_numpress_pic_bytes",
                        MSNumpress.ACC_NUMPRESS_PIC, "PIC", si);
                return;
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
            } else if (ParquetGroups.has(chunk, "intensity")) {
                // Mixed format: m/z is Numpress-compressed, intensity is a plain double list.
                chunkIntensity = ParquetGroups.doubleListNullable(chunk, "intensity");
            } else {
                // No intensity data at all — skip this chunk; the spectrum will materialise as empty.
                return;
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

        /** Throws if Numpress intensity in {@code field} decodes to values that have no Numpress m/z to pair with. */
        private static void rejectUnpairedNumpressIntensity(Group chunk, String field, String cvAccession,
                                                            String kind, long si) {
            if (ParquetGroups.has(chunk, field)
                    && decodeNumpress(chunk, field, cvAccession, si, "intensity").length > 0) {
                throw new MzPeakException("unsupported chunk: Numpress " + kind + " intensity (" + field
                        + ") without Numpress linear m/z (chunk_encoding "
                        + ParquetGroups.optString(chunk, "chunk_encoding") + ") for spectrum_index " + si);
            }
        }

        /**
         * Throws for a {@code *numpress*} column other than the three decoded ones when it carries a value in this
         * row: its data would otherwise be dropped (or, in place of the SLOF column, the whole chunk skipped).
         */
        private static void rejectUnknownNumpressColumns(Group chunk, long si) {
            for (Type field : chunk.getType().getFields()) {
                String name = field.getName();
                if (name.contains("numpress") && !NUMPRESS_COLUMNS.contains(name) && carriesValue(chunk, name)) {
                    throw new MzPeakException("unsupported Numpress column " + name + " for spectrum_index " + si
                            + "; only mz_numpress_linear_bytes, intensity_numpress_slof_bytes and"
                            + " intensity_numpress_pic_bytes are decoded");
                }
            }
        }

        /** True if {@code field} is non-null in this row and, if it is a group (such as a LIST), not empty. */
        private static boolean carriesValue(Group g, String field) {
            if (!ParquetGroups.has(g, field)) {
                return false;
            }
            if (g.getType().getType(field).isPrimitive()) {
                return true;
            }
            Group value = g.getGroup(field, 0);
            for (int i = 0; i < value.getType().getFieldCount(); i++) {
                if (value.getFieldRepetitionCount(i) > 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Throws for a coordinate-grid chunk ({@code MS:1003826}, a draft encoding not decoded yet): one whose
         * {@code chunk_encoding} names the grid, or one with an {@code mz_grid} struct and intensities but no chunk
         * values. Without this check the intensities fail the length check with a misleading message.
         */
        private static void rejectGrid(Group chunk, String encoding, double[] values, double[] intensity, long si) {
            Group grid = ParquetGroups.optGroup(chunk, "mz_grid");
            boolean gridEncoding = encoding != null && encoding.contains("1003826");
            if (gridEncoding || (grid != null && values.length == 0 && intensity.length > 0)) {
                String gridType = grid == null ? null : ParquetGroups.optString(grid, "grid_type");
                throw new MzPeakException("unsupported chunk encoding " + GRID_ENCODING
                        + " (coordinate grid, draft) with grid_type " + (gridType == null ? "(none)" : gridType)
                        + " for spectrum_index " + si + "; grid decoding is not implemented");
            }
        }

        /**
         * Whether a non-Numpress chunk with {@code encoding} stores absolute m/z ({@code MS:1000576},
         * {@code MS:1003088}) rather than deltas from {@code chunk_start} ({@code null}, {@code MS:1003089}).
         * Numpress ({@code MS:1002312}) is chosen by column presence; a row that names it without carrying a Numpress
         * column decodes as delta, as before. Any other encoding throws instead of being decoded as delta.
         */
        private static boolean isAbsolute(String encoding, long si) {
            if (encoding == null || encoding.equals(DELTA_ENCODING)
                    || encoding.equals(MSNumpress.ACC_NUMPRESS_LINEAR)) {
                return false;
            }
            if (encoding.equals(NO_COMPRESSION) || encoding.equals(TRUNCATION_ZLIB)) {
                return true;
            }
            throw new MzPeakException("unsupported chunk encoding " + encoding + " for spectrum_index " + si);
        }

        /**
         * Decode chunk m/z values parallel to {@code values} (NaN preserved as gaps).
         *
         * <p>"No compression" encodings ({@code MS:1000576}) and "truncation + zlib" ({@code MS:1003088})
         * store absolute coordinates — emitted as-is. Delta-prediction ({@code MS:1003089}) stores deltas
         * relative to a running predictor seeded at {@code chunk_start}, but a {@code null} (NaN) breaks the
         * prediction chain: the next non-null value is then <em>absolute</em>, not a delta. This mirrors the
         * spec's {@code null_delta_decode} reference algorithm (chunked-layout, "Delta encoding"). Treating a
         * post-null value as a delta (the previous behaviour) double-counted absolute re-anchors and produced
         * non-monotonic, out-of-range m/z.
         */
        private static double[] decodeChunkMz(double[] values, double start, boolean noDelta) {
            double[] out = new double[values.length];
            if (noDelta) {
                System.arraycopy(values, 0, out, 0, values.length); // absolute; NaN stays NaN
                return out;
            }
            // Null-aware delta decode: `last` is the previous emitted m/z; `lastValid` is false right after a
            // null, which forces the next value to be read as an absolute re-anchor (predictor reset).
            double last = start;
            boolean lastValid = true;
            for (int i = 0; i < values.length; i++) {
                double v = values[i];
                if (Double.isNaN(v)) {
                    out[i] = Double.NaN;   // gap; breaks the delta chain
                    lastValid = false;
                } else if (lastValid) {
                    last += v;             // delta from the running predictor
                    out[i] = last;
                } else {
                    last = v;              // absolute re-anchor after a null
                    out[i] = last;
                    lastValid = true;
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
            double[] mzArray = mz.toArray();
            double[] intensityArray = intensity.toArray();
            double[] model = deltaModels == null ? null : deltaModels.get(current);
            Arrays arrays;
            if (reconstructProfile && model != null && model.length > 0) {
                // Exact reconstruction: fill null-marked m/z by stepping with the stored polynomial delta model.
                polynomialFill(mzArray, model);
                arrays = new Arrays(mzArray, intensityArray);
            } else {
                arrays = finalizeArrays(mzArray, intensityArray, reconstructProfile);
            }
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

    /**
     * Fill NaN m/z by stepping with the per-spectrum delta model {@code beta} (the mzPeak
     * {@code mz_delta_model} polynomial: {@code delta(mz) = beta[0] + beta[1]*mz + beta[2]*mz^2 + ...}). Each
     * filled point sits one predicted local spacing from a stored neighbour — closer to the original grid than
     * linear interpolation.
     *
     * <p>The fill is flank-aware. Null marking replaces the zero-intensity points on both sides of each stored
     * cluster, so an interior run of two nulls is the right flank of one cluster followed by the left flank of the
     * next: the first null steps forwards from the left anchor, the last steps backwards from the right anchor
     * ({@code m[right] - predict(m[right])}). In a longer interior run (not produced by null marking) every null
     * but the last steps forwards. A leading run steps backwards from the first stored value; a trailing run and an
     * interior single null step forwards. The stored model is applied to both flanks as is, without re-fitting;
     * on the small.numpress ground truth this lands within 1e-6 Da. The Rust reference {@code fill_nulls_for} is
     * also flank-aware but uses the polynomial only for single-point clusters and a local median spacing otherwise.
     */
    static void polynomialFill(double[] m, double[] beta) {
        int n = m.length;
        int first = 0;
        while (first < n && Double.isNaN(m[first])) {
            first++;
        }
        if (first == n) {
            return;
        }
        for (int k = first - 1; k >= 0; k--) {            // leading run: step backwards
            m[k] = m[k + 1] - predict(m[k + 1], beta);
        }
        int i = first;
        while (i < n) {
            if (!Double.isNaN(m[i])) {
                i++;
                continue;
            }
            int right = i;
            while (right < n && Double.isNaN(m[right])) {
                right++;
            }
            int end = right;
            if (right < n && right - i >= 2) {            // interior run of 2+: its last null is the left flank
                m[right - 1] = m[right] - predict(m[right], beta);   // of the next cluster, so step backwards
                end = right - 1;
            }
            for (int k = i; k < end; k++) {               // right flank and the rest of the run: step forwards
                m[k] = m[k - 1] + predict(m[k - 1], beta);
            }
            i = right + 1;
        }
    }

    private static double predict(double mz, double[] beta) {
        double acc = 0;
        double power = 1;
        for (double b : beta) {
            acc += b * power;
            power *= mz;
        }
        return acc;
    }
}
