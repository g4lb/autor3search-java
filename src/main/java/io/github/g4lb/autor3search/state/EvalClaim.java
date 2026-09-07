package io.github.g4lb.autor3search.state;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The claim one {@code eval} holds on a run for as long as it is measuring.
 *
 * <p>The claim is an ADVISORY LOCK held on the pid file for the lifetime of the
 * process, not merely the file's existence. That distinction is what makes
 * {@code stop -force} safe: an evaluation killed without cleanup leaves the file
 * behind, and pids are recycled, so a force-stop that trusted the file alone
 * could eventually signal an unrelated process. The lock is released by the
 * kernel when the process dies however it dies, so {@link #running} can tell a
 * live evaluation from a corpse.
 *
 * <p>Two concurrent evaluations against one run is itself a bug — they would
 * fight over the same pinned worktree — so a claim that cannot be taken is
 * reported as an error naming the incumbent, never silently ignored.
 */
public final class EvalClaim implements AutoCloseable {

    /** The pid file, relative to a run's state directory. */
    public static final String PID_FILE = "eval.pid";

    /**
     * The byte the claim is taken on — far past any content the file will ever
     * hold, and locked one byte wide.
     *
     * <p>Not the whole file, and this is not tidiness. A byte-range lock is
     * ADVISORY on Unix but MANDATORY on Windows: locking the region the pid lives
     * in makes every reader fail, and the readers are the whole point. {@link
     * #running} reads the pid precisely while a claim is held, and {@link #acquire}
     * reads it to name the incumbent when it refuses. Locking a byte nothing will
     * ever write leaves the conflict semantics intact and the file readable.
     */
    private static final long CLAIM_BYTE = 1L << 62;

    private final Path path;
    private final RandomAccessFile file;
    private final FileLock lock;

    private EvalClaim(Path path, RandomAccessFile file, FileLock lock) {
        this.path = path;
        this.file = file;
        this.lock = lock;
    }

    /** Takes the claim, or throws naming whoever already holds it. */
    public static EvalClaim acquire(Path stateDir, long pid) throws IOException {
        Files.createDirectories(stateDir);
        Path path = stateDir.resolve(PID_FILE);
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        FileLock lock;
        try {
            lock = raf.getChannel().tryLock(CLAIM_BYTE, 1, false);
        } catch (OverlappingFileLockException e) {
            raf.close();
            throw new IOException("another autor3search-java eval is already running for this run");
        } catch (IOException e) {
            raf.close();
            throw new IOException("lock " + path + ": " + e.getMessage(), e);
        }
        if (lock == null) {
            raf.close();
            String other = readPid(path);
            throw new IOException("another autor3search-java eval" + (other == null ? "" : " (pid " + other + ")")
                    + " is already running for this run");
        }
        raf.setLength(0);
        raf.write((pid + "\n").getBytes(StandardCharsets.UTF_8));
        raf.getChannel().force(true);
        return new EvalClaim(path, raf, lock);
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // The lock is released by the kernel when the channel closes anyway.
        }
        try {
            file.close();
        } catch (IOException ignored) {
            // Nothing useful is left to do about a failing close on the way out.
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover pid file is recognised as stale by `running` below.
        }
    }

    /** The pid recorded in the file, or null when there is none or it is unreadable. */
    private static String readPid(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    /** What {@code status} and {@code stop} learn about an evaluation in flight. */
    public record State(long pid, boolean running) {}

    /**
     * Whether some live process holds the claim on this run.
     *
     * <p>Answered by trying to take a SHARED lock: success means no exclusive
     * holder, so the pid file is a leftover. Shared rather than exclusive because
     * this is a query — it still conflicts with the exclusive lock
     * {@link #acquire} takes, which is what makes it a valid test, but two
     * concurrent queries no longer block each other, so a {@code status} and a
     * {@code stop} looking at the same run cannot report one another as the
     * running evaluation.
     */
    public static State running(Path stateDir) throws IOException {
        Path path = stateDir.resolve(PID_FILE);
        if (!Files.exists(path)) return new State(0, false);
        String text = readPid(path);
        long pid;
        try {
            pid = Long.parseLong(text == null ? "" : text);
        } catch (NumberFormatException e) {
            // A pid file that exists but holds no plausible pid is an ERROR, not a
            // missing one: the value is about to be handed to a process lookup, and
            // refusing to guess is the only safe reading of a corrupt file.
            throw new IOException("read " + path + ": \"" + text + "\" is not a pid");
        }
        if (pid <= 1) {
            throw new IOException("read " + path + ": pid " + pid + " is not a process this command will signal");
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            FileLock probe;
            try {
                probe = ch.tryLock(CLAIM_BYTE, 1, true);
            } catch (OverlappingFileLockException e) {
                // Held by this very JVM — which happens in the test suite, and would
                // happen if a future command ever ran an eval in-process.
                return new State(pid, true);
            }
            if (probe == null) return new State(pid, true);
            probe.release();
            return new State(pid, false);
        }
    }

    /** Removes a pid file left behind by an evaluation that died without releasing its claim. */
    public static void clear(Path stateDir) throws IOException {
        Files.deleteIfExists(stateDir.resolve(PID_FILE));
    }
}
