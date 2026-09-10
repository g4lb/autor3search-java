package io.github.autor3search.pipeline;

/**
 * Paths the harness owns inside the repository it is optimizing.
 *
 * <p>Separate from {@link Pipeline} so that packages which need to name these
 * files — the build diagnostics, for one — do not have to depend on the
 * evaluation pipeline to do it.
 */
public final class PipelinePaths {
    private PipelinePaths() {}

    /**
     * The harness-owned scratch log inside the repository root. Subprocess output
     * that could flood an unattended agent's context is written here rather than
     * streamed to stdout. Gitignored by {@code init}, and not part of the score.
     */
    public static final String RUN_LOG_NAME = "run.log";
}
