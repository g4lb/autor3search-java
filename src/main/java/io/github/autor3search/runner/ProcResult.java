package io.github.autor3search.runner;

import java.time.Duration;
import java.util.List;

/** The outcome of one subprocess. */
public record ProcResult(
        List<String> command,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        Duration duration) {

    /** A clean, in-time run. */
    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }

    /**
     * The last n lines of stderr, falling back to stdout when stderr is blank.
     * Build tools differ on which stream they put a compilation error on, and a
     * verdict message that says "FAILED" with nothing under it is useless.
     */
    public String tail(int n) {
        int lines = Math.max(n, 0);
        String src = stderr;
        if (src == null || src.isBlank()) src = stdout;
        if (src == null) return "";
        String[] all = src.replaceAll("\n+$", "").split("\n", -1);
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }
}
