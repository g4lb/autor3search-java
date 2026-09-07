package io.github.g4lb.autor3search.verdict;

import io.github.g4lb.autor3search.bench.BenchSet;
import io.github.g4lb.autor3search.bench.Delta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictTest {

    private static Delta delta(String name, double pctChange, double p) {
        return delta(name, pctChange, p, 10);
    }

    private static Delta delta(String name, double pctChange, double p, int n) {
        double ratio = 1 + pctChange / 100;
        return new Delta(name, BenchSet.UNIT_TIME, 100, 100 * ratio, ratio, pctChange,
                p, 0.05, p < 0.05, n, n, List.of());
    }

    private static VerdictResult decide(List<Delta> deltas, double score) {
        return Verdict.decide(new Verdict.Input(deltas, score, 5.0, 1.0));
    }

    @Test
    void keepsARealSignificantImprovement() {
        VerdictResult r = decide(List.of(delta("a", -20, 0.001)), 0.80);
        assertEquals(Status.KEEP, r.status());
        assertEquals(Reason.IMPROVED, r.reason());
        assertEquals(0, r.exitCode());
    }

    @Test
    void discardsWhenNothingMoved() {
        VerdictResult r = decide(List.of(delta("a", -0.2, 0.8)), 0.998);
        assertEquals(Status.DISCARD, r.status());
        assertEquals(Reason.NO_IMPROVEMENT, r.reason());
        assertEquals(1, r.exitCode());
    }

    /**
     * A measured, significant win that is smaller than the minimum effect size is
     * reported as its own thing. "Your idea did nothing" and "your idea worked, by
     * less than we will bank" call for different next moves, and reporting the
     * second as the first tells the agent its change had no effect when it
     * measurably did.
     */
    @Test
    void distinguishesTooSmallFromNoEffect() {
        VerdictResult r = decide(List.of(delta("a", -0.5, 0.001)), 0.995);
        assertEquals(Status.DISCARD, r.status());
        assertEquals(Reason.BELOW_MIN_EFFECT, r.reason());
        assertTrue(r.message().contains("real improvement"), r.message());
    }

    @Test
    void aSignificantRegressionBeyondTheGuardRejectsHoweverGoodTheScore() {
        List<Delta> deltas = List.of(delta("fast", -40, 0.001), delta("slow", 12, 0.001));
        VerdictResult r = decide(deltas, 0.79);
        assertEquals(Status.DISCARD, r.status());
        assertEquals(Reason.GUARD_REGRESSION, r.reason());
        assertEquals(1, r.regressions().size());
        assertEquals("slow", r.regressions().get(0).name());
        assertTrue(r.message().contains("+12.0%"), r.message());
    }

    @Test
    void aRegressionWithinTheGuardDoesNotReject() {
        List<Delta> deltas = List.of(delta("fast", -40, 0.001), delta("slow", 3, 0.001));
        assertEquals(Status.KEEP, decide(deltas, 0.80).status());
    }

    /** An insignificant regression is noise, not harm, whatever its size. */
    @Test
    void anInsignificantRegressionDoesNotTripTheGuard() {
        List<Delta> deltas = List.of(delta("fast", -40, 0.001), delta("noisy", 30, 0.9));
        assertEquals(Status.KEEP, decide(deltas, 0.80).status());
    }

    /**
     * Testing k benchmarks against the same uncorrected alpha inflates the chance
     * that one looks significant by luck alone. The KEEP rule divides alpha by k;
     * a benchmark that clears 0.05 but not 0.05/k cannot carry a KEEP on its own.
     */
    @Test
    void keepRequiresTheBonferroniCorrectedThreshold() {
        List<Delta> four = new ArrayList<>();
        four.add(delta("a", -20, 0.04));   // significant at alpha, not at alpha/4 = 0.0125
        four.add(delta("b", -0.1, 0.9));
        four.add(delta("c", -0.1, 0.9));
        four.add(delta("d", -0.1, 0.9));
        assertEquals(Status.DISCARD, decide(four, 0.94).status());

        four.set(0, delta("a", -20, 0.01)); // now clears 0.0125
        assertEquals(Status.KEEP, decide(four, 0.94).status());
    }

    /**
     * The guard deliberately uses the RAW alpha. Bonferroni only makes it harder
     * to call something significant, and applying it to the guard would make real
     * regressions easier to miss — backwards from what a guard is for.
     */
    @Test
    void theGuardUsesTheUncorrectedAlpha() {
        List<Delta> deltas = List.of(
                delta("a", -30, 0.001), delta("b", -30, 0.001),
                delta("c", -30, 0.001), delta("harm", 20, 0.04));
        VerdictResult r = decide(deltas, 0.70);
        // 0.04 clears the raw 0.05 but not the corrected 0.0125; the guard still fires.
        assertEquals(Reason.GUARD_REGRESSION, r.reason());
    }

    @Test
    void carriesThePerComparisonWarningsWithoutDuplicating() {
        Delta a = new Delta("a", BenchSet.UNIT_TIME, 100, 80, 0.8, -20, 0.001, 0.05, true, 4, 4,
                List.of("too few rounds"));
        Delta b = new Delta("b", BenchSet.UNIT_TIME, 100, 80, 0.8, -20, 0.001, 0.05, true, 4, 4,
                List.of("too few rounds"));
        List<String> warnings = decide(List.of(a, b), 0.8).warnings();
        assertEquals(1, warnings.stream().filter(w -> w.equals("too few rounds")).count());
    }

    /**
     * With enough benchmarks the corrected threshold can fall below the smallest
     * p-value the test can produce at the configured count — at which point every
     * experiment discards no matter what the agent does. The config validator
     * cannot catch this, because it does not know how many benchmarks a run will
     * compare.
     */
    @Test
    void warnsWhenNoKeepWasReachableAtAll() {
        List<Delta> deltas = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            deltas.add(delta("b" + i, -20, 0.008, 5));
        }
        List<String> warnings = decide(deltas, 0.8).warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("no KEEP was reachable")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("raise count to at least")),
                warnings.toString());
    }

    @Test
    void doesNotWarnWhenAtLeastOneBenchmarkCouldClearTheBar() {
        List<Delta> deltas = List.of(delta("a", -20, 0.001, 10), delta("b", -1, 0.9, 10));
        assertFalse(decide(deltas, 0.9).warnings().stream()
                .anyMatch(w -> w.startsWith("no KEEP was reachable")));
    }

    @Test
    void gateResultsCarryNoScoreAndTheRightExitCodes() {
        VerdictResult fail = VerdictResult.gate(Status.FAIL, Reason.SCOPE, "out of scope");
        assertEquals(2, fail.exitCode());
        assertEquals(0.0, fail.score());
        assertEquals(3, VerdictResult.gate(Status.CRASH, Reason.BUILD, "boom").exitCode());
        assertEquals(2, VerdictResult.gate(Status.ABORTED, Reason.STOP_FORCED, "stopped").exitCode());
    }

    @Test
    void countForAlphaFindsTheSmallestUsableCount() {
        assertEquals(4, Verdict.countForAlpha(0.05));
        assertEquals(5, Verdict.countForAlpha(0.05 / 2));
        assertEquals(0, Verdict.countForAlpha(1e-40));
    }

    @Test
    void everyReasonHasAStableMachineReadableCode() {
        assertEquals("improvement_below_min_effect", Reason.BELOW_MIN_EFFECT.code());
        assertEquals("no_significant_improvement", Reason.NO_IMPROVEMENT.code());
        assertEquals("scope_violation", Reason.SCOPE.code());
        assertEquals("measurement_failed", Reason.MEASUREMENT.code());
    }
}
