package io.github.g4lb.autor3search.verdict;

import io.github.g4lb.autor3search.bench.Delta;

import java.util.List;

/**
 * The harness's answer for one experiment.
 *
 * @param regressions the benchmarks that tripped the guard, when one did
 * @param warnings    what says the measurement behind this result is too weak to
 *                    support it. They never change the decision — they say what
 *                    it can and cannot mean.
 */
public record VerdictResult(
        Status status,
        Reason reason,
        double score,
        String message,
        List<Delta> regressions,
        List<String> warnings) {

    /** A result for a correctness gate that failed before anything was measured. */
    public static VerdictResult gate(Status status, Reason reason, String message) {
        return new VerdictResult(status, reason, 0, message, List.of(), List.of());
    }

    public int exitCode() {
        return status.exitCode();
    }
}
