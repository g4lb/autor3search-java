package io.github.g4lb.autor3search.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The sentinel a human's shell writes and the agent's loop reads.
 *
 * <p>It lives out-of-tree alongside the rest of the run state, for the same
 * reason everything else there does: a sentinel inside the repository would
 * dirty the working tree the agent commits from, and would have to be
 * special-cased in the scope gate and in .gitignore. Out here it is invisible to
 * every gate and to git, and both processes still find it, because the state
 * directory is derived from the repository path and the run tag rather than from
 * anything either process holds privately.
 */
public final class Stop {
    private Stop() {}

    /**
     * Marks that the human has asked the run to end. {@code eval} reports its
     * presence alongside the verdict; the AGENT decides when to act on it, which
     * is what makes the stop graceful — nothing here interrupts an experiment
     * already under way.
     */
    public static final String REQUEST_FILE = "stop.request";

    /**
     * Asks the RUNNING evaluation to abandon the experiment it is measuring.
     *
     * <p>A file rather than a signal, deliberately. Killing the process would work
     * on Unix, but the JVM's exit status is then fixed at 128+SIGTERM by the
     * runtime and nothing in Java can change it — so a force-stopped evaluation
     * could never exit with the code the agent's loop branches on, and could not
     * print the ABORTED verdict that tells the agent to drop its commit. Polling a
     * sentinel lets the evaluation cancel itself, report properly, and exit 2 on
     * every platform. {@code stop -force} still escalates to killing the process
     * if the evaluation does not let go.
     */
    public static final String FORCE_FILE = "stop.force";

    /**
     * Asks the run in stateDir to end after the current experiment.
     *
     * <p>Creating stateDir when it is missing is deliberate: a human reaching for
     * the brake should never be told the directory does not exist yet. A request
     * against a tag whose baseline never finished is harmless — the sentinel sits
     * there until a run reads it, or {@link #clear} removes it.
     */
    public static void request(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve(REQUEST_FILE), "stop requested\n", StandardCharsets.UTF_8);
    }

    /** Cancels a pending request, graceful and forced alike. Clearing one that was never made is not an error. */
    public static void clear(Path stateDir) throws IOException {
        Files.deleteIfExists(stateDir.resolve(REQUEST_FILE));
        Files.deleteIfExists(stateDir.resolve(FORCE_FILE));
    }

    /** Asks the evaluation currently in flight to abandon its experiment. */
    public static void requestForce(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve(FORCE_FILE), "abandon the current experiment\n",
                StandardCharsets.UTF_8);
    }

    /** Whether an evaluation has been asked to abandon its experiment. */
    public static boolean forceRequested(Path stateDir) {
        return Files.exists(stateDir.resolve(FORCE_FILE));
    }

    /**
     * Removes the force sentinel. Called by the evaluation as it starts, so one
     * left over from a previous run cannot abandon the next experiment before it
     * has measured anything, and by {@code stop -force} once it is done.
     */
    public static void clearForce(Path stateDir) throws IOException {
        Files.deleteIfExists(stateDir.resolve(FORCE_FILE));
    }

    /**
     * Whether a stop is pending.
     *
     * <p>Returns a plain boolean rather than propagating an I/O failure, because
     * every caller wants the same answer for an unreadable sentinel as for an
     * absent one: carry on. A stop that cannot be read must never abort a run by
     * itself — the human still has {@code -force} and Ctrl+C.
     */
    public static boolean requested(Path stateDir) {
        return Files.exists(stateDir.resolve(REQUEST_FILE));
    }
}
