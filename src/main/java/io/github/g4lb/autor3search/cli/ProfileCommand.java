package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.build.BuildTool;
import io.github.g4lb.autor3search.build.BuildTools;
import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.discover.Benchmark;
import io.github.g4lb.autor3search.discover.Discovery;
import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.measure.Jmh;
import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the declared benchmarks under JMH's own profilers and prints where the
 * time and the allocations actually go.
 *
 * <p>Real profile data rather than an agent reading source and guessing. It is
 * read-only with respect to the run: no verdict, no {@code results.tsv} row, no
 * change to the measurement baseline — an agent may run it as often as it likes
 * between experiments.
 */
public final class ProfileCommand {
    private ProfileCommand() {}

    /**
     * JMH's built-in sampling stack profiler and its GC profiler.
     *
     * <p>Both are pure Java and ship with jmh-core, so they work on any JDK with
     * no native agent, no extra dependency, and no permission a normal user does
     * not already have. A more precise picture is available from async-profiler,
     * but requiring one would mean {@code profile} failed on most machines the
     * first time an agent reached for it.
     */
    private static final List<String> PROFILERS = List.of("stack", "gc");

    public static int run(String[] args) {
        Flags f = new Flags("profile");
        f.string("C", ".", "repository root (or a directory inside it)");
        if (!f.parse(args)) return ExitCodes.USAGE;

        try {
            Path root = Git.root(Path.of(f.get("C")));
            Config cfg = ConfigLoading.load("profile", root);
            List<Benchmark> benches = Discovery.benchmarks(root);
            BuildTool tool = BuildTools.detect(root, cfg.buildTool, benches);
            ProcRunner build = new ProcRunner(tool.workingDir(root), cfg.timeoutDuration(), null);
            ProcResult compiled = tool.compile(root, build);
            if (!compiled.ok()) {
                System.err.println("autor3search-java profile: the project does not compile (exit "
                        + compiled.exitCode() + "):\n" + compiled.tail(30));
                return ExitCodes.USAGE;
            }
            String classpath = tool.benchClasspath(root, build);
            ProcRunner runner = new ProcRunner(tool.moduleRoot(root), cfg.timeoutDuration(), null);

            List<String> cmd = new ArrayList<>(List.of(
                    Jmh.javaExecutable(),
                    "-cp", classpath,
                    "org.openjdk.jmh.Main",
                    Jmh.pattern(cfg.benchmarks),
                    "-bm", "avgt",
                    "-tu", "ns",
                    "-f", "1",
                    "-wi", String.valueOf(cfg.warmupIterations),
                    "-i", String.valueOf(cfg.measurementIterations),
                    "-w", cfg.benchtime,
                    "-r", cfg.benchtime,
                    "-foe", "true"));
            for (String prof : PROFILERS) {
                cmd.add("-prof");
                cmd.add(prof);
            }
            if (!cfg.jvmArgs.isEmpty()) {
                cmd.add("-jvmArgs");
                cmd.add(String.join(" ", cfg.jvmArgs));
            }

            ProcResult res = runner.run(cmd);
            String output = res.stdout() + (res.stderr().isBlank() ? "" : "\n" + res.stderr());
            System.out.println(output);

            Path saved = save(root, output);
            if (saved != null) {
                System.out.println("\nfull profile written to " + root.relativize(saved));
            }
            if (!res.ok()) {
                System.err.println("autor3search-java profile: JMH exited " + res.exitCode());
                return ExitCodes.USAGE;
            }
            return ExitCodes.OK;
        } catch (IOException | ConfigLoading.LoadException e) {
            System.err.println("autor3search-java profile: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    /**
     * Keeps the full transcript under the harness's own (gitignored) directory, so
     * a long profile can be re-read without re-running it — and so nothing large
     * lands where the scope gate would have to reason about it.
     */
    private static Path save(Path root, String output) {
        try {
            Path dir = root.resolve(".autor3search/profiles");
            Files.createDirectories(dir);
            Path file = dir.resolve(Instant.now().toString().replace(':', '-') + ".txt");
            Files.writeString(file, output, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            // The profile was already printed; failing to keep a copy of it is not
            // worth turning a successful command into a failed one.
            return null;
        }
    }
}
