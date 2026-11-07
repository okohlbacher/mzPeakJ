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
 * Reads a spectrum signal file ({@code spectra_data.parquet} / {@code spectra_peaks.parquet}) in a streaming,
 * memory-bounded fashion. Supports the {@code point} layout, delta-encoded {@code chunk} layout, and
 * MS-Numpress chunks (linear m/z + SLOF intensity). A chunk it cannot decode (a coordinate grid, an unknown
 * {@code chunk_encoding}, Numpress intensity without Numpress m/z, or an unknown Numpress column with a value)
 * throws an {@link MzPeakException} instead of being dropped or misdecoded. The chunk payloads are decoded by
 * {@link ChunkCodecs}, and null-marked m/z is resolved by {@link NullFill}.
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
            ChunkCodecs.rejectUnknownNumpressColumns(chunk, si);
            String encoding = ParquetGroups.optString(chunk, "chunk_encoding");
            if (encoding != null && encoding.contains("1003826")) {
                throw ChunkCodecs.unsupportedGrid(chunk, si);
            }
            // Checked for every row: Numpress column presence picks the decoder, but never admits an unknown CURIE.
            boolean absolute = ChunkCodecs.isAbsolute(encoding, si);
            if (ParquetGroups.has(chunk, "mz_numpress_linear_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")
                    || ParquetGroups.has(chunk, "intensity_numpress_pic_bytes")) {
                acceptNumpressChunk(chunk, si);
                return;
            }
            double start = orZero(ParquetGroups.optDouble(chunk, "mz_chunk_start"));
            double[] rawMz = ParquetGroups.doubleListNullable(chunk, "mz_chunk_values");
            double[] chunkIntensity = ParquetGroups.doubleListNullable(chunk, "intensity");
            if (ParquetGroups.has(chunk, "mz_grid") && rawMz.length == 0 && chunkIntensity.length > 0) {
                // A grid chunk without the grid chunk_encoding: the intensities pair with grid indices, not values.
                throw ChunkCodecs.unsupportedGrid(chunk, si);
            }
            double[] chunkMz = ChunkCodecs.decodeAxisValues(rawMz, start, absolute);
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
                ChunkCodecs.rejectUnpairedNumpressIntensity(chunk, "intensity_numpress_slof_bytes",
                        MSNumpress.ACC_NUMPRESS_SLOF, "SLOF", si);
                ChunkCodecs.rejectUnpairedNumpressIntensity(chunk, "intensity_numpress_pic_bytes",
                        MSNumpress.ACC_NUMPRESS_PIC, "PIC", si);
                return;
            }
            double[] chunkMz = ChunkCodecs.decodeNumpress(chunk, "mz_numpress_linear_bytes",
                    MSNumpress.ACC_NUMPRESS_LINEAR, si, "m/z");
            double[] chunkIntensity;
            if (ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")) {
                chunkIntensity = ChunkCodecs.decodeNumpress(chunk, "intensity_numpress_slof_bytes",
                        MSNumpress.ACC_NUMPRESS_SLOF, si, "intensity");
            } else if (ParquetGroups.has(chunk, "intensity_numpress_pic_bytes")) {
                chunkIntensity = ChunkCodecs.decodeNumpress(chunk, "intensity_numpress_pic_bytes",
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
                NullFill.polynomialFill(mzArray, model);
                arrays = new Arrays(mzArray, intensityArray);
            } else {
                arrays = NullFill.finalizeArrays(mzArray, intensityArray, reconstructProfile);
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
}
