package io.github.autor3search.bench;

import java.util.Arrays;

/**
 * The two-sided Mann-Whitney U test (Wilcoxon rank-sum), which is what decides
 * whether a difference between two sets of benchmark observations is real.
 *
 * <p>Rank-based and distribution-free, because benchmark timings are not normal:
 * they are bounded below by the work the code actually does and have a long tail
 * of interference from everything else on the machine. A t-test on that data
 * reports confidence it has not earned.
 *
 * <p>The exact null distribution is used whenever it can be — no ties, and both
 * samples small enough that the enumeration is cheap. That matters at the sample
 * sizes this harness runs at: with 10 rounds per side the normal approximation
 * is noticeably wrong in the tail, which is the only part of the distribution a
 * significance threshold ever looks at.
 */
public final class MannWhitney {
    private MannWhitney() {}

    /** The rejection threshold a comparison is reported against. */
    public static final double DEFAULT_ALPHA = 0.05;

    /** The largest sample size for which the exact distribution is enumerated. */
    private static final int EXACT_LIMIT = 20;

    /**
     * The outcome of one test.
     *
     * @param p     the two-sided p-value
     * @param alpha the threshold it should be read against
     * @param n1    observations on the first side
     * @param n2    observations on the second side
     * @param exact whether the exact null distribution was used
     */
    public record Result(double p, double alpha, int n1, int n2, boolean exact) {
        public boolean significant() {
            return p < alpha;
        }
    }

    /** Runs the test at {@link #DEFAULT_ALPHA}. */
    public static Result test(double[] a, double[] b) {
        return test(a, b, DEFAULT_ALPHA);
    }

    public static Result test(double[] a, double[] b, double alpha) {
        int n1 = a.length;
        int n2 = b.length;
        if (n1 < 1 || n2 < 1) {
            throw new IllegalArgumentException("Mann-Whitney needs at least one observation per side");
        }
        double[] pooled = new double[n1 + n2];
        System.arraycopy(a, 0, pooled, 0, n1);
        System.arraycopy(b, 0, pooled, n1, n2);
        Integer[] order = new Integer[pooled.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(pooled[x], pooled[y]));

        double[] ranks = new double[pooled.length];
        double tieTerm = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && pooled[order[j + 1]] == pooled[order[i]]) j++;
            double avgRank = (i + j + 2) / 2.0; // ranks are 1-based
            int groupSize = j - i + 1;
            if (groupSize > 1) tieTerm += (double) groupSize * groupSize * groupSize - groupSize;
            for (int k = i; k <= j; k++) ranks[order[k]] = avgRank;
            i = j + 1;
        }

        double rankSumA = 0;
        for (int k = 0; k < n1; k++) rankSumA += ranks[k];
        double u1 = rankSumA - (double) n1 * (n1 + 1) / 2;

        boolean ties = tieTerm > 0;
        if (!ties && n1 <= EXACT_LIMIT && n2 <= EXACT_LIMIT) {
            return new Result(exactP(n1, n2, (int) Math.round(u1)), alpha, n1, n2, true);
        }
        return new Result(normalP(n1, n2, u1, tieTerm), alpha, n1, n2, false);
    }

    /**
     * The smallest two-sided p-value the test can return for samples of size n1
     * and n2: {@code 2 / C(n1+n2, n1)}, because that is the fraction of orderings
     * at least as extreme as the most extreme one possible. Two samples can be
     * maximally separated and the test still cannot go below it.
     */
    public static double minAchievableP(int n1, int n2) {
        if (n1 < 1 || n2 < 1) return 1;
        double p = 2 / binomial(n1 + n2, n1);
        return Math.min(p, 1);
    }

    /** C(n, k) as a double, kept near the result rather than routed through a factorial. */
    public static double binomial(int n, int k) {
        if (k < 0 || k > n) return 0;
        int kk = Math.min(k, n - k);
        double c = 1;
        for (int i = 0; i < kk; i++) {
            c = c * (n - i) / (i + 1);
        }
        return c;
    }

    /**
     * The exact two-sided p-value, from the count of rank orderings producing
     * each possible U.
     *
     * <p>That count is the number of partitions of U into at most n1 parts, each
     * at most n2 — the Gaussian binomial coefficient — which satisfies
     * {@code P(a,b,u) = P(a-1,b,u) + P(a,b-1,u-a)}: split on whether the
     * partition uses fewer than a parts, or exactly a (in which case subtracting
     * one from each part leaves a partition of u-a inside an a x (b-1) box).
     */
    private static double exactP(int n1, int n2, int u) {
        int max = n1 * n2;
        double[][][] table = new double[n1 + 1][n2 + 1][];
        for (int a = 0; a <= n1; a++) {
            for (int b = 0; b <= n2; b++) {
                double[] row = new double[max + 1];
                row[0] = 1; // the empty partition, for every box
                if (a > 0 && b > 0) {
                    double[] fewerParts = table[a - 1][b];
                    double[] smallerParts = table[a][b - 1];
                    for (int s = 1; s <= max; s++) {
                        double v = fewerParts[s];
                        if (s - a >= 0) v += smallerParts[s - a];
                        row[s] = v;
                    }
                }
                table[a][b] = row;
            }
        }
        double[] counts = table[n1][n2];
        double total = binomial(n1 + n2, n1);
        int clamped = Math.max(0, Math.min(max, u));
        double lower = 0;
        for (int s = 0; s <= clamped; s++) lower += counts[s];
        double upper = 0;
        for (int s = clamped; s <= max; s++) upper += counts[s];
        double p = 2 * Math.min(lower, upper) / total;
        return Math.min(p, 1);
    }

    /**
     * The normal approximation, with a continuity correction and the standard
     * correction to the variance for tied ranks. Used when the samples are large
     * enough that enumeration is wasteful, or when ties make the exact
     * distribution wrong.
     */
    private static double normalP(int n1, int n2, double u, double tieTerm) {
        double n = n1 + n2;
        double mu = (double) n1 * n2 / 2;
        double variance = ((double) n1 * n2 / 12) * ((n + 1) - tieTerm / (n * (n - 1)));
        if (variance <= 0) return 1;
        double sigma = Math.sqrt(variance);
        double z = (Math.abs(u - mu) - 0.5) / sigma;
        if (z <= 0) return 1;
        return Math.min(1, erfc(z / Math.sqrt(2)));
    }

    /**
     * The complementary error function, to a fractional accuracy of about 1.2e-7
     * — far finer than any threshold a p-value is compared against here, and it
     * avoids taking a dependency on a statistics library for one function.
     */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2.0 - ans;
    }
}
