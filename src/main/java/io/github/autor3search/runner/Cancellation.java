package io.github.autor3search.runner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shared brake for every subprocess an evaluation starts.
 *
 * <p>Checking a flag between phases is not enough. A single measurement round is
 * a JMH invocation that can run for minutes, and the whole point of
 * {@code stop -force} is to end one of those without waiting it out — so the
 * cancel has to reach INTO the running process, not merely be noticed after it
 * finishes. Runners register themselves here for exactly as long as they hold a
 * live process, and {@link #cancel()} tears down each one's process tree.
 *
 * <p>Called from a shutdown hook, so everything here is safe to run on another
 * thread while the main one is blocked in {@code waitFor}.
 */
public final class Cancellation {

    private final Set<ProcRunner> active = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    public boolean cancelled() {
        return cancelled;
    }

    /** Marks the run cancelled and kills every process currently in flight. */
    public void cancel() {
        cancelled = true;
        for (ProcRunner r : active) {
            r.cancel();
        }
    }

    void register(ProcRunner r) {
        active.add(r);
    }

    void unregister(ProcRunner r) {
        active.remove(r);
    }
}
