package io.github.autor3search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.autor3search.cli.Capture;
import io.github.autor3search.cli.ExitCodes;
import io.github.autor3search.config.Config;
import io.github.autor3search.git.Git;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.RunState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gradle half of the build-tool support, driven end to end.
 *
 * <p>Gradle is a genuinely different problem from Maven, and none of it is
 * exercised anywhere else: the classpath comes from an injected init script
 * rather than a plugin goal, tasks are addressed by project path rather than by
 * directory, and the source set that holds the benchmarks may be {@code jmh} or
 * {@code test}. A unit test can assert the command lines are shaped correctly; it
 * cannot tell whether Gradle agrees.
 *
 * <p>Only one experiment is run. The verdict itself is not the point — the Maven
 * journey already covers the decisions — what matters here is that every stage
 * before it can drive Gradle at all.
 */
class GradleEndToEndTest {

    private static final String SETTINGS = "rootProject.name = 'demo'\n";

    private static final String BUILD = """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation 'org.openjdk.jmh:jmh-core:1.37'
                testAnnotationProcessor 'org.openjdk.jmh:jmh-generator-annprocess:1.37'
                testImplementation platform('org.junit:junit-bom:5.14.1')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
            }
            tasks.withType(JavaCompile).configureEach {
                options.release = 17
                // JDK 23 and later will not run an annotation processor found only on
                // the classpath without being told to, and JMH's generator is one. The
                // flag does not exist before JDK 21, which is why this is conditional
                // rather than always set.
                if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
                    options.compilerArgs << '-proc:full'
                }
            }
            test { useJUnitPlatform() }
            """;

    @Test
    void drivesAGradleProjectThroughAWholeExperiment(@TempDir Path work) throws IOException {
        Assumptions.assumeTrue(onPath("gradle"), "gradle is not on PATH");
        Assumptions.assumeFalse(Boolean.getBoolean("autor3search.skipE2E"), "end-to-end tests disabled");
        Path demo = Path.of("testdata", "demo");
        Assumptions.assumeTrue(Files.isDirectory(demo), "the demo project is missing");

        // Same sources as the Maven demo, under a Gradle build. Sharing them is
        // deliberate: any difference between the two runs is then the build tool,
        // never the code being measured.
        Path repo = work.resolve("repo");
        copy(demo.resolve("src/main/java/demo/WordCount.java"),
                repo.resolve("src/main/java/demo/WordCount.java"));
        copy(demo.resolve("src/test/java/demo/WordCountTest.java"),
                repo.resolve("src/test/java/demo/WordCountTest.java"));
        copy(demo.resolve("src/test/java/demo/WordCountBenchmark.java"),
                repo.resolve("src/test/java/demo/WordCountBenchmark.java"));
        write(repo.resolve("settings.gradle"), SETTINGS);
        write(repo.resolve("build.gradle"), BUILD);
        write(repo.resolve(".gitignore"), "build/\n.gradle/\n");
        TestRepo.init(repo).commit("initial");

        assertEquals(ExitCodes.OK, run("init", "-C", repo.toString()));

        Path config = repo.resolve(Config.PATH);
        write(config, Files.readString(config)
                .replace("count: 10", "count: 4")
                .replace("warmup_iterations: 5", "warmup_iterations: 1")
                .replace("measurement_iterations: 5", "measurement_iterations: 1")
                .replace("benchtime: 1s", "benchtime: 200ms"));
        Git.git(repo, "add", "-A");
        Git.git(repo, "commit", "-q", "-m", "autor3search-java init");

        assertEquals(ExitCodes.OK, run("baseline", "-C", repo.toString(), "-tag", "g"));
        Baseline base = Baseline.load(RunState.stateDir(repo, "g").resolve(RunState.BASELINE_FILE));
        assertEquals("gradle", base.buildTool);
        assertEquals(".", base.moduleDir);
        assertEquals(List.of("demo.WordCountBenchmark.count"), base.benchmarks);

        int code = run("eval", "-C", repo.toString(), "--json", "-desc", "gradle-baseline-vs-itself");
        // KEEP, DISCARD or CRASH are all real answers here; what must not happen is
        // the command failing to produce a verdict at all, which is what a broken
        // classpath, an unrunnable task or an unparsed result looks like.
        assertTrue(Set.of(ExitCodes.OK, ExitCodes.DISCARD).contains(code),
                "gradle run did not reach a verdict:\n" + lastOut + lastErr);

        JsonObject verdict = JsonParser.parseString(lastOut).getAsJsonObject();
        assertTrue(Set.of("KEEP", "DISCARD").contains(verdict.get("status").getAsString()), lastOut);
        assertEquals(1, verdict.getAsJsonArray("deltas").size(), lastOut);
        assertEquals("demo.WordCountBenchmark.count",
                verdict.getAsJsonArray("deltas").get(0).getAsJsonObject().get("name").getAsString());
    }

    private static String lastOut = "";
    private static String lastErr = "";

    private static int run(String... args) {
        Capture.Output out = Capture.run(() -> Main.dispatch(args));
        lastOut = out.out();
        lastErr = out.err();
        System.out.println("$ autor3search-java " + String.join(" ", args));
        System.out.println(lastOut);
        if (!lastErr.isBlank()) System.out.println("[stderr] " + lastErr);
        return out.code();
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
    }

    private static void write(Path to, String content) throws IOException {
        Files.createDirectories(to.getParent());
        Files.writeString(to, content, StandardCharsets.UTF_8);
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }
}
