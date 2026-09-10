package io.github.autor3search.cli;

/** The process exit codes callers — and program.md's loop — branch on. */
public final class ExitCodes {
    private ExitCodes() {}

    /** KEEP. */
    public static final int OK = 0;
    /** DISCARD. */
    public static final int DISCARD = 1;
    /** FAIL: a correctness or scope gate rejected the change. */
    public static final int FAIL = 2;
    /** CRASH: the build failed outright, or a phase timed out. */
    public static final int CRASH = 3;
    /** The command was invoked wrongly, or the run is not set up. */
    public static final int USAGE = 64;
}
