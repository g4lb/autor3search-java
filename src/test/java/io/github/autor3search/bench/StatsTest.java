package io.github.autor3search.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    private static BenchSet set(String name, String unit, double... values) {
        BenchSet s = new BenchSet();
        for (double v : values) s.record(name, name, unit, v);
        return s;
    }

    @Test
    void comparesMediansAndReportsTheRatio() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 100, 100, 100, 100);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 50, 50, 50, 50);
        Delta d = Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME);
        assertEquals(100, d.baseCenter(), 1e-9);
        assertEquals(50, d.candCenter(), 1e-9);
        assertEquals(0.5, d.ratio(), 1e-9);
        assertEquals(-50.0, d.pctChange(), 1e-9);
        assertEquals(4, d.nBase());
        assertEquals(4, d.nCand());
    }

    /**
     * The centre is the median, not the mean: one interfering round adds an
     * arbitrarily large outlier on the high side and none on the low side, and the
     * mean follows it.
     */
    @Test
    void aSingleOutlierDoesNotMoveTheCentre() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 100, 100, 100, 100);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 100, 100, 100, 100_000);
        Delta d = Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME);
        assertEquals(100, d.candCenter(), 1e-9);
    }

    @Test
    void carriesTheUnderpoweredSampleWarning() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 100, 101, 102, 103);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 50, 51, 52, 53);
        Delta d = Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME);
        assertEquals(1, d.warnings().size());
        assertTrue(d.warnings().get(0).contains("at least 6"));
    }

    @Test
    void sixRoundsPerSideCarriesNoWarning() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 100, 101, 102, 103, 104, 105);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 50, 51, 52, 53, 54, 55);
        assertTrue(Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME).warnings().isEmpty());
    }

    @Test
    void refusesFewerThanTwoObservations() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 100);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 50);
        assertThrows(BenchException.class, () -> Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME));
    }

    @Test
    void refusesAZeroBaselineBecauseNoRatioExists() {
        BenchSet base = set("a.B.run", BenchSet.UNIT_TIME, 0, 0, 0, 0);
        BenchSet cand = set("a.B.run", BenchSet.UNIT_TIME, 50, 50, 50, 50);
        BenchException e = assertThrows(BenchException.class,
                () -> Stats.compare(base, cand, "a.B.run", BenchSet.UNIT_TIME));
        assertTrue(e.getMessage().contains("median is zero"));
    }

    /**
     * A benchmark that disappears from the candidate fails the comparison outright.
     * Skipping it would let deleting the benchmark that was about to regress read
     * as a clean result.
     */
    @Test
    void aVanishedBenchmarkFailsTheWholeComparison() {
        BenchSet base = new BenchSet();
        BenchSet cand = new BenchSet();
        for (double v : new double[]{100, 101, 102, 103}) {
            base.record("a.B.one", "a.B.one", BenchSet.UNIT_TIME, v);
            base.record("a.B.two", "a.B.two", BenchSet.UNIT_TIME, v);
            cand.record("a.B.one", "a.B.one", BenchSet.UNIT_TIME, v);
        }
        BenchException e = assertThrows(BenchException.class,
                () -> Stats.compareAll(base, cand, BenchSet.UNIT_TIME));
        assertTrue(e.getMessage().contains("a.B.two"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot be checked for regressions"));
    }

    @Test
    void compareAllReturnsEveryBenchmarkSortedByName() {
        BenchSet base = new BenchSet();
        BenchSet cand = new BenchSet();
        for (double v : new double[]{100, 101, 102, 103}) {
            base.record("a.B.z", "a.B.z", BenchSet.UNIT_TIME, v);
            base.record("a.B.a", "a.B.a", BenchSet.UNIT_TIME, v);
            cand.record("a.B.z", "a.B.z", BenchSet.UNIT_TIME, v);
            cand.record("a.B.a", "a.B.a", BenchSet.UNIT_TIME, v);
        }
        List<Delta> deltas = Stats.compareAll(base, cand, BenchSet.UNIT_TIME);
        assertEquals(List.of("a.B.a", "a.B.z"), deltas.stream().map(Delta::name).toList());
    }

    @Test
    void compareAllRefusesAnEmptyIntersection() {
        assertThrows(BenchException.class,
                () -> Stats.compareAll(new BenchSet(), new BenchSet(), BenchSet.UNIT_TIME));
    }

    /**
     * Geometric, not arithmetic: a benchmark that halves and one that doubles have
     * cancelled out, and an arithmetic mean would call that pair a 25% regression.
     */
    @Test
    void geoMeanCancelsReciprocalRatios() {
        List<Delta> deltas = List.of(delta("a", 0.5), delta("b", 2.0));
        assertEquals(1.0, Stats.geoMean(deltas), 1e-12);
    }

    @Test
    void geoMeanOfOneRatioIsThatRatio() {
        assertEquals(0.75, Stats.geoMean(List.of(delta("a", 0.75))), 1e-12);
    }

    @Test
    void geoMeanRefusesAnEmptySet() {
        assertThrows(BenchException.class, () -> Stats.geoMean(List.of()));
    }

    @Test
    void geoMeanRefusesANonPositiveRatio() {
        assertThrows(BenchException.class, () -> Stats.geoMean(List.of(delta("a", 0))));
    }

    @Test
    void selectByBaseKeepsOnlyTheNamedBenchmarks() {
        BenchSet s = new BenchSet();
        s.record("a.B.run/size=big", "a.B.run", BenchSet.UNIT_TIME, 1);
        s.record("a.B.run/size=small", "a.B.run", BenchSet.UNIT_TIME, 2);
        s.record("a.C.run", "a.C.run", BenchSet.UNIT_TIME, 3);
        BenchSet selected = s.selectByBase(java.util.Set.of("a.B.run"));
        assertEquals(List.of("a.B.run/size=big", "a.B.run/size=small"), selected.names());
        assertFalse(selected.has("a.C.run", BenchSet.UNIT_TIME));
        // An empty selection keeps everything.
        assertEquals(3, s.selectByBase(java.util.Set.of()).names().size());
    }

    @Test
    void valuesAreDefensivelyCopied() {
        BenchSet s = set("a.B.run", BenchSet.UNIT_TIME, 1, 2, 3);
        double[] first = s.values("a.B.run", BenchSet.UNIT_TIME);
        first[0] = 999;
        assertEquals(1, s.values("a.B.run", BenchSet.UNIT_TIME)[0], 1e-9);
    }

    private static Delta delta(String name, double ratio) {
        return new Delta(name, BenchSet.UNIT_TIME, 1, ratio, ratio, (ratio - 1) * 100,
                0.01, 0.05, true, 10, 10, List.of());
    }
}
