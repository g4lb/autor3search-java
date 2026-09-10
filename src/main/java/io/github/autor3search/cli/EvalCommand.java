package io.github.autor3search.cli;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.autor3search.bench.Delta;
import io.github.autor3search.build.BuildTool;
import io.github.autor3search.build.BuildTools;
import io.github.autor3search.config.Config;
import io.github.autor3search.git.Git;
import io.github.autor3search.measure.Interleave;
import io.github.autor3search.pipeline.Pipeline;
import io.github.autor3search.results.Results;
import io.github.autor3search.results.Row;
import io.github.autor3search.runner.Cancellation;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.EvalClaim;
import io.github.autor3search.state.RunState;
import io.github.autor3search.state.Stop;
import io.github.autor3search.verdict.Reason;
import io.github.autor3search.verdict.Status;
import io.github.autor3search.verdict.VerdictResult;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs one experiment and returns the verdict the agent's loop branches on. */
public final class EvalCommand {
    private EvalCommand() {}

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            // The verdict is read by an agent and by a human, never by a browser.
            // HTML escaping turns every apostrophe in a warning into \u0027.
            .disableHtmlEscaping()
            .serializeSpecialFloatingPointValues()
            .create();

    /**
     * How long the shutdown hook gives the main thread to finish reporting an
     * abort before the JVM halts underneath it.
     *
     * <p>Without the wait, a Ctrl+C would cancel the measurement and then exit
     * before anything was printed — the agent would see a killed process and no
     * verdict at all, which is the one output it cannot act on.
     */
    private static final long SHUTDOWN_GRACE_SECONDS = 20;

    /**
     * How often the evaluation checks whether it has been asked to abandon the
     * experiment. Fast enough that a human waiting on {@code stop -force} does not
     * notice it, and cheap enough to be irrelevant beside a benchmark round.
     */
    private static final long FORCE_POLL_MILLIS = 250;

