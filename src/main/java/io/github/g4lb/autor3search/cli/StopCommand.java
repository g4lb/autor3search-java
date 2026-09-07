package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.state.EvalClaim;
import io.github.g4lb.autor3search.state.Stop;
import io.github.g4lb.autor3search.util.Durations;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks a run to end.
 *
 * <p>Two speeds, and the difference is who decides when to stop:
 *
 * <ul>
 *   <li>Plain {@code stop} writes a request the AGENT reads at its next verdict.
 *       The experiment under way finishes and is scored, its KEEP or DISCARD is
 *       applied, and only then does the loop exit. Nothing is thrown away.
 *   <li>{@code stop -force} additionally signals the running evaluation to
 *       abandon the experiment now. That is for when the human cannot wait for a
 *       long benchmark to finish.
 * </ul>
 *
 * <p>Both leave the repository on the run branch with every kept commit intact.
 */
public final class StopCommand {
    private StopCommand() {}

    /**
     * How long {@code -force} waits for a signalled evaluation to tear down its
     * benchmark JVMs before escalating. Generous on purpose: the polite signal is
     * what lets the evaluation cancel its own measurement, which is what kills the
     * whole process tree. Escalating early would leave the forked benchmark JVM
     * running — the exact outcome force-stop exists to prevent.
     */
    static final Duration DEFAULT_GRACE = Duration.ofSeconds(30);

    /** How often the wait re-checks whether the signalled evaluation has let go. */
    private static final long POLL_MILLIS = 50;

