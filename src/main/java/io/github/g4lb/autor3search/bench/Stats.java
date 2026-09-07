package io.github.g4lb.autor3search.bench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Turns two sets of observations into per-benchmark deltas and a single score. */
public final class Stats {
    private Stats() {}

    /** Compares one benchmark's unit across two measurement sets. */
    public static Delta compare(BenchSet base, BenchSet cand, String name, String unit) {
        double[] bv = base.values(name, unit);
        if (bv == null) throw new BenchException("baseline has no " + unit + " for " + name);
        double[] cv = cand.values(name, unit);
        if (cv == null) throw new BenchException("candidate has no " + unit + " for " + name);
        if (bv.length < 2 || cv.length < 2) {
            throw new BenchException(name + ": need at least 2 observations per side, got "
                    + bv.length + "/" + cv.length);
        }

        Sample bs = new Sample(bv);
        Sample cs = new Sample(cv);
        double baseCenter = bs.center();
        double candCenter = cs.center();
        if (baseCenter == 0) {
            throw new BenchException(name + ": baseline " + unit
                    + " median is zero, so no ratio can be formed against it");
        }
        MannWhitney.Result mw = MannWhitney.test(bv, cv);
        double ratio = candCenter / baseCenter;

        Set<String> warnings = new LinkedHashSet<>();
        warnings.addAll(bs.warnings());
        warnings.addAll(cs.warnings());

        return new Delta(name, unit, baseCenter, candCenter, ratio, (ratio - 1) * 100,
                mw.p(), mw.alpha(), mw.significant(), bv.length, cv.length, List.copyOf(warnings));
    }

    /**
     * Compares every benchmark present in both sets, sorted by name.
     *
     * <p>A benchmark measured at baseline but MISSING from the candidate fails the
     * whole comparison rather than being skipped: a benchmark that disappears
     * cannot be checked for regressions, so skipping it would let deleting the
     * benchmark that was about to regress read as a clean result.
     */
    public static List<Delta> compareAll(BenchSet base, BenchSet cand, String unit) {
        List<Delta> out = new ArrayList<>();
        Set<String> missing = new TreeSet<>();
        for (String name : base.names()) {
            if (!base.has(name, unit)) continue;
            if (!cand.has(name, unit)) {
                missing.add(name);
                continue;
            }
            out.add(compare(base, cand, name, unit));
        }
        if (!missing.isEmpty()) {
            throw new BenchException("benchmark(s) measured at baseline but missing from the candidate: "
                    + missing + " — a benchmark that disappears cannot be checked for regressions");
        }
        if (out.isEmpty()) {
            throw new BenchException("no benchmark reported " + unit + " on both the baseline and the candidate");
        }
        return out;
    }

    /**
     * The geometric mean of the deltas' ratios: the single number the agent
     * optimizes, below 1 for an overall speedup.
     *
     * <p>Geometric and not arithmetic because these are ratios. A benchmark that
     * halves (0.5) and one that doubles (2.0) have cancelled out exactly; their
     * arithmetic mean is 1.25, which would call that pair a regression.
     */
    public static double geoMean(List<Delta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            throw new BenchException("geometric mean of an empty delta set");
        }
        double sum = 0;
        for (Delta d : deltas) {
            if (d.ratio() <= 0) {
                throw new BenchException(d.name() + ": non-positive ratio " + d.ratio());
            }
            sum += Math.log(d.ratio());
        }
        return Math.exp(sum / deltas.size());
    }
}
