package org.edtp.entitycollisionoptimizer.gametest;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import java.util.Arrays;
import java.util.Locale;

/** Keeps sampled values for exact diagnostic quantiles, without per-value boxing. */
final class ScanDistribution {
    private final DoubleArrayList values = new DoubleArrayList();

    void add(double value) { values.add(value); }
    int size() { return values.size(); }

    String summary() {
        if (values.isEmpty()) return "empty";
        double[] sorted = values.toDoubleArray();
        Arrays.sort(sorted);
        double sum = 0;
        for (double value : sorted) sum += value;
        return String.format(Locale.ROOT, "[mean:%.6f,p50:%.6f,p95:%.6f,p99:%.6f,max:%.6f]",
                sum / sorted.length, quantile(sorted, 0.50), quantile(sorted, 0.95),
                quantile(sorted, 0.99), sorted[sorted.length - 1]);
    }

    private static double quantile(double[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
}
