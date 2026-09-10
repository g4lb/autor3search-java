package io.github.autor3search.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MannWhitneyTest {

    private static double[] seq(double... v) {
        return v;
    }

    /**
     * Two completely separated samples give the smallest p the test can produce
     * for that sample size, and that floor is {@code 2/C(n1+n2, n1)}. These are the
     * numbers the KEEP rule is compared against, so they are pinned exactly.
     */
    @Test
    void maximallySeparatedSamplesHitTheExactFloor() {
        assertEquals(2.0 / 6, MannWhitney.test(seq(1, 2), seq(10, 11)).p(), 1e-12);
        assertEquals(2.0 / 20, MannWhitney.test(seq(1, 2, 3), seq(10, 11, 12)).p(), 1e-12);
        assertEquals(2.0 / 70, MannWhitney.test(seq(1, 2, 3, 4), seq(10, 11, 12, 13)).p(), 1e-12);
        assertEquals(2.0 / 252, MannWhitney.test(seq(1, 2, 3, 4, 5), seq(10, 11, 12, 13, 14)).p(), 1e-12);
    }

    @Test
    void minAchievablePMatchesTheSameFormula() {
        assertEquals(0.33333, MannWhitney.minAchievableP(2, 2), 1e-5);
        assertEquals(0.10000, MannWhitney.minAchievableP(3, 3), 1e-5);
        assertEquals(0.02857, MannWhitney.minAchievableP(4, 4), 1e-5);
        assertEquals(0.00794, MannWhitney.minAchievableP(5, 5), 1e-5);
    }

    /**
     * The floor above 0.05 at three rounds per side is exactly why the config
     * refuses a count below four: nothing the agent does could ever be banked.
     */
    @Test
    void threeRoundsPerSideCanNeverReachTheDefaultAlpha() {
        assertTrue(MannWhitney.minAchievableP(3, 3) > MannWhitney.DEFAULT_ALPHA);
        assertTrue(MannWhitney.minAchievableP(4, 4) < MannWhitney.DEFAULT_ALPHA);
    }

    @Test
    void identicalSamplesAreNotSignificant() {
        MannWhitney.Result r = MannWhitney.test(seq(5, 5, 5, 5, 5), seq(5, 5, 5, 5, 5));
        assertEquals(1.0, r.p(), 1e-12);
        assertFalse(r.significant());
    }

    @Test
    void overlappingSamplesAreNotSignificant() {
        MannWhitney.Result r = MannWhitney.test(seq(1, 3, 5, 7, 9), seq(2, 4, 6, 8, 10));
        assertTrue(r.p() > 0.05, "p was " + r.p());
        assertFalse(r.significant());
    }

    @Test
    void usesTheExactDistributionWhenThereAreNoTies() {
        assertTrue(MannWhitney.test(seq(1, 2, 3, 4), seq(10, 11, 12, 13)).exact());
    }

    /** Ties break the exact enumeration's assumptions, so it falls back. */
    @Test
    void fallsBackToTheApproximationWhenSamplesTie() {
        MannWhitney.Result r = MannWhitney.test(seq(1, 2, 3, 4), seq(4, 11, 12, 13));
        assertFalse(r.exact());
        assertTrue(r.p() > 0 && r.p() <= 1);
    }

    @Test
    void fallsBackToTheApproximationForLargeSamples() {
        double[] a = new double[25];
        double[] b = new double[25];
        for (int i = 0; i < 25; i++) {
            a[i] = i;
            b[i] = 100 + i;
        }
        MannWhitney.Result r = MannWhitney.test(a, b);
        assertFalse(r.exact());
        assertTrue(r.p() < 1e-6, "p was " + r.p());
    }

    @Test
    void isSymmetricInItsArguments() {
        double[] a = seq(10, 12, 11, 13, 14, 10.5);
        double[] b = seq(20, 22, 21, 23, 24, 20.5);
        assertEquals(MannWhitney.test(a, b).p(), MannWhitney.test(b, a).p(), 1e-12);
    }

    @Test
    void reportsTheSampleSizesItWasGiven() {
        MannWhitney.Result r = MannWhitney.test(seq(1, 2, 3), seq(4, 5, 6, 7));
        assertEquals(3, r.n1());
        assertEquals(4, r.n2());
        assertEquals(MannWhitney.DEFAULT_ALPHA, r.alpha());
    }

    @Test
    void erfcMatchesKnownValues() {
        assertEquals(1.0, MannWhitney.erfc(0), 1e-7);
        assertEquals(0.157299, MannWhitney.erfc(1), 1e-5);
        assertEquals(0.004678, MannWhitney.erfc(2), 1e-5);
        assertEquals(2.0 - 0.157299, MannWhitney.erfc(-1), 1e-5);
    }

    @Test
    void binomialIsExactForTheSizesTheTestUses() {
        assertEquals(1, MannWhitney.binomial(5, 0), 1e-9);
        assertEquals(70, MannWhitney.binomial(8, 4), 1e-9);
        assertEquals(184756, MannWhitney.binomial(20, 10), 1e-6);
        assertEquals(0, MannWhitney.binomial(3, 4), 1e-9);
    }

    /**
     * The exact enumeration's counts must sum to the total number of orderings, or
     * every p-value drawn from them is scaled wrong.
     */
    @Test
    void exactDistributionIsNormalised() {
        // A p-value of exactly 1 for the median-most arrangement is what a correctly
        // normalised distribution produces; a mis-scaled one overshoots or undershoots.
        MannWhitney.Result r = MannWhitney.test(seq(1, 4, 5, 8), seq(2, 3, 6, 7));
        assertTrue(r.p() <= 1.0 && r.p() > 0.9, "p was " + r.p());
    }
}
