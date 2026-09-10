package io.github.autor3search.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A set of observations of one benchmark and unit, summarised without assuming
 * a distribution.
 *
 * <p>The centre is the MEDIAN, not the mean. A single interfering process during
 * one round adds an arbitrarily large outlier to the high side and none to the
 * low side; the mean follows it and the median does not. That asymmetry is the
 * normal condition of benchmarking, not an edge case.
 */
public record Sample(double[] values) {

    /** The median. */
    public double center() {
        double[] s = values.clone();
        Arrays.sort(s);
        int n = s.length;
        if (n == 0) return Double.NaN;
        if (n % 2 == 1) return s[n / 2];
        return (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /**
     * The smallest sample size at which a two-sided 95% confidence interval on
     * the median is bounded at all.
     *
     * <p>An order-statistic interval covers {@code 1 - 2*(1/2)^n} at its widest —
     * the whole sample, from the smallest observation to the largest. For that to
     * reach 95% needs {@code 2^(1-n) <= 0.05}, so n must be at least 6. Below it,
     * no interval exists at that confidence: the honest answer is unbounded, and
     * a tool that printed one anyway would be inventing precision.
     */
    public static final int MIN_FOR_INTERVAL = 6;

    /**
     * What qualifies how far this sample's numbers can be trusted. Empty when
     * nothing does.
     */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (values.length < MIN_FOR_INTERVAL) {
            out.add("only " + values.length + " measured round(s) per side: at 95% confidence the median's"
                    + " interval needs at least " + MIN_FOR_INTERVAL + " and is unbounded below that."
                    + " The medians are real; the precision implied by them is not. Raise count.");
        }
        return out;
    }
}
