package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads a spectrum signal file ({@code spectra_data.parquet} / {@code spectra_peaks.parquet}) and groups its
 * rows by {@code spectrum_index} into parallel {@code double[]} m/z + intensity arrays. Supports both the
 * {@code point} layout (one row per point) and the {@code chunk} layout (one row per m/z chunk, with
 * delta-encoded {@code mz_chunk_values}). Numpress-encoded chunks are detected and rejected with a clear error.
 *
 * <p>Prototype strategy: the whole file is read once on construction and cached. Streaming / predicate
 * pushdown for large files is future work.
 */
final class SpectrumArrayStore {

    record Arrays(double[] mz, double[] intensity) {
    }

    private final Map<Long, Arrays> byIndex;

    private SpectrumArrayStore(Map<Long, Arrays> byIndex) {
        this.byIndex = byIndex;
    }

    Arrays get(long spectrumIndex) {
        return byIndex.get(spectrumIndex);
    }

    boolean contains(long spectrumIndex) {
        return byIndex.containsKey(spectrumIndex);
    }

    static SpectrumArrayStore load(InputFile file, boolean reconstructProfile) {
        Map<Long, Arrays> result = new HashMap<>();
        Accumulator acc = new Accumulator(result, reconstructProfile);
        ParquetGroups.forEach(file, acc);
        acc.flush();
        return new SpectrumArrayStore(result);
    }

    /** Buffers one spectrum's points/chunks and flushes when the sorted {@code spectrum_index} changes. */
    private static final class Accumulator implements Consumer<Group> {
        private final Map<Long, Arrays> result;
        private final boolean reconstructProfile;
        private long current = Long.MIN_VALUE;
        private boolean started = false;
        private DoubleBuf mz = new DoubleBuf();
        private DoubleBuf intensity = new DoubleBuf();

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
                throw new MzPeakException("Numpress-encoded chunk layout is not yet supported "
                        + "(spectrum_index " + si + "); use a delta-encoded or point-layout mzPeak file");
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
            mz = new DoubleBuf();
            intensity = new DoubleBuf();
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

    /** Minimal growable double array to avoid boxing. */
    private static final class DoubleBuf {
        private double[] a = new double[16];
        private int n = 0;

        void add(double v) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        int size() {
            return n;
        }

        double[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
