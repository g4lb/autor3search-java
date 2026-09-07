package io.github.g4lb.autor3search.verdict;

import io.github.g4lb.autor3search.bench.Delta;
import io.github.g4lb.autor3search.bench.MannWhitney;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns measurements into a single decision. */
public final class Verdict {
    private Verdict() {}

    /** Everything {@link #decide} needs once every correctness gate has passed. */
    public record Input(List<Delta> deltas, double score, double maxRegressPct, double minEffectPct) {}

    /**
     * Applies the scoring rules.
     *
     * <ol>
     *   <li>Any regression significant at the raw, UNCORRECTED alpha and larger
     *       than {@code maxRegressPct} rejects the change, however good the
     *       overall score. This check deliberately does NOT apply the Bonferroni
     *       correction from rule 2: Bonferroni only ever makes it harder to call
     *       a result significant, and applying it here would make the guard less
     *       sensitive to harm — backwards from what a guard is for. The asymmetry
     *       is intentional: conservative about accepting a win, liberal about
     *       catching a regression.
     *   <li>Otherwise KEEP only when BOTH:
     *     <ul>
     *       <li>the score is a real speedup by at least {@code minEffectPct} —
     *           below {@code 1 - minEffectPct/100}, not merely below 1. A result
     *           that is technically significant but trivially small is not worth
     *           a commit in an unattended loop; and
     *       <li>at least one benchmark improved at the Bonferroni-corrected
     *           threshold {@code alpha/k}, where k is the number of benchmarks
     *           compared. Testing k benchmarks against the same uncorrected alpha
     *           inflates the family-wise false-positive rate — with four
     *           benchmarks, roughly an 18% chance at least one looks significant
     *           when nothing changed — and dividing by k is the standard
     *           correction for that.
     *     </ul>
     * </ol>
     *
     * <p>A change that clears 2b but misses 2a discards with
     * {@link Reason#BELOW_MIN_EFFECT} rather than {@link Reason#NO_IMPROVEMENT}:
     * it measurably worked, it was just too small to bank, and those two call for
     * different next moves.
     */
    public static VerdictResult decide(Input in) {
        int k = Math.max(1, in.deltas().size());
        List<String> warnings = measurementWarnings(in.deltas(), k);

        List<Delta> regressions = new ArrayList<>();
        for (Delta d : in.deltas()) {
            if (d.significant() && d.pctChange() > in.maxRegressPct()) {
                regressions.add(d);
            }
        }
        if (!regressions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < regressions.size(); i++) {
                if (i > 0) sb.append(", ");
                Delta d = regressions.get(i);
                sb.append(d.name()).append(' ').append(pct(d.pctChange()));
            }
            return new VerdictResult(Status.DISCARD, Reason.GUARD_REGRESSION, in.score(),
                    String.format(Locale.ROOT, "regression guard tripped (limit %+.1f%%): %s",
                            in.maxRegressPct(), sb),
                    List.copyOf(regressions), warnings);
        }

        boolean improved = false;
        for (Delta d : in.deltas()) {
            double correctedAlpha = d.alpha() / k;
            if (d.pctChange() < 0 && d.p() < correctedAlpha) {
                improved = true;
                break;
            }
        }
        double minEffectThreshold = 1 - in.minEffectPct() / 100;
        if (in.score() < minEffectThreshold && improved) {
            return new VerdictResult(Status.KEEP, Reason.IMPROVED, in.score(),
                    String.format(Locale.ROOT, "score %.4f (%+.2f%%)", in.score(), (in.score() - 1) * 100),
                    List.of(), warnings);
        }

        if (improved && in.score() < 1) {
            return new VerdictResult(Status.DISCARD, Reason.BELOW_MIN_EFFECT, in.score(),
                    String.format(Locale.ROOT,
                            "score %.4f (%+.2f%%), a real improvement but below the %.1f%% minimum effect size",
                            in.score(), (in.score() - 1) * 100, in.minEffectPct()),
                    List.of(), warnings);
        }
        return new VerdictResult(Status.DISCARD, Reason.NO_IMPROVEMENT, in.score(),
                String.format(Locale.ROOT, "score %.4f (%+.2f%%), no significant improvement",
                        in.score(), (in.score() - 1) * 100),
                List.of(), warnings);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%+.1f%%", v);
    }

    /**
     * Everything that qualifies how far the numbers in a result can be trusted:
     * the per-comparison warnings about underpowered samples, then the harness's
     * own check that a KEEP was statistically reachable at all.
     */
    static List<String> measurementWarnings(List<Delta> deltas, int k) {
        Set<String> out = new LinkedHashSet<>();
        for (Delta d : deltas) {
            out.addAll(d.warnings());
        }
        String unreachable = unreachableAlphaWarning(deltas, k);
        if (unreachable != null) out.add(unreachable);
        return List.copyOf(out);
    }

    /**
     * Reports when rule 2b cannot be satisfied by any result whatsoever, so the
     * run is incapable of a KEEP before it starts.
     *
     * <p>The Mann-Whitney U test has a floor on the p-value it can produce for a
     * given pair of sample sizes: two samples can be maximally separated and the
     * test still only reaches {@code 2/C(n1+n2, n1)}, because that is the fraction
     * of orderings at least as extreme as the observed one. If the corrected
     * threshold {@code alpha/k} falls below that floor for EVERY benchmark, no
     * benchmark can clear it and every experiment discards no matter what the
     * agent does. The configuration validator enforces a count floor for a single
     * benchmark; this is the same footgun at k of them, which the validator
     * cannot see because it does not know how many will be compared.
     *
     * <p>A KEEP needs only one benchmark to clear the threshold, so this warns
     * only when none of them can.
     */
    static String unreachableAlphaWarning(List<Delta> deltas, int k) {
        if (deltas.isEmpty()) return null;
        int worstN = 0;
        double alpha = 0;
        for (Delta d : deltas) {
            double corrected = d.alpha() / k;
            if (MannWhitney.minAchievableP(d.nBase(), d.nCand()) < corrected) {
                return null; // this one can clear it, which is all a KEEP needs
            }
            int n = Math.min(d.nBase(), d.nCand());
            if (n > worstN) {
                worstN = n;
                alpha = d.alpha();
            }
        }
        double corrected = alpha / k;
        StringBuilder msg = new StringBuilder(String.format(Locale.ROOT,
                "no KEEP was reachable: comparing %d benchmark(s) corrects the significance threshold to"
                        + " %.5f, but with %d rounds per side the test cannot produce a p-value below %.5f"
                        + " however large the improvement is",
                k, corrected, worstN, MannWhitney.minAchievableP(worstN, worstN)));
        int need = countForAlpha(corrected);
        if (need > 0) {
            msg.append(" — raise count to at least ").append(need);
        } else {
            msg.append(" — raise count, or measure fewer benchmarks");
        }
        return msg.toString();
    }

    /**
     * The smallest number of rounds per side at which the test can produce a
     * p-value below alpha, or 0 when no practical count does. The search stops at
     * 50, far past any sensible benchmark budget.
     */
    static int countForAlpha(double alpha) {
        for (int n = 2; n <= 50; n++) {
            if (MannWhitney.minAchievableP(n, n) < alpha) return n;
        }
        return 0;
    }
}
