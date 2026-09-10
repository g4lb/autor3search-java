package io.github.autor3search.results;

/**
 * One logged experiment. Field order must be kept in step with
 * {@link Results#HEADER}, with the format string in {@code Results.append}, and
 * with the column indices in {@code Results.load}.
 *
 * @param commit         the commit the experiment was measured at
 * @param score          the geomean of per-benchmark ratios; 1.0 means no change
 * @param bestBenchDelta the largest single-benchmark improvement, in percent
 * @param bytesDelta     the change in B/op for that benchmark — a hint, never scored
 * @param status         keep, discard, fail, crash
 * @param description    a short human summary of what was tried
 */
public record Row(
        String commit,
        double score,
        double bestBenchDelta,
        double bytesDelta,
        String status,
        String description) {}
