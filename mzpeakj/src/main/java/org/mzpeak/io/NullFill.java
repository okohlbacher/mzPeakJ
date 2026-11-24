package org.mzpeak.io;

import org.mzpeak.io.SpectrumArrayStore.Arrays;

import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the NaN m/z placeholders that null marking leaves in a decoded profile spectrum, or the NaN time
 * placeholders of a chunked chromatogram (C4): drops them with their intensities, or fills them by the spec's rule
 * (signal-data.md, Decoding null pairs). The rules below say m/z and spectrum for the main axis and the entity.
 */
final class NullFill {

    private static final System.Logger LOG = System.getLogger(NullFill.class.getName());

    private NullFill() {
    }

    /**
     * Drops the NaN m/z placeholders and their intensities. {@code extras} are each parallel to {@code mz}: the
     * points dropped from {@code mz} are dropped from every extra too, so the extras stay parallel to the m/z, and an
     * extra left with only NaN values is dropped. Without a NaN m/z the arrays are returned as they are.
     */
    static Arrays dropNulls(double[] mz, double[] intensity, Map<String, double[]> extras) {
        int kept = 0;
        for (double v : mz) {
            if (!Double.isNaN(v)) {
                kept++;
            }
        }
        if (kept == mz.length) {
            return new Arrays(mz, intensity, extras);
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
        Map<String, double[]> ce = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> extra : extras.entrySet()) {
            double[] values = keepStored(mz, extra.getValue(), kept);
            if (hasNumber(values)) {
                ce.put(extra.getKey(), values);
            }
        }
        return new Arrays(cm, ci, ce.isEmpty() ? Map.of() : ce);
    }

