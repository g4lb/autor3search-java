package io.github.g4lb.autor3search.pipeline;

import io.github.g4lb.autor3search.bench.BenchException;
import io.github.g4lb.autor3search.bench.BenchSet;
import io.github.g4lb.autor3search.bench.Delta;
import io.github.g4lb.autor3search.bench.Stats;
import io.github.g4lb.autor3search.build.BuildTool;
import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.discover.Discovery;
import io.github.g4lb.autor3search.freeze.Freeze;
import io.github.g4lb.autor3search.freeze.FreezeException;
import io.github.g4lb.autor3search.freeze.Manifest;
import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.measure.Interleave;
import io.github.g4lb.autor3search.measure.Measure;
import io.github.g4lb.autor3search.results.Results;
import io.github.g4lb.autor3search.runner.Cancellation;
import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;
import io.github.g4lb.autor3search.scope.ScopeMatcher;
import io.github.g4lb.autor3search.state.Baseline;
import io.github.g4lb.autor3search.state.RunState;
import io.github.g4lb.autor3search.util.Hashes;
import io.github.g4lb.autor3search.verdict.Reason;
import io.github.g4lb.autor3search.verdict.Status;
import io.github.g4lb.autor3search.verdict.Verdict;
import io.github.g4lb.autor3search.verdict.VerdictResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs one full evaluation: gate correctness, measure the candidate against the
 * pinned baseline, return a verdict.
 *
 * <p>It lives here rather than in the command layer so it is testable without a
 * process boundary — {@code eval} itself handles only flags, output formatting
 * and the exit code.
 */
public final class Pipeline {
    private Pipeline() {}

    /**
     * The harness-owned scratch log inside the repository root. Subprocess output
     * that could flood an unattended agent's context — the full transcript of the
     * compile, the test run and every benchmark round — is written here rather
     * than streamed to stdout. It is gitignored by {@code init} and is not part
     * of the score.
     */
    public static final String RUN_LOG_NAME = "run.log";

    /**
     * How the candidate is measured against the pinned baseline.
     *
     * <p>A seam, so the gates and the scoring rules can be exercised against
     * synthetic measurements. Every real caller passes {@link Measure#run}; a test
     * that wants to know what the pipeline does with a 40% improvement should not
     * have to spend four minutes producing a real one, and one that wants to know
     * what it does with a benchmark that vanished cannot produce that at all.
     */
    public interface Measurer {
        Interleave.Result measure(Measure.Options options) throws IOException;
    }

    /** Everything one {@link #eval} call needs. */
    public record Options(
            Path root,
            Path stateDir,
            Config cfg,
            Baseline base,
            BuildTool tool,
            Appendable log,
            Cancellation cancel,
            Measurer measurer) {

        /** The ordinary case: measure with real JMH invocations. */
        public Options(Path root, Path stateDir, Config cfg, Baseline base, BuildTool tool,
                       Appendable log, Cancellation cancel) {
            this(root, stateDir, cfg, base, tool, log, cancel, Measure::run);
        }
    }

    /**
     * Every unit compared for one experiment.
     *
     * <p>{@code time} is the scored metric: it is the only field
     * {@link Verdict#decide} ever sees, and the only one that can trip the
     * regression guard. {@code bytes} is reported purely as a hint — allocation is
     * the most common lead for what to try next on the JVM, and a human reading
     * {@code results.tsv} leans on it to see WHY something got faster — but it
     * never feeds the verdict. Keep that boundary: scoring bytes would silently
     * let allocation-only changes with no real latency improvement pass as KEEP.
     *
     * @param bytes null when the comparison itself failed; a missing hint is never
     *              a reason to discard an otherwise-valid experiment
     */
    public record Measurements(List<Delta> time, List<Delta> bytes) {}

    /** A verdict plus what was measured to reach it. */
    public record Outcome(VerdictResult verdict, Measurements measurements) {}

