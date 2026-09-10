package io.github.autor3search.doctor;

import io.github.autor3search.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorTest {

    private static Map<String, Doctor.Finding> byName(List<Doctor.Finding> findings) {
        return findings.stream().collect(Collectors.toMap(Doctor.Finding::name, Function.identity(),
                (a, b) -> a));
    }

    @Test
    void reportsTheRuntimeAndGitOnAnyMachineTheSuiteRunsOn(@TempDir Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("repo"));
        r.write("pom.xml", "<project/>\n").commit("initial");

        Map<String, Doctor.Finding> findings = byName(Doctor.check(r.root()));
        // The suite is running, so the runtime is new enough by construction.
        assertEquals(Doctor.Severity.OK, findings.get("java").severity());
        assertEquals(Doctor.Severity.OK, findings.get("git").severity());
        assertEquals(Doctor.Severity.OK, findings.get("git repo").severity());
        assertTrue(findings.containsKey("cpu"));
        assertTrue(findings.containsKey("disk"));
    }

    @Test
    void reportsADirectoryThatIsNotARepository(@TempDir Path dir) {
        assertEquals(Doctor.Severity.FAIL, byName(Doctor.check(dir)).get("git repo").severity());
    }

    /**
     * A repository the harness cannot drive is a FAIL, and one it can drive but
     * that has nothing to measure is a WARN — neither is silently OK.
     */
    @Test
    void gradesTheBuildOnWhetherItCanBeDrivenAndMeasured(@TempDir Path dir) throws IOException {
        TestRepo none = TestRepo.init(dir.resolve("none"));
        none.write("README.md", "no build file\n").commit("initial");
        assertEquals(Doctor.Severity.FAIL, byName(Doctor.check(none.root())).get("build").severity());

        TestRepo empty = TestRepo.init(dir.resolve("empty"));
        empty.write("pom.xml", "<project/>\n").commit("initial");
        Doctor.Finding warn = byName(Doctor.check(empty.root())).get("build");
        assertEquals(Doctor.Severity.WARN, warn.severity());
        assertTrue(warn.detail().contains("no @Benchmark"), warn.detail());

        TestRepo good = TestRepo.init(dir.resolve("good"));
        good.write("pom.xml", "<project/>\n");
        good.write("src/test/java/a/B.java", """
                package a;
                import org.openjdk.jmh.annotations.Benchmark;
                public class B { @Benchmark public int run() { return 1; } }
                """);
        good.commit("initial");
        Doctor.Finding ok = byName(Doctor.check(good.root())).get("build");
        assertEquals(Doctor.Severity.OK, ok.severity());
        assertTrue(ok.detail().contains("1 benchmark(s)"), ok.detail());
    }

    /**
     * A repository that publishes to Maven Central but builds with Gradle keeps
     * both build files. Re-detecting with "auto" regardless would report a hard
     * FAIL on a repository whose config has already settled the question.
     */
    @Test
    void honoursTheBuildToolTheConfigAlreadySettled(@TempDir Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("both"));
        r.write("pom.xml", "<project/>\n");
        r.write("build.gradle", "plugins { id 'java' }\n");
        r.write("src/test/java/a/B.java", """
                package a;
                import org.openjdk.jmh.annotations.Benchmark;
                public class B { @Benchmark public int run() { return 1; } }
                """);
        r.commit("initial");

        // Ambiguous, and honestly reported as such.
        assertEquals(Doctor.Severity.FAIL, byName(Doctor.check(r.root())).get("build").severity());

        r.write(io.github.autor3search.config.Config.PATH,
                "benchmarks: []\nbuild_tool: maven\n").commit("settle it");
        Doctor.Finding settled = byName(Doctor.check(r.root())).get("build");
        assertEquals(Doctor.Severity.OK, settled.severity(), settled.detail());
        assertTrue(settled.detail().contains("maven"), settled.detail());
    }

    /**
     * A check that did not run must never be reported as a pass: missing cpufreq
     * on a VM is "not checked", not "fine".
     */
    @Test
    void anAbsentGovernorIsNotCheckedRatherThanOk(@TempDir Path dir) {
        Doctor.Finding f = Doctor.checkGovernor(dir.resolve("no-such-file"));
        assertEquals(Doctor.Severity.NOT_APPLICABLE, f.severity());
        assertTrue(f.detail().contains("not checked"), f.detail());
    }

    @Test
    void gradesTheGovernorItFinds(@TempDir Path dir) throws IOException {
        Path performance = dir.resolve("performance");
        Files.writeString(performance, "performance\n", StandardCharsets.UTF_8);
        assertEquals(Doctor.Severity.OK, Doctor.checkGovernor(performance).severity());

        Path powersave = dir.resolve("powersave");
        Files.writeString(powersave, "powersave\n", StandardCharsets.UTF_8);
        Doctor.Finding warn = Doctor.checkGovernor(powersave);
        assertEquals(Doctor.Severity.WARN, warn.severity());
        assertTrue(warn.detail().contains("powersave"), warn.detail());
    }
}
