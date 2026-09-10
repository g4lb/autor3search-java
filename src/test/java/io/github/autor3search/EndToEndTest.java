package io.github.autor3search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.autor3search.cli.Capture;
import io.github.autor3search.cli.ExitCodes;
import io.github.autor3search.config.Config;
import io.github.autor3search.git.Git;
import io.github.autor3search.results.Results;
import io.github.autor3search.results.Row;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.RunState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole journey through the real commands, against a real Maven project with
 * a real JMH benchmark: init, baseline, a no-op experiment, a genuine
 * optimization, report, and a stop.
 *
 * <p>Everything else in this suite stubs the build tool and the measurement, for
 * good reasons — but that means nothing else proves the harness can actually
 * drive Maven, that JMH's JSON is shaped the way the parser expects, or that a
 * real 50% improvement comes out the other end as a KEEP. This is the test that
 * would have caught a silently wrong classpath, a renamed profiler metric, or an
 * annotation processor that never ran.
 *
 * <p>It is minutes of wall time, so the benchmark is configured down to the
 * smallest settings the significance test still permits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndTest {

    private static final String TAG = "e2e";
    private static final String MAIN = "src/main/java/demo/WordCount.java";
    private static final String TEST = "src/test/java/demo/WordCountTest.java";

    @TempDir
    static Path work;

    private static Path repo;
    private static String baselineCommit;

    private static int cli(String... args) {
        Capture.Output out = Capture.run(() -> Main.dispatch(args));
        // The transcript is what makes a failure here diagnosable at all.
        System.out.println("$ autor3search-java " + String.join(" ", args));
        System.out.println(out.out());
        if (!out.err().isBlank()) System.out.println("[stderr] " + out.err());
        lastOutput = out;
        return out.code();
    }

    private static Capture.Output lastOutput;

    @Test
    @Order(1)
    void initDiscoversTheBenchmarkAndWritesTheRunFiles() throws IOException {
        Assumptions.assumeTrue(onPath("mvn"), "maven is not on PATH");
        Assumptions.assumeFalse(Boolean.getBoolean("autor3search.skipE2E"), "end-to-end tests disabled");

        Path demo = Path.of("testdata", "demo");
        Assumptions.assumeTrue(Files.isDirectory(demo), "the demo project is missing");

        repo = work.resolve("repo");
        copyTree(demo, repo);
        deleteTree(repo.resolve("target"));
        TestRepo.init(repo).commit("initial");

        assertEquals(ExitCodes.OK, cli("init", "-C", repo.toString()));
        assertTrue(lastOutput.out().contains("demo.WordCountBenchmark.count"), lastOutput.out());

        // Configure the measurement down to the fastest settings the significance
        // test still permits, so the suite finishes in minutes rather than an hour.
        Path config = repo.resolve(Config.PATH);
        Files.writeString(config, Files.readString(config)
                .replace("count: 10", "count: 4")
                .replace("warmup_iterations: 5", "warmup_iterations: 1")
                .replace("measurement_iterations: 5", "measurement_iterations: 1")
                .replace("benchtime: 1s", "benchtime: 200ms"), StandardCharsets.UTF_8);
        TestRepo.init(repo); // re-assert the identity; init() is idempotent on an existing repo
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "autor3search-java init");
    }

    @Test
    @Order(2)
    void doctorReportsOnTheMachineAndAlwaysSucceeds() {
        Assumptions.assumeTrue(repo != null);
        assertEquals(ExitCodes.OK, cli("doctor", "-C", repo.toString()));
        assertTrue(lastOutput.out().contains("git repo:"), lastOutput.out());
        assertTrue(lastOutput.out().contains("build:"), lastOutput.out());
    }

    @Test
    @Order(3)
    void baselineCreatesTheBranchFreezesSourcesAndPinsAWorktree() throws IOException {
        Assumptions.assumeTrue(repo != null);
        assertEquals(ExitCodes.OK, cli("baseline", "-C", repo.toString(), "-tag", TAG));

        assertEquals("autor3search-java/" + TAG, Git.currentBranch(repo));
        Path stateDir = RunState.stateDir(repo, TAG);
        Baseline base = Baseline.load(stateDir.resolve(RunState.BASELINE_FILE));
        baselineCommit = base.commit;
        assertEquals(base.commit, base.measureCommit);
        assertEquals("maven", base.buildTool);
        assertEquals(List.of("demo.WordCountBenchmark.count"), base.benchmarks);
        assertTrue(Files.isDirectory(stateDir.resolve(RunState.WORKTREE_NAME)));
        assertEquals(base.commit, Git.headCommit(stateDir.resolve(RunState.WORKTREE_NAME)));
        // The frozen store holds the test and the benchmark, not the main source.
        assertTrue(Files.exists(stateDir.resolve("frozen").resolve(TEST)));
        assertTrue(Files.exists(stateDir.resolve("frozen")
                .resolve("src/test/java/demo/WordCountBenchmark.java")));
    }

    @Test
    @Order(4)
    void aRealOptimizationIsKeptAndAdvancesTheMeasurementBaseline() throws IOException {
        Assumptions.assumeTrue(repo != null);
        Files.writeString(repo.resolve(MAIN), """
                package demo;

                import java.util.HashMap;
                import java.util.Map;

                public final class WordCount {

                    private WordCount() {}

                    public static Map<String, Integer> count(String s) {
                        Map<String, Integer> counts = new HashMap<>(64);
                        StringBuilder word = new StringBuilder(32);
                        int n = s.length();
                        int i = 0;
                        while (i < n) {
                            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                            word.setLength(0);
                            while (i < n && !Character.isWhitespace(s.charAt(i))) {
                                char c = s.charAt(i++);
                                if (c >= 'A' && c <= 'Z') {
                                    c += 'a' - 'A';
                                }
                                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                                    word.append(c);
                                }
                            }
                            if (word.length() > 0) {
                                counts.merge(word.toString(), 1, Integer::sum);
                            }
                        }
                        return counts;
                    }
                }
                """, StandardCharsets.UTF_8);
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "byte loop and a StringBuilder");
        String candidate = Git.headCommit(repo);

        int code = cli("eval", "-C", repo.toString(), "--json", "-desc", "stringbuilder");
        assertEquals(ExitCodes.OK, code, "a real 2x improvement must be KEPT:\n" + lastOutput.out()
                + lastOutput.err());

        JsonObject verdict = JsonParser.parseString(lastOutput.out()).getAsJsonObject();
        assertEquals("KEEP", verdict.get("status").getAsString());
        assertEquals("improved", verdict.get("reason").getAsString());
        assertTrue(verdict.get("score").getAsDouble() < 0.9, "score was " + verdict.get("score"));
        assertEquals(1, verdict.getAsJsonArray("deltas").size());
        // The allocation hint travels alongside the scored deltas.
        assertEquals(1, verdict.getAsJsonArray("bytes_deltas").size());

        // The measurement baseline advanced to the commit just kept; the frozen
        // anchor did not move.
        Path stateDir = RunState.stateDir(repo, TAG);
        Baseline base = Baseline.load(stateDir.resolve(RunState.BASELINE_FILE));
        assertEquals(candidate, base.measureCommit);
        assertEquals(baselineCommit, base.commit);
        assertEquals(candidate, Git.headCommit(stateDir.resolve(RunState.WORKTREE_NAME)));

        List<Row> rows = Results.load(repo.resolve(Results.PATH));
        assertEquals(1, rows.size());
        assertEquals("keep", rows.get(0).status());
        assertEquals("stringbuilder", rows.get(0).description());
        assertTrue(rows.get(0).bytesDelta() < 0, "the allocation hint should show a reduction");
    }

    @Test
    @Order(5)
    void aWeakenedTestIsRestoredRatherThanObeyed() throws IOException {
        Assumptions.assumeTrue(repo != null);
        String original = Files.readString(repo.resolve(TEST));
        Files.writeString(repo.resolve(TEST),
                "package demo;\n\nclass WordCountTest {\n    // gutted\n}\n", StandardCharsets.UTF_8);
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "weaken the test");

        cli("eval", "-C", repo.toString(), "--json", "-desc", "weaken-test");
        assertEquals(original, Files.readString(repo.resolve(TEST)),
                "the frozen test must have been restored");

        Git.git(repo, "reset", "--hard", "-q", "HEAD~1");
    }

    @Test
    @Order(6)
    void aNewBenchmarkSourceIsRejected() throws IOException {
        Assumptions.assumeTrue(repo != null);
        Files.writeString(repo.resolve("src/test/java/demo/EasyBenchmark.java"), """
                package demo;
                import org.openjdk.jmh.annotations.Benchmark;
                public class EasyBenchmark {
                    @Benchmark
                    public int easy() { return 1; }
                }
                """, StandardCharsets.UTF_8);
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "add an easier benchmark");

        assertEquals(ExitCodes.FAIL, cli("eval", "-C", repo.toString(), "--json", "-desc", "easy-bench"));
        JsonObject verdict = JsonParser.parseString(lastOutput.out()).getAsJsonObject();
        assertEquals("new_test_file", verdict.get("reason").getAsString());

        Git.git(repo, "reset", "--hard", "-q", "HEAD~1");
    }

    @Test
    @Order(7)
    void statusAndReportDescribeTheRun() throws IOException {
        Assumptions.assumeTrue(repo != null);
        assertEquals(ExitCodes.OK, cli("status", "-C", repo.toString()));
        assertTrue(lastOutput.out().contains("run tag        " + TAG), lastOutput.out());
        assertTrue(lastOutput.out().contains("advanced past the baseline"), lastOutput.out());
        assertTrue(lastOutput.out().contains("idle — between experiments"), lastOutput.out());

        assertEquals(ExitCodes.OK, cli("report", "-C", repo.toString()));
        assertTrue(lastOutput.out().contains("cumulative speedup:"), lastOutput.out());
        assertTrue(lastOutput.out().contains("stringbuilder"), lastOutput.out());
    }

    @Test
    @Order(8)
    void aStopRequestIsReportedBackWithTheNextVerdict() throws IOException {
        Assumptions.assumeTrue(repo != null);
        assertEquals(ExitCodes.OK, cli("stop", "-C", repo.toString()));

        // A no-op experiment: measured against itself, so the verdict is a DISCARD —
        // but the stop must be reported alongside it, and the verdict must still stand.
        Files.writeString(repo.resolve(MAIN),
                Files.readString(repo.resolve(MAIN)) + "\n// a comment, and nothing else\n",
                StandardCharsets.UTF_8);
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "a comment");

        cli("eval", "-C", repo.toString(), "--json", "-desc", "no-op");
        JsonObject verdict = JsonParser.parseString(lastOutput.out()).getAsJsonObject();
        assertTrue(verdict.get("stop_requested").getAsBoolean(), lastOutput.out());
        // The verdict still stands alongside the stop: a graceful stop never
        // invalidates the experiment that was already under way.
        assertNotEquals("ABORTED", verdict.get("status").getAsString(), lastOutput.out());

        assertEquals(ExitCodes.OK, cli("stop", "-C", repo.toString(), "-clear"));

        // Every invocation appends exactly one row, KEEP or not — including the two
        // that a gate rejected. A long trail of honest failures is the point of the
        // log, so none of them may be silently absent.
        List<Row> rows = Results.load(repo.resolve(Results.PATH));
        assertEquals(List.of("stringbuilder", "weaken-test", "easy-bench", "no-op"),
                rows.stream().map(Row::description).toList());
        assertEquals("keep", rows.get(0).status());
        // The weakened test was RESTORED, not argued with — so that experiment was
        // then judged on its merits like any other, and what it actually changed was
        // nothing. It is not a FAIL; tampering with a frozen file simply wastes an
        // experiment slot, which is exactly what program.md warns the agent about.
        assertNotEquals("fail", rows.get(1).status());
        // The new benchmark source is the one the frozen-set gate rejects outright.
        assertEquals("fail", rows.get(2).status());
    }

    // --- helpers -------------------------------------------------------------

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
