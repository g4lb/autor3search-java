package io.github.g4lb.autor3search.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopAndClaimTest {

    @Test
    void aRequestIsVisibleUntilItIsCleared(@TempDir Path dir) throws IOException {
        Path stateDir = dir.resolve("run");
        assertFalse(Stop.requested(stateDir));
        Stop.request(stateDir);
        assertTrue(Stop.requested(stateDir));
        Stop.clear(stateDir);
        assertFalse(Stop.requested(stateDir));
    }

    /**
     * A human reaching for the brake must never be told the directory does not
     * exist yet — a request against a tag whose baseline never finished simply
     * sits there.
     */
    @Test
    void requestCreatesTheStateDirectoryIfItIsMissing(@TempDir Path dir) throws IOException {
        Path stateDir = dir.resolve("never/made");
        Stop.request(stateDir);
        assertTrue(Files.isDirectory(stateDir));
    }

    @Test
    void clearingARequestThatWasNeverMadeIsNotAnError(@TempDir Path dir) throws IOException {
        Stop.clear(dir.resolve("run"));
    }

    @Test
    void theForceSentinelIsSeparateFromTheGracefulOne(@TempDir Path dir) throws IOException {
        Path stateDir = dir.resolve("run");
        Stop.request(stateDir);
        assertFalse(Stop.forceRequested(stateDir));
        Stop.requestForce(stateDir);
        assertTrue(Stop.forceRequested(stateDir));
        assertTrue(Stop.requested(stateDir), "a force must not cancel the graceful request");
        Stop.clearForce(stateDir);
        assertFalse(Stop.forceRequested(stateDir));
        assertTrue(Stop.requested(stateDir));
    }

    @Test
    void clearRemovesBothSentinels(@TempDir Path dir) throws IOException {
        Path stateDir = dir.resolve("run");
        Stop.request(stateDir);
        Stop.requestForce(stateDir);
        Stop.clear(stateDir);
        assertFalse(Stop.requested(stateDir));
        assertFalse(Stop.forceRequested(stateDir));
    }

    @Test
    void anUnheldRunReportsNoEvalRunning(@TempDir Path dir) throws IOException {
        assertFalse(EvalClaim.running(dir).running());
    }

    @Test
    void aHeldClaimIsVisibleAndReleasedOnClose(@TempDir Path dir) throws IOException {
        long pid = ProcessHandle.current().pid();
        try (EvalClaim claim = EvalClaim.acquire(dir, pid)) {
            EvalClaim.State state = EvalClaim.running(dir);
            assertTrue(state.running());
            assertEquals(pid, state.pid());
        }
        assertFalse(EvalClaim.running(dir).running());
    }

    /** Two evaluations sharing one pinned worktree would measure each other's checkouts. */
    @Test
    void aSecondClaimOnTheSameRunIsRefused(@TempDir Path dir) throws IOException {
        try (EvalClaim first = EvalClaim.acquire(dir, ProcessHandle.current().pid())) {
            IOException e = assertThrows(IOException.class,
                    () -> EvalClaim.acquire(dir, ProcessHandle.current().pid()));
            assertTrue(e.getMessage().contains("already running"), e.getMessage());
        }
    }

    /**
     * The value is about to be used to find a process. A file that exists but
     * holds no plausible pid is an error, never a guess.
     */
    @Test
    void aCorruptPidFileIsAnErrorNotAGuess(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(EvalClaim.PID_FILE), "not-a-pid\n");
        assertThrows(IOException.class, () -> EvalClaim.running(dir));

        Files.writeString(dir.resolve(EvalClaim.PID_FILE), "1\n");
        assertThrows(IOException.class, () -> EvalClaim.running(dir));
    }

    /**
     * A pid file left behind by an evaluation that died without cleanup must read
     * as idle: pids are recycled, and trusting the file alone would eventually
     * point a force-stop at an unrelated process.
     */
    @Test
    void aLeftoverPidFileReadsAsIdle(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(EvalClaim.PID_FILE), "999999\n");
        EvalClaim.State state = EvalClaim.running(dir);
        assertFalse(state.running());
        assertEquals(999999, state.pid());
        EvalClaim.clear(dir);
        assertFalse(Files.exists(dir.resolve(EvalClaim.PID_FILE)));
    }
}