    public static int run(String[] args) {
        Flags f = new Flags("stop");
        f.string("C", ".", "repository root (or a directory inside it)");
        f.string("tag", "", "run tag, when the current branch is not the run branch");
        f.bool("clear", false, "cancel a pending stop request so the loop may continue");
        f.bool("force", false, "also signal the running eval to abandon the current experiment");
        f.string("grace", Durations.format(DEFAULT_GRACE),
                "with -force, how long to let the running eval shut down before killing it");
        if (!f.parse(args)) return ExitCodes.USAGE;

        if (f.flag("clear") && f.flag("force")) {
            System.err.println("autor3search-java stop: -clear and -force are opposites; pass one or neither");
            return ExitCodes.USAGE;
        }
        Duration grace;
        try {
            grace = Durations.parse(f.get("grace"));
        } catch (IllegalArgumentException e) {
            System.err.println("autor3search-java stop: -grace " + e.getMessage());
            return ExitCodes.USAGE;
        }

        RunRef ref;
        try {
            ref = RunRef.resolve("stop", f.get("C"), f.get("tag"));
        } catch (RunRef.ResolveException e) {
            System.err.println("autor3search-java stop: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        try {
            if (f.flag("clear")) {
                Stop.clear(ref.stateDir());
                System.out.println("stop request cleared for run \"" + ref.tag() + "\"");
                System.out.println("the agent will keep experimenting; run `autor3search-java stop` again"
                        + " to stop it");
                return ExitCodes.OK;
            }

            Stop.request(ref.stateDir());
            System.out.println("stop requested for run \"" + ref.tag() + "\"");

            if (!f.flag("force")) {
                System.out.println("the agent will finish the experiment it is running and then exit the loop");
                System.out.println("to cancel:      autor3search-java stop -clear");
                System.out.println("to stop sooner: autor3search-java stop -force");
                return ExitCodes.OK;
            }
            return force(ref, grace);
        } catch (IOException e) {
            System.err.println("autor3search-java stop: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    /**
     * Signals the running evaluation and reports the state it leaves behind. The
     * stop request has already been written by the caller, so an agent that
     * survives the signal still sees the stop at its next verdict.
     */
    private static int force(RunRef ref, Duration grace) throws IOException {
        EvalClaim.State state;
        try {
            state = EvalClaim.running(ref.stateDir());
        } catch (IOException e) {
            // A corrupt pid file is reported, never guessed at. The request still stands.
            System.err.println("autor3search-java stop: " + e.getMessage());
            System.err.println("the stop request was written; the agent will still stop at its next verdict");
            return ExitCodes.USAGE;
        }

        if (!state.running()) {
            // Either nothing was in flight, or an evaluation died without releasing
            // its claim. Clearing the leftover keeps `status` honest.
            EvalClaim.clear(ref.stateDir());
            System.out.println("no eval running — nothing to signal");
            printRepoState(ref);
            return ExitCodes.OK;
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(state.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            EvalClaim.clear(ref.stateDir());
            System.out.println("eval (pid " + state.pid() + ") is already gone");
            printRepoState(ref);
            return ExitCodes.OK;
        }

        System.out.println("asking eval (pid " + state.pid() + ") to abandon its experiment...");
        // The sentinel first, not a signal. It lets the evaluation cancel its own
        // measurement — which is what tears down the forked benchmark JVMs running
        // as its grandchildren — and then report an ABORTED verdict and exit with
        // the code the agent's loop branches on. Killing it outright would orphan
        // those JVMs, and an orphaned benchmark keeps burning CPU and corrupts every
        // later measurement on the machine.
        Stop.requestForce(ref.stateDir());

        boolean killed = false;
        if (!waitForRelease(ref, grace)) {
            System.out.println("eval did not exit within " + Durations.format(grace)
                    + "; killing it and everything it started");
            for (ProcessHandle child : handle.get().descendants().toList()) {
                child.destroyForcibly();
            }
            handle.get().destroyForcibly();
            if (!waitForRelease(ref, grace)) {
                System.err.println("autor3search-java stop: eval (pid " + state.pid()
                        + ") still holds the run after a forcible kill");
                return ExitCodes.USAGE;
            }
            killed = true;
        }
        Stop.clearForce(ref.stateDir());
        System.out.println("eval exited; its benchmark JVMs were torn down with it");

        // Only tidy up after a forcible kill, which runs none of the evaluation's own
        // cleanup. One that shut down politely removed its pid file itself, and
        // deleting unconditionally here would race a NEXT evaluation that had already
        // claimed the run: it would hold a lock on an unlinked file, leaving `status`
        // reporting an idle loop and `stop -force` with nothing to signal — the brake
        // failing precisely when it is reached for.
        if (killed) {
            EvalClaim.clear(ref.stateDir());
        }

        printRepoState(ref);
        return ExitCodes.OK;
    }

    /**
     * Polls until no live process holds the run's claim, or the grace period runs
     * out.
     *
     * <p>It asks the claim rather than probing the pid, because the pid answers the
     * wrong question. A signalled process that has died but not yet been reaped by
     * its parent is a zombie, and a liveness check against a zombie still succeeds:
     * polling the pid would wait out the full grace period and then escalate
     * against a process that was already dead. The kernel releases the claim's lock
     * the moment the process dies, zombie or not, so the claim is the honest signal.
     */
    private static boolean waitForRelease(RunRef ref, Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        while (true) {
            try {
                if (!EvalClaim.running(ref.stateDir()).running()) return true;
            } catch (IOException e) {
                return true;
            }
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Tells the human what a forced stop left behind. It never changes the
     * repository: dropping a commit is the human's call, and an experiment
     * abandoned mid-flight may still be worth keeping by hand.
     */
    private static void printRepoState(RunRef ref) {
        System.out.println();
        System.out.println("repository state:");
        System.out.printf("  %-10s %s%n", "branch", ref.branch());
        try {
            System.out.printf("  %-10s %s \"%s\"%n", "HEAD",
                    Git.headCommit(ref.root()), Git.headSubject(ref.root()));
        } catch (IOException e) {
            return;
        }
        System.out.println();
        System.out.println("if the agent had already committed the experiment it was running, that commit");
        System.out.println("carries no verdict. To drop it:  git reset --hard HEAD~1");
        System.out.println("to resume this run later:        autor3search-java stop -clear");
    }
}
