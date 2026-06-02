package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a {@code point}-layout signal file ({@code spectra_data.parquet} or {@code spectra_peaks.parquet})
 * and groups its rows by {@code spectrum_index} into parallel {@code double[]} m/z + intensity arrays.
 *
 * <p>Prototype strategy: the whole file is read once on construction and cached in memory. This is correct
 * and fast for the example datasets. Streaming / row-group + page predicate pushdown for large files is a
 * documented follow-up (see PEAK-03).
 */
final class PointArrayStore {

    /** Parallel m/z + intensity arrays for one spectrum. */
    record Arrays(double[] mz, double[] intensity) {
    }

    private final Map<Long, Arrays> byIndex;

    private PointArrayStore(Map<Long, Arrays> byIndex) {
        this.byIndex = byIndex;
    }

    Arrays get(long spectrumIndex) {
        return byIndex.get(spectrumIndex);
    }

    boolean contains(long spectrumIndex) {
        return byIndex.containsKey(spectrumIndex);
    }

    static PointArrayStore load(Path file) {
        Map<Long, Arrays> result = new HashMap<>();
        Accumulator acc = new Accumulator(result);
        ParquetGroups.forEach(file, acc);
        acc.flush();
        return new PointArrayStore(result);
    }

    /** Stateful handler that flushes a spectrum's points when the sorted {@code spectrum_index} changes. */
    private static final class Accumulator implements java.util.function.Consumer<Group> {
        private final Map<Long, Arrays> result;
        private long current = Long.MIN_VALUE;
        private boolean started = false;
        private DoubleBuf mz = new DoubleBuf();
        private DoubleBuf intensity = new DoubleBuf();

        Accumulator(Map<Long, Arrays> result) {
            this.result = result;
        }

        @Override
        public void accept(Group record) {
            Group point = ParquetGroups.optGroup(record, "point");
            if (point == null) {
                throw new MzPeakException("point-layout file row missing 'point' struct");
            }
            Long si = ParquetGroups.optLong(point, "spectrum_index");
            if (si == null) {
                throw new MzPeakException("point row missing spectrum_index");
            }
            if (si < 0) {
                throw new MzPeakException("spectrum_index exceeds supported range (uint64 high bit set): " + si);
            }
            Double mzVal = ParquetGroups.optDouble(point, "mz");
            Double inVal = ParquetGroups.optDouble(point, "intensity");
            // Null marking (deferred): treat null intensity as 0; skip points with null m/z for the prototype.
            if (mzVal == null) {
                return;
            }
            if (!started) {
                started = true;
                current = si;
            } else if (si != current) {
                flush();
                current = si;
            }
            mz.add(mzVal);
            intensity.add(inVal == null ? 0.0 : inVal);
        }

        void flush() {
            if (!started) {
                return;
            }
            if (result.putIfAbsent(current, new Arrays(mz.toArray(), intensity.toArray())) != null) {
                throw new MzPeakException("point file is not sorted by spectrum_index (index " + current
                        + " appears in multiple non-contiguous runs)");
            }
            mz = new DoubleBuf();
            intensity = new DoubleBuf();
        }
    }

    /** Minimal growable double array to avoid boxing 200k+ points. */
    private static final class DoubleBuf {
        private double[] a = new double[16];
        private int n = 0;

        void add(double v) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        double[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
