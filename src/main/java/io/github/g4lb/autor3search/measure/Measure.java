package io.github.g4lb.autor3search.measure;

import io.github.g4lb.autor3search.build.BuildTool;
import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.runner.Cancellation;
import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Measures a candidate tree against a baseline tree with real JMH invocations. */
public final class Measure {
    private Measure() {}

    /** Everything one measurement needs. */
    public record Options(
            Path baseTree,
            Path candTree,
            BuildTool tool,
            Config cfg,
            String pattern,
            Duration timeout,
            Appendable log,
            Cancellation cancel) {}

    /**
     * Compiles both trees, resolves both classpaths, then interleaves the rounds.
     *
     * <p>The compile and the classpath resolution happen ONCE per evaluation, not
     * once per round. They shell out to Maven or Gradle, which is slow and — more
     * importantly — variable: letting a build tool run between measured rounds
     * would put minutes of unrelated, unevenly distributed CPU work inside the
     * very interval the interleaving exists to hold constant.
     */
    public static Interleave.Result run(Options o) throws IOException {
        // Where the BUILD TOOL runs, which is the tree root for Gradle and the
        // module for Maven, and where the BENCHMARK JVM runs, which is the module
        // for both so that a benchmark reading a relative path finds the same files
        // its own module's tests would.
        Path baseBuildDir = o.tool().workingDir(o.baseTree());
        Path candBuildDir = o.tool().workingDir(o.candTree());
        Path baseModule = o.tool().moduleRoot(o.baseTree());
        Path candModule = o.tool().moduleRoot(o.candTree());

        ProcRunner baseBuild = new ProcRunner(baseBuildDir, o.timeout(), o.log(), o.cancel());
        ProcRunner candBuild = new ProcRunner(candBuildDir, o.timeout(), o.log(), o.cancel());

        // The candidate was already compiled by the correctness gate. The pinned
        // baseline worktree was not: it is a fresh checkout the first time, and
        // after every KEEP it is re-pointed at a newly kept commit.
        ProcResult baseCompile = o.tool().compile(o.baseTree(), baseBuild);
        if (!baseCompile.ok()) {
            throw new IOException("the pinned baseline worktree at " + o.baseTree()
                    + " does not compile (exit " + baseCompile.exitCode() + "):\n" + baseCompile.tail(30));
        }

        String baseClasspath = o.tool().benchClasspath(o.baseTree(), baseBuild);
        String candClasspath = o.tool().benchClasspath(o.candTree(), candBuild);

        ProcRunner baseRun = new ProcRunner(baseModule, o.timeout(), o.log(), o.cancel());
        ProcRunner candRun = new ProcRunner(candModule, o.timeout(), o.log(), o.cancel());

        Interleave.Round base = round -> Jmh.round(baseRun, baseModule, baseClasspath, o.pattern(), o.cfg());
        Interleave.Round cand = round -> Jmh.round(candRun, candModule, candClasspath, o.pattern(), o.cfg());
        return Interleave.run(o.cfg().count, true, base, cand,
                o.cancel() == null ? () -> false : o.cancel()::cancelled);
    }
}
