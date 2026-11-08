package org.mzpeak.io;

import java.util.Arrays;

/** Minimal growable {@code double} array, to accumulate point values without boxing. */
final class DoubleArrayBuilder {
    private double[] a = new double[16];
    private int n = 0;

    void add(double v) {
        if (n == a.length) {
            a = Arrays.copyOf(a, a.length * 2);
        }
        a[n++] = v;
    }

    int size() {
        return n;
    }

    double[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
