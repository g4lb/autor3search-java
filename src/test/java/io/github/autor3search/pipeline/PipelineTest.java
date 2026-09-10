package io.github.autor3search.pipeline;

import io.github.autor3search.bench.BenchSet;
import io.github.autor3search.config.Config;
import io.github.autor3search.git.Git;
import io.github.autor3search.measure.Interleave;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.RunState;
import io.github.autor3search.verdict.Reason;
import io.github.autor3search.verdict.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTest {

    private static final double[] SLOW = {100, 101, 99, 100, 102, 98};
    private static final double[] FAST = {50, 51, 49, 50, 52, 48};

    // --- the happy paths -----------------------------------------------------

    @Test
    void keepsARealImprovementAndReportsBothMetrics(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 2; } }\n").commit("faster");

        Pipeline.Outcome out = f.eval(SLOW, FAST);
        assertEquals(Status.KEEP, out.verdict().status());
        assertEquals(Reason.IMPROVED, out.verdict().reason());
        assertEquals(0.5, out.verdict().score(), 0.02);
        assertEquals(1, out.measurements().time().size());
        assertEquals(PipelineFixture.BENCHMARK, out.measurements().time().get(0).name());
    }

    @Test
    void discardsAChangeThatDidNothing(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 1; } // comment\n}\n").commit("no-op");
        Pipeline.Outcome out = f.eval(SLOW, SLOW);
        assertEquals(Status.DISCARD, out.verdict().status());
        assertEquals(Reason.NO_IMPROVEMENT, out.verdict().reason());
    }

    /**
     * Without the measurement baseline advancing, every later experiment is
     * compared against the run's ORIGINAL commit forever — so a no-op that merely
     * fails to regress an earlier win banks as a KEEP it did not earn.
     */
    @Test
    void aKeepAdvancesTheMeasurementBaselineToTheCommitJustKept(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        String frozenAnchor = f.base.commit;
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 2; } }\n");
        String kept = f.repo.commit("faster");

        assertEquals(Status.KEEP, f.eval(SLOW, FAST).verdict().status());

        assertEquals(kept, f.base.measureCommit, "the in-memory record must advance");
        assertEquals(kept, Git.headCommit(f.worktree()), "the pinned worktree must follow it");
        assertEquals(kept, Baseline.load(f.stateDir.resolve(RunState.BASELINE_FILE)).measureCommit,
                "and the change must be persisted");
        assertEquals(frozenAnchor, f.base.commit, "the frozen anchor must never move");
    }

    @Test
    void aDiscardLeavesTheMeasurementBaselineWhereItWas(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        String anchor = f.base.measureCommit;
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 1; } //x\n}\n").commit("no-op");
        f.eval(SLOW, SLOW);
        assertEquals(anchor, f.base.measureCommit);
        assertEquals(anchor, Git.headCommit(f.worktree()));
    }

    // --- the scope gate ------------------------------------------------------

    @Test
    void rejectsAnEditOutsideTheDeclaredScope(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.cfg.scope = java.util.List.of("./src/main/java/...");
        f.repo.write("tools/helper.java", "class Helper {}\n").commit("edit outside scope");

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.SCOPE, out.verdict().reason());
        assertTrue(out.verdict().message().contains("tools/helper.java"));
        assertNull(out.measurements());
    }

    /**
     * A dependency change is a supply-chain decision a human makes, and it would
     * change WHAT is measured rather than how fast it runs — so it is refused
     * regardless of how permissive the scope is.
     */
    @Test
    void rejectsABuildFileEvenWhenTheScopeIsTheWholeRepository(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        assertEquals(java.util.List.of("./..."), f.cfg.scope);
        f.repo.write("pom.xml", "<project><!-- swapped a dependency --></project>\n").commit("bump a dep");

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.SCOPE, out.verdict().reason());
        assertTrue(out.verdict().message().contains("human decision"), out.verdict().message());
    }

    /**
     * The scope gate diffs against the FROZEN anchor, not the advancing one. An
     * out-of-scope edit must be re-caught on every later evaluation, not given one
     * evaluation in which to be noticed and then absorbed into accepted state.
     */
    @Test
    void reCatchesAnOutOfScopeEditAfterAKeepHasAdvancedTheBaseline(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 2; } }\n").commit("faster");
        assertEquals(Status.KEEP, f.eval(SLOW, FAST).verdict().status());

        // Narrow the scope only now, and make an edit outside it. Anchored to the
        // advancing pointer, the earlier accepted commit would no longer be looked at.
        f.cfg.scope = java.util.List.of("./src/main/java/...");
        f.repo.write("tools/helper.java", "class Helper {}\n").commit("out of scope");
        assertEquals(Reason.SCOPE, f.eval(PipelineFixture.NEVER_MEASURED).verdict().reason());
    }

    @Test
    void harnessOwnedFilesAreNotScopeChecked(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.cfg.scope = java.util.List.of("./src/main/java/...");
        f.writeUncommitted("results.tsv", "commit\tscore\n");
        f.writeUncommitted(Pipeline.RUN_LOG_NAME, "noise\n");
        assertEquals(Status.DISCARD, f.eval(SLOW, SLOW).verdict().status());
    }

    // --- config integrity ----------------------------------------------------

    @Test
    void rejectsAConfigThatChangedSinceBaseline(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        Path config = f.repo.root().resolve(Config.PATH);
        Files.writeString(config, Files.readString(config).replace("max_regress_pct: 5",
                "max_regress_pct: 90"), StandardCharsets.UTF_8);

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.CONFIG_CHANGED, out.verdict().reason());
    }

    // --- the frozen set ------------------------------------------------------

    @Test
    void restoresAnEditedTestRatherThanArguingWithIt(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        String original = Files.readString(f.repo.root().resolve(PipelineFixture.TEST_FILE));
        f.repo.write(PipelineFixture.TEST_FILE, "package demo;\nclass WordCountTest { /* gutted */ }\n")
                .commit("weaken the test");

        f.eval(SLOW, SLOW);
        assertEquals(original, Files.readString(f.repo.root().resolve(PipelineFixture.TEST_FILE)));
    }

    @Test
    void restoresADeletedBenchmark(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        String original = Files.readString(f.repo.root().resolve(PipelineFixture.BENCH_FILE));
        Files.delete(f.repo.root().resolve(PipelineFixture.BENCH_FILE));
        f.repo.commit("delete the benchmark");

        f.eval(SLOW, SLOW);
        assertEquals(original, Files.readString(f.repo.root().resolve(PipelineFixture.BENCH_FILE)));
    }

    @Test
    void rejectsANewBenchmarkSource(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write("src/test/java/demo/EasyBenchmark.java", """
                package demo;
                import org.openjdk.jmh.annotations.Benchmark;
                public class EasyBenchmark {
                    @Benchmark
                    public int easy() { return 1; }
                }
                """).commit("add an easier benchmark");

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.NEW_TEST_FILE, out.verdict().reason());
        assertTrue(out.verdict().message().contains("EasyBenchmark"), out.verdict().message());
    }

    /**
     * A benchmark parked in main sources is still the definition of what "faster"
     * means, so it is frozen too — and adding one is caught the same way.
     */
    @Test
    void rejectsANewBenchmarkHiddenInMainSources(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write("src/main/java/demo/SneakyBenchmark.java", """
                package demo;
                import org.openjdk.jmh.annotations.Benchmark;
                public class SneakyBenchmark {
                    @Benchmark
                    public int easy() { return 1; }
                }
                """).commit("hide a benchmark in main");
        assertEquals(Reason.NEW_TEST_FILE, f.eval(PipelineFixture.NEVER_MEASURED).verdict().reason());
    }

    /** Moving the frozen sources somewhere else is simply undone: they come back. */
    @Test
    void restoresAFrozenTreeThatWasMovedAway(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        Path from = f.repo.root().resolve("src/test");
        Path to = f.repo.root().resolve("elsewhere");
        Files.move(from, to);
        f.repo.commit("move the tests");

        f.eval(SLOW, SLOW);
        assertTrue(Files.exists(f.repo.root().resolve(PipelineFixture.TEST_FILE)));
        assertTrue(Files.exists(f.repo.root().resolve(PipelineFixture.BENCH_FILE)));
    }

    /**
     * The frozen set and the tree must agree in BOTH directions. A manifest entry
     * that restore can write but discovery cannot see is a file that was restored
     * and would still not run — the run would keep scoring against a benchmark set
     * that no longer executes.
     *
     * <p>The state has to be constructed rather than provoked: with the symlink
     * hole closed there is no route an agent can still take to reach it. It is
     * kept, and tested, as the invariant that catches the next one.
     */
    @Test
    void rejectsAFrozenSourceThatDiscoveryCannotReach(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        // A frozen path under a directory the discovery walk deliberately skips.
        String hidden = "target/generated/src/test/java/demo/HiddenTest.java";
        Path store = f.stateDir.resolve("frozen").resolve(hidden);
        Files.createDirectories(store.getParent());
        Files.writeString(store, "package demo;\nclass HiddenTest {}\n", StandardCharsets.UTF_8);

        Path manifestPath = f.stateDir.resolve(io.github.autor3search.freeze.Freeze.MANIFEST_PATH);
        var manifest = io.github.autor3search.freeze.Manifest.load(manifestPath);
        manifest.files.put(hidden, io.github.autor3search.util.Hashes.sha256File(store));
        manifest.save(manifestPath);

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.MISSING_TEST_FILE, out.verdict().reason());
        assertTrue(out.verdict().message().contains("HiddenTest"), out.verdict().message());
    }

    @Test
    void rejectsAFrozenPathReplacedByASymlink(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        Path outside = dir.resolve("outside.java");
        Files.writeString(outside, "package demo;\nclass WordCountTest {}\n");
        Path test = f.repo.root().resolve(PipelineFixture.TEST_FILE);
        Files.delete(test);
        Files.createSymbolicLink(test, outside);

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.SYMLINK_SWAP, out.verdict().reason());
    }

    @Test
    void rejectsAFrozenStoreThatWasRewritten(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        // Rewrite both the working copy and the golden copy, so only the manifest
        // hash can still tell that the reference has been lost.
        String gutted = "package demo;\nclass WordCountTest { /* gutted */ }\n";
        f.repo.write(PipelineFixture.TEST_FILE, gutted).commit("weaken");
        Files.writeString(f.stateDir.resolve("frozen").resolve(PipelineFixture.TEST_FILE), gutted);

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.FROZEN_TAMPERED, out.verdict().reason());
        assertTrue(out.verdict().message().contains("fresh run"), out.verdict().message());
    }

    // --- correctness gates ---------------------------------------------------

    @Test
    void aFailedCompileCrashesRatherThanFails(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.tool.compileExit = 1;
        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.CRASH, out.verdict().status());
        assertEquals(Reason.BUILD, out.verdict().reason());
        assertTrue(out.verdict().message().contains("compile failed here"));
    }

    @Test
    void aFailedTestFailsAndNamesTheTest(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.tool.testExit = 1;
        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.TESTS, out.verdict().reason());
        assertTrue(out.verdict().message().contains("stripsPunctuation"));
    }

    @Test
    void aTimeoutInEitherPhaseCrashesAndSaysWhichOne(@TempDir Path dir) throws IOException {
        PipelineFixture compileTimeout = PipelineFixture.create(dir.resolve("a"));
        compileTimeout.tool.compileTimedOut = true;
        var out = compileTimeout.eval(PipelineFixture.NEVER_MEASURED).verdict();
        assertEquals(Status.CRASH, out.status());
        assertEquals(Reason.TIMEOUT, out.reason());
        assertTrue(out.message().contains("compile"), out.message());

        PipelineFixture testTimeout = PipelineFixture.create(dir.resolve("b"));
        testTimeout.tool.testTimedOut = true;
        var out2 = testTimeout.eval(PipelineFixture.NEVER_MEASURED).verdict();
        assertEquals(Reason.TIMEOUT, out2.reason());
        assertTrue(out2.message().contains("test run"), out2.message());
    }

    /**
     * An agent that made the BASELINE slow would see every candidate "improve".
     * The worktree's HEAD moving away from the recorded measurement commit is the
     * part of that this can detect.
     */
    @Test
    void rejectsAPinnedWorktreeThatNoLongerMatchesTheRecord(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        f.repo.write(PipelineFixture.MAIN_FILE, "package demo;\npublic class WordCount"
                + " { public static int c() { return 3; } }\n");
        String elsewhere = f.repo.commit("another commit");
        Git.checkoutDetached(f.worktree(), elsewhere);

        Pipeline.Outcome out = f.eval(PipelineFixture.NEVER_MEASURED);
        assertEquals(Status.FAIL, out.verdict().status());
        assertEquals(Reason.BASELINE_TAMPERED, out.verdict().reason());
    }

    // --- measurement ---------------------------------------------------------

    @Test
    void aMeasurementThatCouldNotRunCrashesWithItsOwnReason(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        Pipeline.Outcome out = f.eval(options -> {
            throw new IOException("JMH is not on this project's benchmark classpath");
        });
        assertEquals(Status.CRASH, out.verdict().status());
        assertEquals(Reason.MEASUREMENT, out.verdict().reason());
        assertTrue(out.verdict().message().contains("classpath"));
    }

    @Test
    void theAllocationHintIsOptionalAndNeverScored(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        // Bytes present only on the baseline side: the hint is unavailable, the
        // scored verdict is not affected.
        Pipeline.Outcome out = f.eval(options -> {
            BenchSet b = new BenchSet();
            BenchSet c = new BenchSet();
            for (double v : SLOW) {
                b.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_TIME, v);
                b.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_BYTES, 4096);
            }
            for (double v : FAST) {
                c.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_TIME, v);
            }
            return new Interleave.Result(b, c);
        });
        assertEquals(Status.KEEP, out.verdict().status());
        assertNull(out.measurements().bytes());
        assertNotNull(out.measurements().time());
    }

    @Test
    void carriesTheAllocationHintWhenBothSidesReportIt(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        Pipeline.Outcome out = f.eval(options -> {
            BenchSet b = new BenchSet();
            BenchSet c = new BenchSet();
            for (double v : SLOW) {
                b.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_TIME, v);
                b.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_BYTES, 4096 + v);
            }
            for (double v : FAST) {
                c.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_TIME, v);
                c.record(PipelineFixture.BENCHMARK, PipelineFixture.BENCHMARK, BenchSet.UNIT_BYTES, 1024 + v);
            }
            return new Interleave.Result(b, c);
        });
        assertEquals(1, out.measurements().bytes().size());
        assertTrue(out.measurements().bytes().get(0).pctChange() < -70);
    }

    @Test
    void reportsWorktreePinning(@TempDir Path dir) throws IOException {
        PipelineFixture f = PipelineFixture.create(dir);
        assertTrue(Pipeline.worktreePinned(f.stateDir));
        assertTrue(!Pipeline.worktreePinned(dir.resolve("nowhere")));
    }
}
