package io.github.g4lb.autor3search.pipeline;

import io.github.g4lb.autor3search.TestRepo;
import io.github.g4lb.autor3search.bench.BenchSet;
import io.github.g4lb.autor3search.build.BuildTool;
import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.config.ConfigRenderer;
import io.github.g4lb.autor3search.discover.Discovery;
import io.github.g4lb.autor3search.freeze.Freeze;
import io.github.g4lb.autor3search.freeze.Manifest;
import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.measure.Interleave;
import io.github.g4lb.autor3search.measure.Jmh;
import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;
import io.github.g4lb.autor3search.state.Baseline;
import io.github.g4lb.autor3search.state.RunState;
import io.github.g4lb.autor3search.util.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A run set up exactly as {@code baseline} leaves one: a repository on a run
 * branch, a frozen store, a baseline record and a pinned worktree — but with the
 * build tool and the measurement stubbed out.
 *
 * <p>Stubbing both is what makes the gates testable at all. The real ones spend
 * minutes in Maven and in forked benchmark JVMs, and neither can be persuaded to
 * produce a 40% improvement, a vanished benchmark, or a compile failure on demand
 * — which are precisely the inputs the decisions under test are made from.
 */
public final class PipelineFixture {

    public static final String BENCHMARK = "demo.WordCountBenchmark.count";
    static final String BENCH_FILE = "src/test/java/demo/WordCountBenchmark.java";
    static final String TEST_FILE = "src/test/java/demo/WordCountTest.java";
    static final String MAIN_FILE = "src/main/java/demo/WordCount.java";

    public final TestRepo repo;
    public final Path stateDir;
    public final Config cfg;
    public final Baseline base;
    public final StubBuildTool tool = new StubBuildTool();

    private PipelineFixture(TestRepo repo, Path stateDir, Config cfg, Baseline base) {
        this.repo = repo;
        this.stateDir = stateDir;
        this.cfg = cfg;
        this.base = base;
    }

    /** Builds and pins a run, the way {@code baseline} would. */
    public static PipelineFixture create(Path dir) throws IOException {
        TestRepo repo = TestRepo.init(dir.resolve("repo"));
        repo.write(MAIN_FILE, "package demo;\npublic class WordCount { public static int c() { return 1; } }\n");
        repo.write(TEST_FILE, "package demo;\nclass WordCountTest {}\n");
        repo.write(BENCH_FILE, """
                package demo;
                import org.openjdk.jmh.annotations.Benchmark;
                public class WordCountBenchmark {
                    @Benchmark
                    public int count() { return WordCount.c(); }
                }
                """);
        repo.write("pom.xml", "<project/>\n");

        Config cfg = Config.defaults();
        cfg.benchmarks = List.of(BENCHMARK);
        cfg.count = 6;
        repo.write(Config.PATH, ConfigRenderer.render(cfg));
        repo.write(".gitignore", ".autor3search/*\n!.autor3search/config.yaml\nresults.tsv\nrun.log\n");
        repo.commit("initial");

        Path stateDir = dir.resolve("state");
        Files.createDirectories(stateDir);
        Git.createBranch(repo.root(), "autor3search-java/t");

        Manifest manifest = Freeze.snapshot(repo.root(), stateDir.resolve(Freeze.STORE_DIR),
                Discovery.frozenFiles(repo.root(), cfg.unfreeze));
        manifest.save(stateDir.resolve(Freeze.MANIFEST_PATH));

        String commit = Git.headCommit(repo.root());
        Baseline base = new Baseline();
        base.tag = "t";
        base.branch = "autor3search-java/t";
        base.commit = commit;
        base.measureCommit = commit;
        base.createdAt = Instant.now().toString();
        base.benchmarks = cfg.benchmarks;
        base.pattern = Jmh.pattern(cfg.benchmarks);
        base.configSha256 = Hashes.sha256File(repo.root().resolve(Config.PATH));
        base.buildTool = "stub";
        base.moduleDir = ".";
        base.save(stateDir.resolve(RunState.BASELINE_FILE));

        Git.addWorktree(repo.root(), stateDir.resolve(RunState.WORKTREE_NAME), commit);
        return new PipelineFixture(repo, stateDir, cfg, base);
    }

    public Path worktree() {
        return stateDir.resolve(RunState.WORKTREE_NAME);
    }

    /** Runs one evaluation with a measurement that reports the given per-round timings. */
    public Pipeline.Outcome eval(double[] baseNs, double[] candNs) throws IOException {
        return eval(options -> {
            BenchSet b = new BenchSet();
            BenchSet c = new BenchSet();
            for (double v : baseNs) b.record(BENCHMARK, BENCHMARK, BenchSet.UNIT_TIME, v);
            for (double v : candNs) c.record(BENCHMARK, BENCHMARK, BenchSet.UNIT_TIME, v);
            return new Interleave.Result(b, c);
        });
    }

    public Pipeline.Outcome eval(Pipeline.Measurer measurer) throws IOException {
        return Pipeline.eval(new Pipeline.Options(
                repo.root(), stateDir, cfg, base, tool, null, null, measurer));
    }

    /** A measurement that must never be reached, for the gate tests. */
    public static final Pipeline.Measurer NEVER_MEASURED = options -> {
        throw new AssertionError("a gate should have rejected this before any measurement");
    };

    /** A build tool that answers however a test needs it to, without shelling out. */
    public static final class StubBuildTool implements BuildTool {
        public int compileExit;
        public boolean compileTimedOut;
        public int testExit;
        public boolean testTimedOut;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public String moduleDir() {
            return ".";
        }

        @Override
        public String describe() {
            return "stub build tool";
        }

        @Override
        public ProcResult compile(Path treeRoot, ProcRunner runner) {
            return result(compileExit, compileTimedOut, "compile failed here");
        }

        @Override
        public ProcResult test(Path treeRoot, ProcRunner runner) {
            return result(testExit, testTimedOut, "WordCountTest.stripsPunctuation FAILED");
        }

        @Override
        public String benchClasspath(Path treeRoot, ProcRunner runner) {
            return "stub-classpath";
        }

        @Override
        public boolean isDependencyFile(String rel) {
            return rel.equals("pom.xml") || rel.endsWith("/pom.xml");
        }

        private static ProcResult result(int exit, boolean timedOut, String message) {
            return new ProcResult(List.of("stub"), "", exit == 0 ? "" : message, exit, timedOut,
                    Duration.ofMillis(1));
        }
    }

    /** Writes a file in the repository without committing it. */
    public void writeUncommitted(String rel, String content) throws IOException {
        Path p = repo.root().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