    public static int run(String[] args) {
        Flags f = new Flags("eval");
        f.string("C", ".", "repository root (or a directory inside it)");
        f.bool("json", false, "print the verdict and deltas as a single JSON object and nothing else");
        f.string("desc", "", "short description of this experiment, recorded in results.tsv");
        f.bool("no-log", false, "write subprocess output to stdout instead of run.log");
        if (!f.parse(args)) return ExitCodes.USAGE;
        boolean jsonOut = f.flag("json");

        RunRef ref;
        Config cfg;
        Baseline base;
        try {
            // No -tag here, unlike `status` and `stop`: eval must run on the same run
            // branch baseline created, so there is no way to point it at another run
            // by mistake.
            ref = RunRef.resolve("eval", f.get("C"), null);
            cfg = ConfigLoading.load("eval", ref.root());
            base = Baseline.load(ref.stateDir().resolve(RunState.BASELINE_FILE));
        } catch (RunRef.ResolveException | ConfigLoading.LoadException | IOException e) {
            System.err.println("autor3search-java eval: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        // The baseline record is written before the worktree pin completes, so its
        // existence alone does not prove baseline finished: a run interrupted
        // between the two would otherwise pass this check and fail confusingly deep
        // inside the measurement instead.
        if (!Pipeline.worktreePinned(ref.stateDir())) {
            System.err.println("autor3search-java eval: the baseline worktree is missing at "
                    + ref.worktreeDir() + " — 'autor3search-java baseline -tag " + ref.tag() + "' did not"
                    + " finish pinning it (interrupted, or the directory was later removed). Re-run"
                    + " `autor3search-java baseline -tag " + ref.tag() + " -force`.");
            return ExitCodes.USAGE;
        }

        BuildTool tool;
        try {
            tool = BuildTools.forModule(ref.root(), cfg.buildTool, base.moduleDir);
        } catch (IOException e) {
            System.err.println("autor3search-java eval: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        // Claim the run for the duration. Two evaluations sharing one pinned
        // worktree would measure each other's checkouts; the claim also tells
        // `status` that an experiment is in flight and gives `stop -force` a process
        // to signal.
        try (EvalClaim claim = EvalClaim.acquire(ref.stateDir(), ProcessHandle.current().pid())) {
            return runClaimed(f, ref, cfg, base, tool, jsonOut);
        } catch (IOException e) {
            System.err.println("autor3search-java eval: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    private static int runClaimed(Flags f, RunRef ref, Config cfg, Baseline base, BuildTool tool,
                                  boolean jsonOut) {
        Cancellation cancel = new Cancellation();
        CountDownLatch reported = new CountDownLatch(1);

        // A stale force sentinel — left by a `stop -force` issued while nothing was
        // running — would otherwise abandon this experiment before it measured
        // anything. Clearing it here, after the claim is held, means only a force
        // aimed at THIS evaluation can reach it.
        try {
            Stop.clearForce(ref.stateDir());
        } catch (IOException e) {
            System.err.println("autor3search-java eval: " + e.getMessage());
            return ExitCodes.USAGE;
        }
        Thread forceWatch = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (Stop.forceRequested(ref.stateDir())) {
                    cancel.cancel();
                    return;
                }
                try {
                    Thread.sleep(FORCE_POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "autor3search-force-watch");
        forceWatch.setDaemon(true);
        forceWatch.start();
        Thread hook = new Thread(() -> {
            cancel.cancel();
            try {
                reported.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "autor3search-cancel");
        Runtime.getRuntime().addShutdownHook(hook);

        Writer logFile = null;
        try {
            Appendable log;
            if (f.flag("no-log")) {
                log = System.out;
            } else {
                // Subprocess output can be large; by default it goes to run.log rather
                // than being streamed, so an unattended agent never has its context
                // flooded by one experiment's transcript.
                logFile = Files.newBufferedWriter(ref.root().resolve(Pipeline.RUN_LOG_NAME),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                log = logFile;
            }

            Pipeline.Outcome outcome;
            try {
                outcome = Pipeline.eval(new Pipeline.Options(
                        ref.root(), ref.stateDir(), cfg, base, tool, log, cancel));
            } catch (Interleave.Cancelled e) {
                return reportAbort(ref, base, jsonOut);
            } catch (IOException e) {
                if (cancel.cancelled()) {
                    return reportAbort(ref, base, jsonOut);
                }
                // A thrown exception here means the harness itself malfunctioned — I/O,
                // git, a malformed baseline — not that the candidate was rejected. The
                // verdict exit codes would misreport that as a gate failure.
                System.err.println("autor3search-java eval: " + e.getMessage());
                return ExitCodes.USAGE;
            } catch (RuntimeException e) {
                // Measurements that cannot be compared or scored arrive here. Like an
                // I/O failure this is the harness malfunctioning rather than a verdict,
                // so it must not exit with a code the agent's loop reads as one.
                String message = e.getMessage();
                System.err.println("autor3search-java eval: "
                        + (message == null || message.isBlank() ? e.getClass().getName() : message));
                return ExitCodes.USAGE;
            }

            VerdictResult res = outcome.verdict();
            List<Delta> timeDeltas = outcome.measurements() == null ? List.of()
                    : outcome.measurements().time();
            List<Delta> bytesDeltas = outcome.measurements() == null || outcome.measurements().bytes() == null
                    ? List.of() : outcome.measurements().bytes();

            Delta best = bestDelta(timeDeltas);
            Path resultsPath = ref.root().resolve(Results.PATH);
            Results.append(resultsPath, new Row(
                    Git.headCommit(ref.root()),
                    res.score(),
                    best == null ? 0 : best.pctChange(),
                    // The B/op change for whichever benchmark had the best time delta.
                    // Allocation is never scored; this is purely the "why did it get
                    // faster" hint for the morning read.
                    bytesDeltaFor(bytesDeltas, best == null ? null : best.name()),
                    res.status().name().toLowerCase(Locale.ROOT),
                    f.get("desc")));

            // The stop request is read only now that the experiment is scored and
            // recorded. eval never REFUSES to run because a stop is pending: refusing
            // would throw away work the agent has already committed and leave that
            // commit with no verdict. The graceful stop is the agent's to act on,
            // after it has applied this verdict.
            Ctx ctx = runContext(ref, base, experimentNumber(resultsPath));

            if (jsonOut) {
                printJson(res, timeDeltas, bytesDeltas, ctx);
            } else {
                printHuman(res, timeDeltas, bytesDeltas, cfg, tool, ctx);
            }
            return res.exitCode();
        } catch (IOException e) {
            System.err.println("autor3search-java eval: " + e.getMessage());
            return ExitCodes.USAGE;
        } finally {
            if (logFile != null) {
                try {
                    logFile.close();
                } catch (IOException ignored) {
                    // The transcript is a convenience; a failure closing it must not
                    // change the verdict already printed.
                }
            }
            forceWatch.interrupt();
            reported.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // Already shutting down: the hook is running and has been released.
            }
        }
    }

    /**
     * Renders an evaluation whose measurement was cancelled mid-experiment.
     *
     * <p>No {@code results.tsv} row is written, deliberately: nothing was
     * measured, and a row there is the human's record of an experiment that
     * actually ran. The exit code is the FAIL code rather than a new one of its
     * own, so no existing contract changes — program.md already tells the agent to
     * treat a status it does not recognise the way it treats FAIL, which is
     * exactly right here: drop the commit, and leave the loop.
     */
    private static int reportAbort(RunRef ref, Baseline base, boolean jsonOut) {
        try {
            Stop.clearForce(ref.stateDir());
        } catch (IOException e) {
            // The sentinel outliving this process is harmless: the next eval clears
            // it before doing any work.
        }
        VerdictResult res = new VerdictResult(Status.ABORTED, Reason.STOP_FORCED, 0,
                "eval was interrupted before the experiment could be measured; nothing was recorded",
                List.of(), List.of());
        // An abort IS a stop, whether it came from `stop -force` or a bare Ctrl+C.
        // Reporting it as one means an agent interrupted by hand exits its loop
        // cleanly instead of starting another experiment.
        Ctx ctx = new Ctx(runContext(ref, base, experimentNumber(ref.root().resolve(Results.PATH))).run(),
                true);

        if (jsonOut) {
            printJson(res, List.of(), List.of(), ctx);
        } else {
            System.out.println();
            System.out.println(res.message());
            System.out.println("\nVERDICT: " + res.status());
        }
        return ExitCodes.FAIL;
    }

    /**
     * The "where am I" half of eval's output: which run, which branch, which
     * pinned worktree, how far into the loop, and whether the human has asked for
     * a stop.
     *
     * <p>It travels in the JSON because that is the ONLY channel the loop reads.
     * {@code eval --json} prints one object and nothing else by contract, so a
     * human-readable header would never reach the agent — and the human watching
     * the transcript learns where the run is only if the agent can restate it.
     */
    record RunCtx(String tag, String branch, String baselineCommit, String measureCommit,
                  String worktree, int experiment) {}

    /**
     * The run context plus the one field that is a SIBLING of the verdict rather
     * than part of it. {@code stopRequested} is kept out of {@link RunCtx} so it
     * lands at the top level of the JSON, next to {@code status}, where the loop
     * reads it — it says whether to continue, not what this experiment was worth.
     */
    record Ctx(RunCtx run, boolean stopRequested) {}

    private static Ctx runContext(RunRef ref, Baseline base, int experiment) {
        return new Ctx(new RunCtx(ref.tag(), ref.branch(), base.commit, base.measureCommit,
                ref.worktreeDir().toString(), experiment), Stop.requested(ref.stateDir()));
    }

    /**
     * The 1-based index of the experiment just recorded, or 0 when that cannot be
     * read. A wrong number would be worse than none.
     */
    private static int experimentNumber(Path resultsPath) {
        try {
            return Results.load(resultsPath).size();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * The most-improving time delta, or null when there are none — a gate failure
     * before measurement. That benchmark is treated as "the primary benchmark" for
     * the results log's bytes column.
     */
    static Delta bestDelta(List<Delta> deltas) {
        Delta best = null;
        for (Delta d : deltas) {
            if (best == null || d.pctChange() < best.pctChange()) best = d;
        }
        return best;
    }

    static double bytesDeltaFor(List<Delta> bytesDeltas, String name) {
        if (name == null) return 0;
        for (Delta d : bytesDeltas) {
            if (d.name().equals(name)) return d.pctChange();
        }
        return 0;
    }

    /** What {@code -json} prints: one object and nothing else, so the loop can grep it. */
    record JsonReport(String status, String reason, double score, String message,
                      List<Delta> regressions, List<String> warnings,
                      boolean stopRequested, RunCtx run,
                      List<Delta> deltas, List<Delta> bytesDeltas) {}

    private static void printJson(VerdictResult res, List<Delta> timeDeltas, List<Delta> bytesDeltas,
                                  Ctx ctx) {
        JsonReport report = new JsonReport(res.status().name(), res.reason().code(), res.score(),
                res.message(), res.regressions(), res.warnings(), ctx.stopRequested(), ctx.run(),
                timeDeltas, bytesDeltas);
        System.out.println(GSON.toJson(report));
    }

    /** One correctness gate, in the order the pipeline checks it. */
    private record GateStage(String label, Set<Reason> reasons) {}

    private static List<GateStage> gateStages(BuildTool tool) {
        return List.of(
                new GateStage("checking scope", EnumSet.of(Reason.SCOPE)),
                new GateStage("checking config integrity", EnumSet.of(Reason.CONFIG_CHANGED)),
                new GateStage("restoring frozen sources", EnumSet.of(Reason.NEW_TEST_FILE,
                        Reason.MISSING_TEST_FILE, Reason.SYMLINK_SWAP, Reason.FROZEN_TAMPERED)),
                new GateStage(tool.name() + " compile", EnumSet.of(Reason.BUILD)),
                new GateStage(tool.name() + " test", EnumSet.of(Reason.TESTS)),
                new GateStage("checking baseline worktree integrity", EnumSet.of(Reason.BASELINE_TAMPERED)),
                new GateStage("measuring vs baseline", EnumSet.of(Reason.MEASUREMENT)));
    }

    /**
     * The width the gate labels are padded to: the longest label plus a space.
     * Derived rather than hard-coded, because a hard-coded width is silently wrong
     * the moment a gate is added or renamed past it.
     */
    private static int gateColumn(List<GateStage> stages) {
        int widest = 0;
        for (GateStage s : stages) widest = Math.max(widest, s.label().length());
        return widest + 1;
    }

    private static void printHuman(VerdictResult res, List<Delta> timeDeltas, List<Delta> bytesDeltas,
                                   Config cfg, BuildTool tool, Ctx ctx) {
        System.out.printf("experiment %d on %s (measuring vs %s)%n%n",
                ctx.run().experiment(), ctx.run().branch(), ctx.run().measureCommit());
        List<GateStage> stages = gateStages(tool);
        int column = gateColumn(stages);
        int failedAt = -1;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).reasons().contains(res.reason())) {
                failedAt = i;
                break;
            }
        }
        if (res.reason() == Reason.TIMEOUT) {
            // The compile and the test run share one timeout reason; the message says
            // which of them ran out.
            failedAt = res.message().contains("compile") ? 3 : 4;
        }

        if (failedAt >= 0) {
            for (int i = 0; i < stages.size(); i++) {
                if (i < failedAt) {
                    System.out.printf("%-" + column + "s ok%n", stages.get(i).label());
                    continue;
                }
                System.out.printf("%-" + column + "s FAILED%n", stages.get(i).label());
                System.out.println(res.message());
                break;
            }
            System.out.println("\nVERDICT: " + res.status());
            printStopNotice(ctx);
            return;
        }

        for (GateStage s : stages) {
            System.out.printf("%-" + column + "s ok%n", s.label());
        }
        System.out.printf("bench x%d vs baseline x%d%n%n", cfg.count, cfg.count);

        List<Delta> sorted = new ArrayList<>(timeDeltas);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        // Indexed once rather than scanned per benchmark: pairing each scored
        // benchmark with its hint by a linear scan would be quadratic in the size of
        // the delta set.
        Map<String, Delta> bytes = new LinkedHashMap<>();
        for (Delta d : bytesDeltas) bytes.put(d.name(), d);
        // Mirrors the Bonferroni family size the verdict uses: the number of
        // benchmarks compared in this experiment.
        int k = sorted.size();
        double worst = 0;
        for (Delta d : sorted) {
            String note = "";
            if (!d.significant()) {
                // "significant" is always at the raw, uncorrected alpha.
                note = "  (not significant)";
            } else if (k > 1 && d.p() >= d.alpha() / k) {
                // Truthful even though it reads oddly: this benchmark IS significant at
                // alpha, just not at the stricter corrected bar a KEEP requires.
                note = String.format(Locale.ROOT, "  (significant at alpha, not at corrected alpha/%d)", k);
            }
            System.out.printf(Locale.ROOT, "%-40s %+6.1f%%  [p=%.3f n=%d]%s%n",
                    shorten(d.name()), d.pctChange(), d.p(), d.nCand(), note);
            Delta a = bytes.get(d.name());
            if (a != null) {
                System.out.printf(Locale.ROOT, "  B/op %34s %+6.1f%%  (%.0f -> %.0f)%n",
                        "", a.pctChange(), a.baseCenter(), a.candCenter());
            }
            worst = Math.max(worst, d.pctChange());
        }

        String guard = res.reason() == Reason.GUARD_REGRESSION ? "TRIPPED" : "OK";
        System.out.printf(Locale.ROOT,
                "%nSCORE  %.3f  (%+.1f%%)   min effect: %.1f%%   guard: max regress %+.1f%% < %.1f%% %s%n",
                res.score(), (res.score() - 1) * 100, cfg.minEffectPct, worst, cfg.maxRegressPct, guard);
        printWarnings(res.warnings());
        System.out.println("\nVERDICT: " + res.status());
        printStopNotice(ctx);
    }

    /** Trims a fully qualified benchmark name to its class and method, for a fixed column. */
    static String shorten(String name) {
        String[] parts = name.split("\\.");
        if (parts.length < 2) return name;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /**
     * Goes AFTER the verdict for the same reason warnings go before it: the
     * verdict is where a skimming reader stops, and this is the one thing they
     * should read past it for.
     */
    private static void printStopNotice(Ctx ctx) {
        if (!ctx.stopRequested()) return;
        System.out.println("\nSTOP REQUESTED: apply this verdict, then exit the loop.");
        System.out.println("To resume instead: autor3search-java stop -clear");
    }

    /**
     * Renders what qualifies the numbers above it. They go after the score they
     * are about but BEFORE the verdict line, because that is where a skimming
     * reader — or an agent grepping run.log — stops.
     */
    private static void printWarnings(List<String> warnings) {
        if (warnings.isEmpty()) return;
        System.out.println();
        for (String w : warnings) {
            System.out.println("WARNING: " + w);
        }
    }
}
