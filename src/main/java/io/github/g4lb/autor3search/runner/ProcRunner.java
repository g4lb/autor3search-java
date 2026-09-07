package io.github.g4lb.autor3search.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the build tool and the benchmark JVMs, with a timeout, captured output,
 * and a cancel that reaches the whole process tree.
 *
 * <p>The tree matters more than it sounds. A benchmark run is
 * {@code mvn -> surefire/exec -> java -> JMH's forked JVM}: killing only the
 * process this class started would leave the forked benchmark JVM running,
 * burning CPU and corrupting every later measurement on the machine — which is
 * precisely the failure {@code stop -force} exists to avoid. {@link #cancel()}
 * snapshots {@link ProcessHandle#descendants()} before destroying anything, so
 * the grandchildren are still reachable when it goes to kill them.
 */
public final class ProcRunner {
    /** Per-stream capture limit. Beyond it, output is dropped and marked truncated. */
    private static final int CAP_BYTES = 4 * 1024 * 1024;

    private final Path dir;
    private final Duration timeout;
    private final Appendable log;
    private final Cancellation cancellation;
    private final Map<String, String> extraEnv = new LinkedHashMap<>();

    private volatile Process current;
    private volatile boolean cancelled;

    public ProcRunner(Path dir, Duration timeout, Appendable log) {
        this(dir, timeout, log, null);
    }

    public ProcRunner(Path dir, Duration timeout, Appendable log, Cancellation cancellation) {
        this.dir = dir;
        this.timeout = timeout;
        this.log = log;
        this.cancellation = cancellation;
    }

    /** Adds an environment variable for every subsequent command. */
    public ProcRunner env(String key, String value) {
        extraEnv.put(key, value);
        return this;
    }

    public Path dir() {
        return dir;
    }

    public Duration timeout() {
        return timeout;
    }

    /** Whether this runner, or the run it belongs to, has been cancelled. */
    public boolean cancelled() {
        return cancelled || (cancellation != null && cancellation.cancelled());
    }

    /**
     * Asks the running command and every process it started to stop. Safe to
     * call from another thread, and from a shutdown hook — which is where it is
     * called from when the harness is interrupted.
     */
    public void cancel() {
        cancelled = true;
        Process p = current;
        if (p == null) return;
        List<ProcessHandle> tree = new ArrayList<>(p.descendants().toList());
        p.destroy();
        for (ProcessHandle h : tree) {
            h.destroy();
        }
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        for (ProcessHandle h : tree) {
            if (h.isAlive()) h.destroyForcibly();
        }
    }

    /** Runs one command in this runner's directory, bounded by its timeout. */
    public ProcResult run(List<String> command) throws IOException {
        if (cancelled()) {
            throw new IOException("cancelled before running " + String.join(" ", command));
        }
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().putAll(extraEnv);
        Instant start = Instant.now();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("run " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
        current = p;
        if (cancellation != null) cancellation.register(this);
        // A cancel that arrived while the process was starting would otherwise
        // have found `current` still null and killed nothing.
        if (cancelled()) cancelTree(p);
        Capture out = new Capture(p.getInputStream());
        Capture err = new Capture(p.getErrorStream());
        out.start();
        err.start();

        boolean timedOut = false;
        int exit;
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                cancelTree(p);
                exit = -1;
            } else {
                exit = p.exitValue();
            }
            out.join(TimeUnit.SECONDS.toMillis(10));
            err.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelTree(p);
            throw new IOException("interrupted while running " + String.join(" ", command));
        } finally {
            current = null;
            if (cancellation != null) cancellation.unregister(this);
        }

        ProcResult res = new ProcResult(
                List.copyOf(command), out.text(), err.text(), exit, timedOut,
                Duration.between(start, Instant.now()));
        writeLog(res);
        return res;
    }

    /** Same as {@link #run(List)} with a varargs command. */
    public ProcResult run(String... command) throws IOException {
        return run(List.of(command));
    }

    private void cancelTree(Process p) {
        List<ProcessHandle> tree = new ArrayList<>(p.descendants().toList());
        p.destroyForcibly();
        for (ProcessHandle h : tree) {
            h.destroyForcibly();
        }
    }

    private void writeLog(ProcResult res) {
        if (log == null) return;
        try {
            log.append("\n$ ").append(String.join(" ", res.command())).append('\n');
            log.append("(dir=").append(dir.toString())
                    .append(" exit=").append(String.valueOf(res.exitCode()))
                    .append(" timedOut=").append(String.valueOf(res.timedOut()))
                    .append(" took=").append(res.duration().toMillis() + "ms")
                    .append(")\n");
            log.append(res.stdout());
            log.append(res.stderr());
            if (log instanceof java.io.Flushable f) f.flush();
        } catch (IOException ignored) {
            // A log that cannot be written must never fail the experiment it is
            // only describing.
        }
    }

    /** Drains one stream on its own thread, retaining at most {@link #CAP_BYTES}. */
    private static final class Capture extends Thread {
        private final InputStream in;
        private final StringBuilder buf = new StringBuilder();
        private boolean truncated;

        Capture(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(chunk)) > 0) {
                    synchronized (buf) {
                        int room = CAP_BYTES - buf.length();
                        if (room <= 0) {
                            truncated = true;
                            continue;
                        }
                        String s = new String(chunk, 0, n, StandardCharsets.UTF_8);
                        if (s.length() > room) {
                            buf.append(s, 0, room);
                            truncated = true;
                        } else {
                            buf.append(s);
                        }
                    }
                }
            } catch (IOException ignored) {
                // The process went away mid-read; whatever was captured stands.
            }
        }

        String text() {
            synchronized (buf) {
                return truncated ? buf + "\n[output truncated at 4MB]\n" : buf.toString();
            }
        }
    }
}
