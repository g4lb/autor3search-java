package io.github.g4lb.autor3search.measure;

import io.github.g4lb.autor3search.bench.BenchSet;

import java.io.IOException;

/**
 * Collects observations from a baseline and a candidate in an interleaved order.
 *
 * <p>Interleaving is the core measurement discipline of this harness. Comparing
 * a candidate measured now against a baseline measured minutes ago attributes
 * CPU thermal drift, frequency scaling and background load to the code change.
 * Alternating the two sides within a single session cancels that drift, because
 * both sides experience the same conditions.
 */
public final class Interleave {
    private Interleave() {}

    /** Produces one round of measurements for one side. */
    public interface Round {
        BenchSet measure(int round) throws IOException;
    }

    /** Answers whether the caller has been asked to stop between rounds. */
    public interface Cancellation {
        boolean cancelled();
    }

    /** Both sides' accumulated observations. */
    public record Result(BenchSet base, BenchSet candidate) {}

    /**
     * Runs base and candidate alternately for {@code rounds} rounds and
     * accumulates their observations. When {@code warmup} is true an extra
     * leading round is run and discarded, absorbing first-touch effects: a cold
     * page cache, a build tool's own start-up, and on a JVM the class loading and
     * JIT profile of the very first process.
     *
     * <p>The two sides SWAP ORDER on every round — base,cand then cand,base —
     * rather than always running base first. Alternating rounds alone cancels
     * drift BETWEEN rounds, but a fixed order within each round leaves a
     * systematic offset: the candidate would then always be measured one slot
     * later than the baseline, so any drift monotonic across a round (a CPU still
     * ramping toward thermal steady state, a background job starting mid-run)
     * lands on the candidate in the same direction every single time. Averaging
     * over rounds does not remove that, because it is not noise — it is a
     * constant bias, and it shifts the score the KEEP threshold is compared
     * against. Swapping makes each side lead half the time, cancelling the term
     * to first order.
     *
     * <p>An odd number of measured rounds cannot be split evenly and leaves one
     * round's worth of the offset behind; an even count — the default is 10 —
     * cancels it exactly.
     */
    public static Result run(int rounds, boolean warmup, Round base, Round cand, Cancellation cancel)
            throws IOException {
        if (rounds < 2) {
            throw new IOException("need at least 2 measured rounds, got " + rounds);
        }
        int total = warmup ? rounds + 1 : rounds;
        BenchSet baseSet = new BenchSet();
        BenchSet candSet = new BenchSet();
        for (int i = 0; i < total; i++) {
            if (cancel != null && cancel.cancelled()) {
                throw new Cancelled("measurement was cancelled after round " + i);
            }
            BenchSet b;
            BenchSet c;
            boolean candFirst = i % 2 == 1;
            if (candFirst) {
                c = attempt(cand, i, "candidate");
                b = attempt(base, i, "baseline");
            } else {
                b = attempt(base, i, "baseline");
                c = attempt(cand, i, "candidate");
            }
            if (warmup && i == 0) continue;
            baseSet.addAll(b);
            candSet.addAll(c);
        }
        return new Result(baseSet, candSet);
    }

    private static BenchSet attempt(Round r, int i, String label) throws IOException {
        try {
            return r.measure(i);
        } catch (IOException e) {
            throw new IOException(label + " round " + i + ": " + e.getMessage(), e);
        }
    }

    /**
     * A measurement the human stopped, distinguishable from one that failed.
     *
     * <p>The distinction is load-bearing rather than tidy: a cancelled benchmark
     * JVM exits non-zero exactly like a crashed one, and reporting a human
     * reaching for the brake as a broken build would send the agent off to fix
     * something that was never wrong.
     */
    public static final class Cancelled extends IOException {
        public Cancelled(String message) {
            super(message);
        }
    }
}
