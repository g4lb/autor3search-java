package io.github.autor3search.bench;

import java.util.List;

/**
 * The comparison of one benchmark's unit between the baseline and the candidate.
 *
 * @param name        the benchmark, e.g. "com.example.WordCount.count"
 * @param unit        the measured unit, e.g. "ns/op"
 * @param baseCenter  the baseline median
 * @param candCenter  the candidate median
 * @param ratio       candCenter / baseCenter; below 1 is better for lower-is-better units
 * @param pctChange   (ratio - 1) * 100
 * @param p           the Mann-Whitney two-sided p-value
 * @param alpha       the RAW, uncorrected rejection threshold
 * @param significant p &lt; alpha, always at the raw alpha — the honest statistic,
 *                    never the Bonferroni-corrected KEEP threshold layered on top
 * @param nBase       observations behind baseCenter
 * @param nCand       observations behind candCenter
 * @param warnings    what says these numbers must not be read at face value
 */
public record Delta(
        String name,
        String unit,
        double baseCenter,
        double candCenter,
        double ratio,
        double pctChange,
        double p,
        double alpha,
        boolean significant,
        int nBase,
        int nCand,
        List<String> warnings) {}
