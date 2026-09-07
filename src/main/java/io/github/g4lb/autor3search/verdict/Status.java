package io.github.g4lb.autor3search.verdict;

/** The terminal outcome of one experiment. */
public enum Status {
    KEEP,
    DISCARD,
    FAIL,
    CRASH,
    /**
     * Not a verdict: an experiment interrupted before anything was measured.
     * It reports the FAIL exit code so an agent that does not recognise it does
     * the right thing with it anyway — drop the commit and stop.
     */
    ABORTED;

    /** The process exit code the agent's loop branches on. */
    public int exitCode() {
        return switch (this) {
            case KEEP -> 0;
            case DISCARD -> 1;
            case FAIL, ABORTED -> 2;
            case CRASH -> 3;
        };
    }
}
