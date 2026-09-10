package io.github.autor3search.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unix-only because the TESTS drive {@code sh}, not because the code under test
 * is. {@link ProcRunner} uses nothing platform-specific — {@code ProcessBuilder},
 * {@code ProcessHandle} and threads — but exercising it needs a subprocess that
 * echoes, writes to stderr, sleeps and spawns a grandchild on demand, and the
 * shell is the only thing that does all four in one line. Rewriting these scripts
 * for {@code cmd} would test the scripts as much as the runner.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcRunnerTest {

    private static ProcRunner runner(Path dir, Duration timeout) {
        return new ProcRunner(dir, timeout, null);
    }

    @Test
    void capturesStdoutAndTheExitCode(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofSeconds(30)).run("sh", "-c", "echo hello; exit 0");
        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertEquals("hello\n", r.stdout());
        assertFalse(r.timedOut());
    }

    @Test
    void capturesStderrSeparatelyFromStdout(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofSeconds(30))
                .run("sh", "-c", "echo out; echo err >&2; exit 3");
        assertEquals(3, r.exitCode());
        assertFalse(r.ok());
        assertEquals("out\n", r.stdout());
        assertEquals("err\n", r.stderr());
    }

    @Test
    void runsInTheDirectoryItWasGiven(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofSeconds(30)).run("sh", "-c", "pwd");
        assertEquals(dir.toRealPath().toString(), r.stdout().trim());
    }

    @Test
    void passesExtraEnvironmentToTheChild(@TempDir Path dir) throws IOException {
        ProcRunner runner = runner(dir, Duration.ofSeconds(30)).env("AUTOR3SEARCH_PROBE", "yes");
        assertEquals("yes", runner.run("sh", "-c", "printf %s \"$AUTOR3SEARCH_PROBE\"").stdout());
    }

    /**
     * A command that fills the stderr pipe buffer while the reader is blocked on
     * stdout would deadlock if both were not drained concurrently.
     */
    @Test
    void doesNotDeadlockOnLargeOutputOnBothStreams(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofSeconds(60)).run("sh", "-c",
                "i=0; while [ $i -lt 2000 ]; do echo out-$i; echo err-$i >&2; i=$((i+1)); done");
        assertTrue(r.ok());
        assertTrue(r.stdout().contains("out-1999"));
        assertTrue(r.stderr().contains("err-1999"));
    }

    @Test
    void aTimeoutIsReportedAsSuchAndKillsTheProcess(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofMillis(400)).run("sh", "-c", "sleep 30");
        assertTrue(r.timedOut());
        assertFalse(r.ok());
        assertTrue(r.duration().toMillis() < 20_000, "should not have waited out the sleep");
    }

    /**
     * A benchmark run is build tool -> java -> JMH's forked JVM, so a cancel that
     * reached only the process this class started would leave the forked benchmark
     * running — burning CPU and corrupting every later measurement.
     */
    @Test
    void cancelReachesGrandchildProcesses(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("grandchild-alive");
        ProcRunner runner = runner(dir, Duration.ofSeconds(120));
        Thread run = new Thread(() -> {
            try {
                runner.run("sh", "-c",
                        "sh -c 'while true; do touch \"" + marker + "\"; sleep 0.1; done' & wait");
            } catch (IOException ignored) {
                // The cancel is expected to make this fail.
            }
        });
        run.start();
        // Wait for the grandchild to prove it is alive.
        for (int i = 0; i < 100 && !java.nio.file.Files.exists(marker); i++) {
            Thread.sleep(50);
        }
        assertTrue(java.nio.file.Files.exists(marker), "the grandchild never started");

        runner.cancel();
        run.join(TimeUnit.SECONDS.toMillis(30));

        java.nio.file.Files.deleteIfExists(marker);
        Thread.sleep(600);
        assertFalse(java.nio.file.Files.exists(marker), "the grandchild survived the cancel");
    }

    @Test
    void aSharedCancellationStopsEveryRunnerUnderIt(@TempDir Path dir) {
        Cancellation cancel = new Cancellation();
        ProcRunner runner = new ProcRunner(dir, Duration.ofSeconds(30), null, cancel);
        assertFalse(runner.cancelled());
        cancel.cancel();
        assertTrue(runner.cancelled());
        assertThrows(IOException.class, () -> runner.run("sh", "-c", "echo hi"));
    }

    @Test
    void reportsAnExecutableThatDoesNotExist(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> runner(dir, Duration.ofSeconds(5)).run("autor3search-no-such-command"));
    }

    @Test
    void tailPrefersStderrAndFallsBackToStdout() {
        ProcResult withErr = new ProcResult(List.of("x"), "a\nb\nc\n", "e1\ne2\ne3\n", 1, false,
                Duration.ZERO);
        assertEquals("e2\ne3", withErr.tail(2));

        ProcResult noErr = new ProcResult(List.of("x"), "a\nb\nc\n", "   \n", 1, false, Duration.ZERO);
        assertEquals("a\nb\nc", noErr.tail(10));
        assertEquals("", noErr.tail(0));
    }

    @Test
    void writesEveryCommandToTheLogItWasGiven(@TempDir Path dir) throws IOException {
        StringBuilder log = new StringBuilder();
        new ProcRunner(dir, Duration.ofSeconds(30), log).run("sh", "-c", "echo logged");
        assertTrue(log.toString().contains("$ sh -c echo logged"), log.toString());
        assertTrue(log.toString().contains("logged"), log.toString());
        assertTrue(log.toString().contains("exit=0"), log.toString());
    }

    @Test
    void aFailingLogDoesNotFailTheCommand(@TempDir Path dir) throws IOException {
        Appendable broken = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw new IOException("disk full");
            }
        };
        assertTrue(new ProcRunner(dir, Duration.ofSeconds(30), broken).run("sh", "-c", "true").ok());
    }

    @Test
    void reportsTheCommandItRan(@TempDir Path dir) throws IOException {
        ProcResult r = runner(dir, Duration.ofSeconds(30)).run("sh", "-c", "true");
        assertEquals(List.of("sh", "-c", "true"), r.command());
        assertNotEquals(Duration.ZERO, r.duration());
    }
}