    /** True when some value is not NaN. */
    private static boolean hasNumber(double[] values) {
        for (double v : values) {
            if (!Double.isNaN(v)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code kept} values of {@code values} at the positions where {@code mz} is not NaN. */
    private static double[] keepStored(double[] mz, double[] values, int kept) {
        double[] out = new double[kept];
        int j = 0;
        for (int i = 0; i < mz.length; i++) {
            if (!Double.isNaN(mz[i])) {
                out[j++] = values[i];
            }
        }
        return out;
    }

    /** The concatenation of {@code rows}; the row itself when there is one. */
    static double[] concat(List<double[]> rows) {
        if (rows.size() == 1) {
            return rows.get(0);
        }
        int n = 0;
        for (double[] row : rows) {
            n += row.length;
        }
        double[] out = new double[n];
        int at = 0;
        for (double[] row : rows) {
            System.arraycopy(row, 0, out, at, row.length);
            at += row.length;
        }
        return out;
    }

    /**
     * Fills the NaN m/z of one spectrum in place, array by array, and returns their concatenation. Each array is a
     * chunk row of the chunk layout ({@code chunked}) or the whole spectrum of the point layout. Null marking stores
     * the zero-intensity points on both sides of each stored segment as nulls, so the nulls of an array come in pairs
     * (the right flank of one segment and the left flank of the next) except for the first and last null of the
     * array; a chunk row boundary may split a pair. Per array (signal-data.md, Decoding null pairs):
     * <ul>
     *   <li>a flank steps from its segment's stored end value by the segment's spacing: the second median of the
     *       differences of its stored values (the median of the differences at or below their median) when it has two
     *       or more, else {@code model} ({@code mz_delta_model}: {@code delta(mz) = model[0] + model[1]*mz + ...})
     *       at the single value; without a model, the second median of all differences between adjacent stored values
     *       of the spectrum, in every array (mzPeakJ's fallback), and when it has none either, it throws;
     *   <li>a null alone between stored values (an unpaired null that is not the first or last value of the array)
     *       throws, naming the spectrum, the chunk row and the position;
     *   <li>a run of three or more nulls between stored values is filled at its ends as a pair and linearly between
     *       them, and a run of two or more at an end of the array steps on outwards by the spacing; such runs are not
     *       produced by null marking and are logged once per spectrum at DEBUG;
     *   <li>an array without stored values is left as it is.
     * </ul>
     *
     * @param model  the spectrum's delta model; null or empty when it has none
     * @param member the member name in messages
     * @param index  the entity in messages, which also names the main axis
     */
    static double[] fill(List<double[]> rows, boolean chunked, double[] model, String member,
                         SignalEntity.Ref index) {
        new EntityFill(rows, chunked, model, member, index).fill();
        return concat(rows);
    }

    /** The fill of one spectrum. */
    private static final class EntityFill {
        private final List<double[]> rows;
        private final boolean chunked;
        private final double[] model;
        private final String member;
        private final SignalEntity.Ref index;
        /** The main axis in messages: m/z, time. */
        private final String axis;
        /** The delta model in messages: mz_delta_model for a spectrum. */
        private final String modelName;
        /** The spacing for a single stored value without a model, from the stored values before any fill. */
        private double fallback = Double.NaN;
        /** The runs of nulls that null marking does not produce, and where the first is. */
        private int irregular;
        private String firstIrregular;

        EntityFill(List<double[]> rows, boolean chunked, double[] model, String member, SignalEntity.Ref index) {
            this.rows = rows;
            this.chunked = chunked;
            this.model = model == null || model.length == 0 ? null : model;
            this.member = member;
            this.index = index;
            this.axis = index.entity().axisName();
            this.modelName = index.entity() == SignalEntity.SPECTRUM ? "mz_delta_model" : "delta model";
        }

        void fill() {
            if (!hasNull()) {
                return;
            }
            if (model == null) {
                fallback = entitySpacing();
            }
            for (int r = 0; r < rows.size(); r++) {
                fillRow(rows.get(r), r);
            }
            if (irregular > 0) {
                int runs = irregular;
                String first = firstIrregular;
                LOG.log(Level.DEBUG, () -> member + ": " + index + " has " + runs + (runs == 1 ? " run" : " runs")
                        + " of null " + axis + " that null marking does not produce (the first at " + first + "):"
                        + " three or more nulls between stored values, or more than one at an end of an array; filled"
                        + " by stepping from the stored values");
            }
        }

        private boolean hasNull() {
            for (double[] m : rows) {
                for (double v : m) {
                    if (Double.isNaN(v)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The second median of the differences between adjacent stored values of every array, NaN without any. */
        private double entitySpacing() {
            int n = 0;
            for (double[] m : rows) {
                n += m.length;
            }
            double[] deltas = new double[n];
            int k = 0;
            for (double[] m : rows) {
                for (int i = 1; i < m.length; i++) {
                    if (!Double.isNaN(m[i]) && !Double.isNaN(m[i - 1])) {
                        deltas[k++] = m[i] - m[i - 1];
                    }
                }
            }
            return k == 0 ? Double.NaN : secondMedian(deltas, k);
        }

        /**
         * Fills the nulls of the array {@code m}, chunk row {@code row}. Its runs of nulls are found first, so that
         * each stored segment is known by the runs around it while the runs before it are filled.
         */
        private void fillRow(double[] m, int row) {
            int n = m.length;
            int[] runs = new int[n + 1];   // start, end, start, end, ...
            int count = 0;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(m[i]) && (i == 0 || !Double.isNaN(m[i - 1]))) {
                    runs[count++] = i;
                }
                if (Double.isNaN(m[i]) && (i == n - 1 || !Double.isNaN(m[i + 1]))) {
                    runs[count++] = i + 1;
                }
            }
            if (count == 2 && runs[0] == 0 && runs[1] == n) {
                return; // no stored value
            }
            for (int j = 0; j < count; j += 2) {
                int before = j == 0 ? 0 : runs[j - 1];
                int after = j + 2 < count ? runs[j + 2] : n;
                fillRun(m, row, runs[j], runs[j + 1], before, after);
            }
        }

        /**
         * Fills the nulls {@code [a, b)} of {@code m}; the stored segment before them is {@code [before, a)}, the one
         * after them {@code [b, after)}, and at least one is not empty.
         */
        private void fillRun(double[] m, int row, int a, int b, int before, int after) {
            int n = m.length;
            int length = b - a;
            boolean interior = a > 0 && b < n;
            if (interior && length == 1) {
                throw new MzPeakException(member + ": unpaired null " + axis + " at position " + a + where(row)
                        + " of " + index + "; an unpaired null may only be the first or last value of "
                        + (chunked ? "a chunk row" : "the spectrum") + " (signal-data.md, Decoding null pairs)");
            }
            if ((interior ? length > 2 : length > 1) && irregular++ == 0) {
                firstIrregular = "position " + a + where(row);
            }
            if (a > 0) {                                  // the right flank of the segment before, and on
                double dx = spacing(m, row, before, a);
                int end = interior ? a + 1 : n;
                for (int k = a; k < end; k++) {
                    m[k] = m[k - 1] + dx;
                }
            }
            if (b < n) {                                  // the left flank of the segment after, and back
                double dx = spacing(m, row, b, after);
                int start = interior ? b - 1 : 0;
                for (int k = b - 1; k >= start; k--) {
                    m[k] = m[k + 1] - dx;
                }
            }
            if (interior) {                               // between the flanks of a longer run
                double left = m[a];
                double right = m[b - 1];
                int gap = length - 1;
                for (int k = a + 1; k < b - 1; k++) {
                    m[k] = left + (right - left) * (k - a) / gap;
                }
            }
        }

        /** The spacing of the stored segment {@code [from, to)}. */
        private double spacing(double[] m, int row, int from, int to) {
            if (to - from == 1) {
                return single(m[from], row, from);
            }
            double[] deltas = new double[to - from - 1];
            for (int i = 0; i < deltas.length; i++) {
                deltas[i] = m[from + i + 1] - m[from + i];
            }
            return secondMedian(deltas, deltas.length);
        }

        /** The spacing at the single stored value {@code mz} at {@code at}: the model's, else the spectrum's. */
        private double single(double mz, int row, int at) {
            if (model != null) {
                double acc = 0;
                double power = 1;
                for (double b : model) {
                    acc += b * power;
                    power *= mz;
                }
                return acc;
            }
            if (Double.isNaN(fallback)) {
                throw new MzPeakException(member + ": the null " + axis + " next to the single stored " + axis
                        + " at position " + at + where(row) + " of " + index + " cannot be filled: there is no "
                        + modelName + " and no two adjacent stored " + axis + " to estimate the spacing from");
            }
            return fallback;
        }

        private String where(int row) {
            return chunked ? " of chunk " + row : "";
        }
    }

    /** The median of the first {@code n} values at or below their median: numpy's, the mean of two middle values. */
    private static double secondMedian(double[] values, int n) {
        double[] sorted = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(sorted);
        double median = median(sorted, n);
        int below = 0;
        while (below < n && sorted[below] <= median) {
            below++;
        }
        return median(sorted, below);
    }

    private static double median(double[] sorted, int n) {
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