    /**
     * Gates correctness, measures, scores.
     *
     * <p>Returns a terminal verdict for every gate outcome and every completed
     * measurement. A thrown exception means the HARNESS malfunctioned — I/O, git,
     * a malformed baseline — not that the candidate was rejected.
     */
    public static Outcome eval(Options o) throws IOException {
        Duration timeout = o.cfg().timeoutDuration();

        // 1. Scope. Checked before anything is restored or built, so an
        //    out-of-scope edit is reported as itself rather than as a build error.
        //
        // Deliberately diffs against the FROZEN anchor and NOT the advancing
        // measurement commit. Anchoring the scope gate to the run's true starting
        // point means it re-validates the FULL accumulated diff on every single
        // evaluation, rather than trusting that anything already banked as a KEEP
        // must have been in scope. Anchoring it to the advancing pointer instead
        // would give an out-of-scope edit exactly one evaluation in which to be
        // caught: past that single check it would become part of the "already
        // accepted" state and would never be looked at again.
        List<String> changed = Git.changedSince(o.root(), o.base().commit);
        ScopeMatcher matcher = new ScopeMatcher(o.cfg().scope);
        for (String rel : changed) {
            if (o.tool().isDependencyFile(rel)) {
                return gate(Status.FAIL, Reason.SCOPE, rel + " may not be modified: a change to the build or"
                        + " its dependencies is a human decision, not an autonomous one — and it would change"
                        + " WHAT is being measured, not just how fast it runs");
            }
            if (rel.equals(Results.PATH) || rel.equals(RUN_LOG_NAME) || rel.equals(Config.PATH)) {
                // Harness output, plus the human-owned config, which is
                // integrity-checked below rather than scope-checked.
                continue;
            }
            if (Discovery.isTestPath(rel)) {
                continue; // handled by restore, not by the scope gate
            }
            if (!matcher.match(rel)) {
                return gate(Status.FAIL, Reason.SCOPE,
                        rel + " is outside the allowed scope " + o.cfg().scope);
            }
        }

        // 1b. Config integrity. The config lives in the repository because humans
        //     own it, which means the agent can reach it. Raising max_regress_pct
        //     or deleting entries from the benchmark set would defeat the guard, so
        //     the file is hashed at baseline and any change fails the run.
        String cfgSum = Hashes.sha256File(o.root().resolve(Config.PATH));
        if (!cfgSum.equals(o.base().configSha256)) {
            return gate(Status.FAIL, Reason.CONFIG_CHANGED, Config.PATH
                    + " changed since baseline — the scoring rules are fixed for a run; revert it, or start"
                    + " a new run with 'autor3search-java baseline'");
        }

        // 2. Restore the frozen sources. Agent edits are erased, not argued with.
        Manifest manifest = Manifest.load(o.stateDir().resolve(Freeze.MANIFEST_PATH));
        List<String> restored;
        try {
            restored = Freeze.restore(o.root(), o.stateDir().resolve(Freeze.STORE_DIR), manifest);
        } catch (FreezeException e) {
            return switch (e.kind()) {
                // Tampering, not a harness malfunction: it earns a FAIL row in
                // results.tsv and an actionable message rather than aborting the
                // harness with no signal at all.
                case SYMLINK -> gate(Status.FAIL, Reason.SYMLINK_SWAP, e.getMessage()
                        + " — a frozen source file, and every directory on the way to it, must remain a"
                        + " regular file and real directories; restore them and rerun");
                // Unlike a symlink this cannot be undone by fixing the working tree,
                // because the reference copy is the thing that was lost. The only
                // honest recovery is a new baseline.
                case STORE_TAMPERED -> gate(Status.FAIL, Reason.FROZEN_TAMPERED, e.getMessage()
                        + " — the frozen copy this run scores against was modified, so its tests can no"
                        + " longer be trusted. Start a fresh run with 'autor3search-java baseline'.");
                case ESCAPES_ROOT -> gate(Status.FAIL, Reason.FROZEN_TAMPERED, e.getMessage());
            };
        }
        if (!restored.isEmpty() && o.log() != null) {
            o.log().append("restored " + restored.size() + " frozen file(s): " + restored + "\n");
        }

        // 2b. The frozen set and what is on disk must agree in BOTH directions.
        //
        // Restore only rewrites files it froze, and the scope gate above skips
        // every test path, so without this an agent could ADD a brand-new test or
        // benchmark source — an easier benchmark, or a file shadowing a frozen one
        // — and neither gate would notice.
        //
        // The reverse direction matters just as much and is easier to miss: a file
        // Restore just rewrote should always be visible to the walker again, so a
        // manifest entry MISSING from what is present means the walk could not
        // reach it. Discovery skips build-output and dot-prefixed directories, so a
        // structural change can hide a frozen benchmark from discovery while
        // leaving it nominally restored. Checking only the "extra file" direction
        // would let that pass silently, with the run still scoring against a
        // benchmark set that no longer runs.
        List<String> present = Discovery.frozenFiles(o.root(), o.cfg().unfreeze);
        Set<String> seen = new TreeSet<>(present);
        List<String> added = new ArrayList<>();
        for (String rel : present) {
            if (!manifest.files.containsKey(rel)) added.add(rel);
        }
        if (!added.isEmpty()) {
            return gate(Status.FAIL, Reason.NEW_TEST_FILE, "test or benchmark sources not present at"
                    + " baseline: " + added + " — the benchmark set is frozen; add them before running"
                    + " 'autor3search-java baseline', or list them in the config's unfreeze");
        }
        List<String> missing = new ArrayList<>();
        for (String rel : new TreeSet<>(manifest.files.keySet())) {
            if (!seen.contains(rel)) missing.add(rel);
        }
        if (!missing.isEmpty()) {
            return gate(Status.FAIL, Reason.MISSING_TEST_FILE, "frozen sources are no longer discoverable"
                    + " in the working tree: " + missing + " — they were restored, but the walk that finds"
                    + " them cannot reach them, so they would not run. Check for a directory on their path"
                    + " that was replaced, renamed, or moved under a build-output or dot-prefixed name.");
        }

        ProcRunner runner = new ProcRunner(o.tool().workingDir(o.root()), timeout, o.log(), o.cancel());

        // 3. Compile.
        ProcResult compile = o.tool().compile(o.root(), runner);
        if (compile.timedOut()) {
            return gate(Status.CRASH, Reason.TIMEOUT, "the compile timed out after " + o.cfg().timeout);
        }
        if (!compile.ok()) {
            return gate(Status.CRASH, Reason.BUILD, compile.tail(30));
        }

        // 4. Tests. Correctness is never traded for speed.
        ProcResult test = o.tool().test(o.root(), runner);
        if (test.timedOut()) {
            return gate(Status.CRASH, Reason.TIMEOUT, "the test run timed out after " + o.cfg().timeout);
        }
        if (!test.ok()) {
            return gate(Status.FAIL, Reason.TESTS, test.tail(40));
        }

        // 4b. Baseline worktree integrity. An agent could edit the pinned baseline
        // worktree in place to make the BASELINE itself slow, after which every
        // candidate "improves" and every experiment returns KEEP without optimizing
        // anything. Detect that by checking the worktree's HEAD still matches the
        // recorded measurement commit.
        //
        // This is a DETECTION, not a prevention, and only a partial one: the state
        // directory lives outside the repository, but the agent runs as the same OS
        // user, so nothing stops it editing the worktree in place, and this catches
        // that only if HEAD itself moves. An attacker who edits the worktree's
        // tracked files WITHOUT moving HEAD is not caught here at all, nor is one
        // who also rewrites the recorded measure commit to match. Treat it as
        // catching accidental clobbering and a careless tamper, not as a guarantee.
        Path worktreeDir = o.stateDir().resolve(RunState.WORKTREE_NAME);
        String worktreeHead = Git.headCommit(worktreeDir);
        if (!worktreeHead.equals(o.base().measureCommit)) {
            return gate(Status.FAIL, Reason.BASELINE_TAMPERED, "the pinned baseline worktree's HEAD is "
                    + worktreeHead + " but the recorded measurement commit is " + o.base().measureCommit
                    + " — the worktree no longer matches the baseline and this run's measurements cannot"
                    + " be trusted. Start a fresh run with 'autor3search-java baseline'.");
        }

        // 5. Measure, interleaved against the pinned baseline worktree.
        Interleave.Result measured;
        try {
            measured = o.measurer().measure(new Measure.Options(worktreeDir, o.root(), o.tool(),
                    o.cfg(), o.base().pattern, timeout, o.log(), o.cancel()));
        } catch (Interleave.Cancelled e) {
            throw e;
        } catch (IOException e) {
            // A cancel that lands DURING a round, rather than between two, surfaces
            // here as an ordinary subprocess failure: the benchmark JVM was killed,
            // so it exited non-zero like any crash would. Checking the brake before
            // classifying the failure is what stops a human reaching for `stop
            // -force` from being reported to the agent as a broken build — which it
            // would then try to fix.
            if (o.cancel() != null && o.cancel().cancelled()) {
                throw new Interleave.Cancelled(
                        "measurement was cancelled: " + e.getMessage());
            }
            // A measurement that could not run is CRASH, like a failed build — but
            // with its own reason, because "the compile failed" and "the benchmark
            // would not start" send an agent to entirely different places.
            return gate(Status.CRASH, Reason.MEASUREMENT, e.getMessage());
        }

        // 6. Score. Time is the scored metric: any failure here — including a
        // benchmark that vanished from the candidate — fails the whole call.
        List<Delta> timeDeltas = Stats.compareAll(measured.base(), measured.candidate(), BenchSet.UNIT_TIME);
        double score = Stats.geoMean(timeDeltas);

        // Bytes is informational only. compareAll is strict about a benchmark
        // disappearing from one side, which is right for the scored metric but
        // wrong here: a benchmark that legitimately allocates nothing on one side,
        // or a GC profiler that reported nothing for it, must not fail a real and
        // correctly-measured experiment over a missing hint.
        List<Delta> bytesDeltas;
        try {
            bytesDeltas = Stats.compareAll(measured.base(), measured.candidate(), BenchSet.UNIT_BYTES);
        } catch (BenchException e) {
            bytesDeltas = null;
            if (o.log() != null) {
                o.log().append("B/op comparison unavailable, continuing without it: " + e.getMessage() + "\n");
            }
        }

        VerdictResult result = Verdict.decide(new Verdict.Input(
                timeDeltas, score, o.cfg().maxRegressPct, o.cfg().minEffectPct));

        // 7. Advance the measurement baseline on KEEP. Without this, every
        // experiment after the first kept one is measured against the run's
        // ORIGINAL commit forever, so a later no-op that merely fails to regress an
        // EARLIER improvement still banks as KEEP. Re-pointing the pinned worktree
        // makes the next evaluation answer "did THIS change help".
        if (result.status() == Status.KEEP) {
            advanceMeasurementBaseline(o, worktreeDir);
        }

        return new Outcome(result, new Measurements(timeDeltas, bytesDeltas));
    }

