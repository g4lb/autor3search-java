package io.github.autor3search.cli;

import io.github.autor3search.results.Results;
import io.github.autor3search.results.Row;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.EvalClaim;
import io.github.autor3search.state.RunState;
import io.github.autor3search.state.Stop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Answers "where is this run, and how do I stop it" without touching anything.
 *
 * <p>It exists because the loop's own output cannot answer that question. The
 * agent runs {@code eval --json}, whose contract is one JSON object and nothing
 * else, so there is no human-readable channel to print a header on; and the human
 * watching may not be reading the agent's transcript at all. This is the command
 * they run in their own shell, at any moment, from any branch.
 *
 * <p>Read-only, and always exits 0 once it has a run to describe: a human
 * checking on a run must never be the reason a state file changes.
 */
public final class StatusCommand {
    private StatusCommand() {}

    private static final String FMT = "%-14s %s%n";

    public static int run(String[] args) {
        Flags f = new Flags("status");
        f.string("C", ".", "repository root (or a directory inside it)");
        f.string("tag", "", "run tag, when the current branch is not the run branch");
        if (!f.parse(args)) return ExitCodes.USAGE;

        RunRef ref;
        Baseline base;
        try {
            ref = RunRef.resolve("status", f.get("C"), f.get("tag"));
            base = Baseline.load(ref.stateDir().resolve(RunState.BASELINE_FILE));
        } catch (RunRef.ResolveException | IOException e) {
            System.err.println("autor3search-java status: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        String runBranch = RunRef.BRANCH_PREFIX + ref.tag();
        System.out.printf(FMT, "run tag", ref.tag());
        if (ref.branch().equals(runBranch)) {
            System.out.printf(FMT, "branch", runBranch + "  (checked out)");
        } else {
            // Worth shouting about: the agent commits to the run branch, so a human
            // standing somewhere else is not looking at the run's tree.
            System.out.printf(FMT, "branch",
                    runBranch + "  (NOT checked out — you are on " + ref.branch() + ")");
        }
        System.out.printf(FMT, "baseline", base.commit + "  (run started here)");
        printMeasuringAgainst(base);
        System.out.printf(FMT, "build", base.buildTool + "  (module " + base.moduleDir + ")");
        printWorktree(ref);
        printExperiments(ref);
        printLoopState(ref);
        printStopState(ref);
        return ExitCodes.OK;
    }

    /**
     * Renders the ADVANCING measurement pointer, which is the single most misread
     * number in a run: the score always answers "did this experiment beat the last
     * KEEP", never "is the tree better than when the run started". Naming how far
     * it has moved from the frozen anchor makes that visible instead of implied.
     */
    private static void printMeasuringAgainst(Baseline base) {
        if (base.measureCommit.equals(base.commit)) {
            System.out.printf(FMT, "measuring vs",
                    base.measureCommit + "  (still the baseline — nothing kept yet)");
            return;
        }
        System.out.printf(FMT, "measuring vs",
                base.measureCommit + "  (advanced past the baseline by earlier KEEPs)");
    }

    private static void printWorktree(RunRef ref) {
        Path path = ref.worktreeDir();
        if (!Files.isDirectory(path)) {
            System.out.printf(FMT, "worktree", path + "  (MISSING — re-run `autor3search-java baseline -tag "
                    + ref.tag() + " -force`)");
            return;
        }
        System.out.printf(FMT, "worktree", path.toString());
    }

    /**
     * Answers "how far into the loop is it" from the same file {@code report}
     * reads. A missing or unreadable file is reported as such rather than as zero
     * experiments: silently claiming a run has done nothing would be worse than
     * admitting the count is unavailable.
     */
    private static void printExperiments(RunRef ref) {
        List<Row> rows;
        try {
            rows = Results.load(ref.root().resolve(Results.PATH));
        } catch (IOException e) {
            System.out.printf(FMT, "experiments", "unavailable (" + e.getMessage() + ")");
            return;
        }
        if (rows.isEmpty()) {
            System.out.printf(FMT, "experiments", "none yet");
            return;
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Row r : rows) counts.merge(r.status(), 1, Integer::sum);
        System.out.printf(FMT, "experiments", String.format(Locale.ROOT,
                "%d run  (%d keep, %d discard, %d fail, %d crash)  — next is #%d",
                rows.size(), counts.getOrDefault("keep", 0), counts.getOrDefault("discard", 0),
                counts.getOrDefault("fail", 0), counts.getOrDefault("crash", 0), rows.size() + 1));
    }

    /**
     * Says whether an experiment is in flight right now, which is what tells a
     * human whether a plain {@code stop} will be acted on in seconds or after a
     * long benchmark finishes.
     */
    private static void printLoopState(RunRef ref) {
        try {
            EvalClaim.State s = EvalClaim.running(ref.stateDir());
            if (s.running()) {
                System.out.printf(FMT, "eval",
                        "running (pid " + s.pid() + ") — an experiment is being measured");
            } else {
                System.out.printf(FMT, "eval", "idle — between experiments");
            }
        } catch (IOException e) {
            System.out.printf(FMT, "eval", "unknown (" + e.getMessage() + ")");
        }
    }

    private static void printStopState(RunRef ref) {
        if (Stop.requested(ref.stateDir())) {
            System.out.printf(FMT, "stop", "requested — the agent will exit the loop at its next verdict");
            System.out.println();
            System.out.println("to cancel the stop:  autor3search-java stop -clear");
            System.out.println("to stop sooner:      autor3search-java stop -force");
            return;
        }
        System.out.printf(FMT, "stop", "not requested");
        System.out.println();
        System.out.println("to stop after the current experiment:  autor3search-java stop");
        System.out.println("to stop now, abandoning it:            autor3search-java stop -force");
    }
}
