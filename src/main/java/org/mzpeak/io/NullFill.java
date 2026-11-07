package org.mzpeak.io;

import org.mzpeak.io.SpectrumArrayStore.Arrays;

/**
 * Resolves the NaN m/z placeholders that null marking leaves in a decoded profile spectrum: drops them with their
 * intensities, interpolates them linearly, or steps them with the spectrum's stored {@code mz_delta_model}.
 */
final class NullFill {

    private NullFill() {
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
     * Both reference readers agree on that local-median rule (Rust {@code filter.rs}, HEAD Python {@code filters.py}).
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
