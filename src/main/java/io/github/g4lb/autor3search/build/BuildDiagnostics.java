package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.pipeline.PipelinePaths;
import io.github.g4lb.autor3search.results.Results;

import java.util.List;
import java.util.Locale;

/**
 * Turns a build failure that is really about the harness into a sentence saying
 * so.
 *
 * <p>These are failures where the build tool is working correctly and the
 * message it prints is about something else entirely. Left as-is they read as
 * "your change broke the build", which is the one thing they are not — and an
 * agent told that will start trying to fix code that was never wrong.
 */
public final class BuildDiagnostics {
    private BuildDiagnostics() {}

    /** Files the harness writes into a repository it is optimizing. */
    private static final List<String> HARNESS_FILES =
            List.of("program.md", Results.PATH, PipelinePaths.RUN_LOG_NAME, ".autor3search");

    /**
     * Markers that a licence-header check ran and rejected something. Both the
     * plugin names and the phrasing of the failures, because a build may print
     * either depending on how it is configured.
     */
    private static final List<String> LICENCE_MARKERS = List.of(
            "unapproved", "apache-rat", "rat-plugin", "rat.txt", "rat report",
            "license-maven-plugin", "licence header", "license header",
            "missing header", "header is missing");

    /**
     * A hint to append to a failure message, or null when nothing recognisable
     * happened.
     *
     * @param output the build tool's combined output
     */
    public static String explain(String output) {
        if (output == null || output.isBlank()) return null;
        String lower = output.toLowerCase(Locale.ROOT);

        boolean licenceCheck = LICENCE_MARKERS.stream().anyMatch(lower::contains);
        if (!licenceCheck) return null;

        String named = HARNESS_FILES.stream().filter(lower::contains).findFirst().orElse(null);
        StringBuilder sb = new StringBuilder("\nThis looks like a licence-header check failing, not a problem"
                + " with the change under test.");
        if (named != null) {
            sb.append(" It names ").append(named).append(", which autor3search-java wrote into this"
                    + " repository — it is not source and carries no header.");
        }
        sb.append("\nExclude the harness's own files from that check. For apache-rat:\n")
                .append("  <excludes>\n")
                .append("    <exclude>program.md</exclude>\n")
                .append("    <exclude>").append(Results.PATH).append("</exclude>\n")
                .append("    <exclude>").append(PipelinePaths.RUN_LOG_NAME).append("</exclude>\n")
                .append("    <exclude>.autor3search/**</exclude>\n")
                .append("  </excludes>\n")
                .append("This is a one-off change to your build, so make it before `baseline` — ")
                .append(Config.PATH).append(" is hashed at baseline and the build files may not be")
                .append(" edited during a run.");
        return sb.toString();
    }
}
