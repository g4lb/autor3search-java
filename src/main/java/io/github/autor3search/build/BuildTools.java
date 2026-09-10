package io.github.autor3search.build;

import io.github.autor3search.discover.Benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Detects which build tool drives a repository, and which module holds its benchmarks. */
public final class BuildTools {
    private BuildTools() {}

    private static final List<String> GRADLE_MARKERS = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * Detects the tool and infers the benchmark module from where the benchmarks
     * were found. Used by {@code init} and {@code baseline}, which then record the
     * module so no later command has to infer it again.
     */
    public static BuildTool detect(Path root, String configured, List<Benchmark> benchmarks) throws IOException {
        String kind = resolveKind(root, configured);
        String module = inferModule(root, kind, benchmarks);
        return forModule(root, kind, module);
    }

    /**
     * Builds the tool for a module already recorded at baseline time. Used by
     * {@code eval}, which must not re-infer the module: a benchmark file moved
     * mid-run would silently re-point the measurement at a different module's
     * classpath rather than failing.
     */
    public static BuildTool forModule(Path root, String configured, String moduleDir) throws IOException {
        String kind = resolveKind(root, configured);
        String module = (moduleDir == null || moduleDir.isBlank()) ? "." : moduleDir;
        if (kind.equals("maven")) {
            return new MavenBuildTool(module, Files.isRegularFile(root.resolve("mvnw"))
                    || Files.isRegularFile(root.resolve("mvnw.cmd")));
        }
        Path moduleRoot = module.equals(".") ? root : root.resolve(module);
        return new GradleBuildTool(module,
                Files.isRegularFile(root.resolve("gradlew")) || Files.isRegularFile(root.resolve("gradlew.bat")),
                Files.isDirectory(moduleRoot.resolve("src/jmh/java")));
    }

    private static String resolveKind(Path root, String configured) throws IOException {
        if (configured != null && !configured.isBlank() && !configured.equals("auto")) {
            return configured;
        }
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = GRADLE_MARKERS.stream().anyMatch(m -> Files.isRegularFile(root.resolve(m)));
        if (maven && gradle) {
            // Common in the wild — a library that publishes to Maven Central but
            // builds with Gradle keeps both files — so the message has to be
            // actionable from either side of `init`: before it, only the flag
            // exists; after it, only the config does.
            throw new BuildToolException("this repository has both a pom.xml and a Gradle build file, so which"
                    + " one drives it cannot be inferred.\nRun `autor3search-java init -build-tool maven`"
                    + " (or gradle), or set build_tool: in "
                    + io.github.autor3search.config.Config.PATH + " if it already exists.");
        }
        if (maven) return "maven";
        if (gradle) return "gradle";
        throw new BuildToolException("no pom.xml and no Gradle build file at " + root
                + " — autor3search-java drives a Maven or Gradle build, and cannot compile, test or"
                + " classpath a repository it does not recognise.");
    }

    /**
     * The repository-relative directory of the module declaring the benchmarks:
     * the deepest ancestor of a benchmark source file that holds a build file for
     * this tool.
     *
     * <p>Benchmarks spread across several modules are REFUSED rather than merged.
     * A classpath belongs to one module, so measuring two modules' benchmarks in
     * one JMH invocation would need a classpath that is the union of two — at
     * which point a dependency version resolved differently in each module has
     * silently become one version, and the numbers no longer describe either
     * module as it actually builds.
     */
    private static String inferModule(Path root, String kind, List<Benchmark> benchmarks) throws IOException {
        if (benchmarks == null || benchmarks.isEmpty()) return ".";
        TreeMap<String, Integer> byModule = new TreeMap<>();
        for (Benchmark b : benchmarks) {
            String module = moduleOf(root, kind, b.file());
            byModule.merge(module, 1, Integer::sum);
        }
        if (byModule.size() > 1) {
            Set<String> modules = new LinkedHashSet<>(byModule.keySet());
            throw new BuildToolException("benchmarks were found in more than one module: " + modules
                    + " — a run measures one module's classpath, so narrow the `benchmarks:` list in "
                    + io.github.autor3search.config.Config.PATH + " to a single module and start a"
                    + " separate run for each of the others.");
        }
        return byModule.firstKey();
    }

    /** The nearest ancestor directory of rel that holds a build file, "." for the root. */
    private static String moduleOf(Path root, String kind, String rel) {
        Path dir = root.resolve(rel).getParent();
        while (dir != null && dir.startsWith(root)) {
            if (hasBuildFile(dir, kind)) {
                String r = root.relativize(dir).toString().replace('\\', '/');
                return r.isEmpty() ? "." : r;
            }
            dir = dir.getParent();
        }
        return ".";
    }

    private static boolean hasBuildFile(Path dir, String kind) {
        if (kind.equals("maven")) return Files.isRegularFile(dir.resolve("pom.xml"));
        return GRADLE_MARKERS.stream().anyMatch(m -> Files.isRegularFile(dir.resolve(m)));
    }
}
