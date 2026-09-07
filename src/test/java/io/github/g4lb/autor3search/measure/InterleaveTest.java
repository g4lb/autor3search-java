package io.github.g4lb.autor3search.measure;

import io.github.g4lb.autor3search.bench.BenchSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterleaveTest {

    private static Interleave.Round constant(String label, List<String> order, double value) {
        return round -> {
            order.add(label);
            BenchSet s = new BenchSet();
            s.record("a.B.run", "a.B.run", BenchSet.UNIT_TIME, value);
            return s;
        };
    }

    @Test
    void collectsOneObservationPerSidePerRound() throws IOException {
        List<String> order = new ArrayList<>();
        Interleave.Result r = Interleave.run(4, false,
                constant("base", order, 100), constant("cand", order, 50), null);
        assertEquals(4, r.base().values("a.B.run", BenchSet.UNIT_TIME).length);
        assertEquals(4, r.candidate().values("a.B.run", BenchSet.UNIT_TIME).length);
        assertEquals(100, r.base().values("a.B.run", BenchSet.UNIT_TIME)[0], 1e-9);
        assertEquals(50, r.candidate().values("a.B.run", BenchSet.UNIT_TIME)[0], 1e-9);
    }

    /**
     * A fixed order within each round leaves the candidate permanently in the later
     * slot, so any drift monotonic across a round lands on it in the same direction
     * every time — a constant bias averaging cannot remove, which shifts the score
     * the KEEP threshold is compared against.
     */
    @Test
    void alternatesWhichSideRunsFirst() throws IOException {
        List<String> order = new ArrayList<>();
        Interleave.run(4, false, constant("base", order, 100), constant("cand", order, 50), null);
        assertEquals(List.of("base", "cand", "cand", "base", "base", "cand", "cand", "base"), order);
    }

    /**
     * The half-done version of the alternation — swapping the run order without
     * swapping the results back — silently exchanges the two sides on half the
     * rounds and inverts the score.
     */
    @Test
    void attributesResultsToTheRightSideOnEveryRound() throws IOException {
        List<String> order = new ArrayList<>();
        Interleave.Result r = Interleave.run(6, false,
                constant("base", order, 100), constant("cand", order, 50), null);
        for (double v : r.base().values("a.B.run", BenchSet.UNIT_TIME)) {
            assertEquals(100, v, 1e-9, "a baseline observation was attributed to the candidate");
        }
        for (double v : r.candidate().values("a.B.run", BenchSet.UNIT_TIME)) {
            assertEquals(50, v, 1e-9, "a candidate observation was attributed to the baseline");
        }
    }

    @Test
    void aWarmupRoundIsRunAndDiscarded() throws IOException {
        List<String> order = new ArrayList<>();
        Interleave.Result r = Interleave.run(4, true,
                constant("base", order, 100), constant("cand", order, 50), null);
        assertEquals(10, order.size(), "5 rounds x 2 sides should have been run");
        assertEquals(4, r.base().values("a.B.run", BenchSet.UNIT_TIME).length, "the first is discarded");
    }

    @Test
    void refusesFewerThanTwoRounds() {
        assertThrows(IOException.class,
                () -> Interleave.run(1, false, r -> new BenchSet(), r -> new BenchSet(), null));
    }

    @Test
    void namesTheSideAndRoundThatFailed() {
        Interleave.Round ok = r -> new BenchSet();
        Interleave.Round boom = r -> {
            throw new IOException("the JVM died");
        };
        IOException e = assertThrows(IOException.class, () -> Interleave.run(4, false, ok, boom, null));
        assertTrue(e.getMessage().startsWith("candidate round 0:"), e.getMessage());
    }

    @Test
    void stopsBetweenRoundsWhenCancelled() {
        List<String> order = new ArrayList<>();
        boolean[] cancelled = {false};
        Interleave.Round base = round -> {
            order.add("base");
            cancelled[0] = true; // the human reaches for the brake during round 0
            BenchSet s = new BenchSet();
            s.record("a.B.run", "a.B.run", BenchSet.UNIT_TIME, 1);
            return s;
        };
        assertThrows(Interleave.Cancelled.class,
                () -> Interleave.run(4, false, base, constant("cand", order, 1), () -> cancelled[0]));
        assertTrue(order.size() <= 2, "no round should have started after the cancel");
    }
}