    private static Outcome gate(Status status, Reason reason, String message) {
        return new Outcome(VerdictResult.gate(status, reason, message), null);
    }

    /**
     * Re-points the pinned baseline worktree at the candidate's own commit and
     * persists that commit as the new measurement anchor, after a KEEP.
     *
     * <p>A failure here is thrown rather than folded into the verdict: per
     * {@link #eval}'s contract that means the harness malfunctioned, and every
     * caller already treats that as a reason to stop rather than record a row and
     * continue — which is exactly right. Continuing against a worktree that no
     * longer agrees with the recorded measurement commit would silently corrupt
     * every subsequent measurement in the run, which is precisely the class of bug
     * this advance exists to fix. Should it fail after the worktree has moved but
     * before the new commit is persisted, the NEXT evaluation's worktree-integrity
     * check catches the mismatch and fails loudly rather than measuring against it.
     */
    private static void advanceMeasurementBaseline(Options o, Path worktreeDir) throws IOException {
        String newCommit = Git.headCommit(o.root());
        Git.checkoutDetached(worktreeDir, newCommit);
        o.base().measureCommit = newCommit;
        o.base().save(o.stateDir().resolve(RunState.BASELINE_FILE));
    }

    /** Whether the pinned worktree for a run exists. */
    public static boolean worktreePinned(Path stateDir) {
        return Files.isDirectory(stateDir.resolve(RunState.WORKTREE_NAME));
    }
}
