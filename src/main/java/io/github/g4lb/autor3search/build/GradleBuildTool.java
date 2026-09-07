package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Drives a Gradle build. */
public final class GradleBuildTool implements BuildTool {

    /**
     * An init script is the only way to ask an arbitrary Gradle build for a
     * source set's runtime classpath without editing the build itself — and
     * editing the build is exactly what the agent is not allowed to do, so the
     * harness must not do it either. The task writes to a FILE named by a system
     * property rather than printing, because Gradle routes {@code println} through
     * its own logger at LIFECYCLE level, which {@code -q} suppresses; a classpath
     * that silently vanishes at one verbosity and appears at another is not a
     * mechanism to build on.
     */
    private static final String INIT_SCRIPT = """
            // Written by autor3search-java. Temporary; not part of the project.
            gradle.rootProject { rootProj ->
                rootProj.allprojects { proj ->
                    proj.afterEvaluate {
                        if (proj.plugins.hasPlugin('java')) {
                            proj.tasks.register('autor3searchBenchClasspath') {
                                doLast {
                                    def sets = proj.sourceSets
                                    def ss = sets.findByName('jmh') ?: sets.findByName('test') ?: sets.getByName('main')
                                    def target = System.getProperty('autor3search.classpathOut')
                                    new File(target).write(ss.runtimeClasspath.asPath, 'UTF-8')
                                }
                            }
                        }
                    }
                }
            }
            """;

    private final String moduleDir;
    private final boolean wrapper;
    private final boolean hasJmhSourceSet;

    GradleBuildTool(String moduleDir, boolean wrapper, boolean hasJmhSourceSet) {
        this.moduleDir = moduleDir;
        this.wrapper = wrapper;
        this.hasJmhSourceSet = hasJmhSourceSet;
    }

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public String moduleDir() {
        return moduleDir;
    }

    @Override
    public String describe() {
        return "gradle (" + (wrapper ? "./gradlew" : "gradle on PATH") + ", project " + projectPath()
                + (hasJmhSourceSet ? ", src/jmh source set" : "") + ")";
    }

    /** The Gradle project path for the benchmark module, e.g. ":" or ":core". */
    private String projectPath() {
        if (moduleDir.equals(".")) return ":";
        return ":" + moduleDir.replace('/', ':');
    }

    /** Prefixes a task name with the project path, e.g. ":core:test". */
    private String task(String name) {
        return projectPath().equals(":") ? ":" + name : projectPath() + ":" + name;
    }

    private String exe(Path treeRoot) {
        boolean windows = MavenBuildTool.isWindows();
        if (wrapper) {
            Path w = treeRoot.resolve(windows ? "gradlew.bat" : "gradlew");
            if (Files.isRegularFile(w)) return w.toAbsolutePath().toString();
        }
        return defaultExecutable(windows);
    }

    /**
     * The launcher to look for on PATH. See
     * {@link MavenBuildTool#defaultExecutable}: ProcessBuilder will not find
     * "gradle" on Windows, where the launcher is gradle.bat.
     */
    static String defaultExecutable(boolean windows) {
        return windows ? "gradle.bat" : "gradle";
    }

    /**
     * Every Gradle invocation runs from the TREE ROOT, not the module directory.
     * Gradle addresses a submodule by project path from the settings file at the
     * root; running from inside the module would work only when that module is
     * itself a standalone build.
     */
    @Override
    public Path workingDir(Path treeRoot) {
        return treeRoot;
    }

    /**
     * {@code --no-daemon} on purpose. A Gradle daemon left running would be a
     * long-lived JVM doing its own work on the machine for the rest of the
     * experiment — including while the benchmarks are being timed, which is
     * exactly the background load the interleaving cannot cancel because it is not
     * the same on both sides. The few seconds of start-up it costs are paid four
     * times per experiment, against measurement rounds that take minutes.
     */
    private List<String> base(Path treeRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe(treeRoot));
        cmd.add("--console=plain");
        cmd.add("--no-daemon");
        cmd.add("-q");
        return cmd;
    }

    @Override
    public ProcResult compile(Path treeRoot, ProcRunner runner) throws IOException {
        List<String> cmd = base(treeRoot);
        cmd.add(task("classes"));
        cmd.add(task("testClasses"));
        if (hasJmhSourceSet) cmd.add(task("jmhClasses"));
        return runner.run(cmd);
    }

    @Override
    public ProcResult test(Path treeRoot, ProcRunner runner) throws IOException {
        List<String> cmd = base(treeRoot);
        cmd.add(task("test"));
        return runner.run(cmd);
    }

    @Override
    public String benchClasspath(Path treeRoot, ProcRunner runner) throws IOException {
        Path script = Files.createTempFile("autor3search-init", ".gradle");
        Path out = Files.createTempFile("autor3search-cp", ".txt");
        try {
            Files.writeString(script, INIT_SCRIPT, StandardCharsets.UTF_8);
            List<String> cmd = base(treeRoot);
            cmd.add("--init-script");
            cmd.add(script.toAbsolutePath().toString());
            cmd.add("-Dautor3search.classpathOut=" + out.toAbsolutePath());
            cmd.add(task("autor3searchBenchClasspath"));
            ProcResult res = runner.run(cmd);
            String cp = Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8).trim() : "";
            if (!res.ok() || cp.isEmpty()) {
                throw new BuildToolException("gradle could not resolve the benchmark classpath for project "
                        + projectPath() + " (exit " + res.exitCode() + "):\n" + res.tail(30)
                        + "\n\nThe project must apply the `java` plugin, and its benchmarks must live in the"
                        + " jmh, test or main source set.");
            }
            return cp;
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(out);
        }
    }

    @Override
    public boolean isDependencyFile(String rel) {
        String s = rel.replace('\\', '/');
        String base = s.substring(s.lastIndexOf('/') + 1);
        return switch (base) {
            case "build.gradle", "build.gradle.kts",
                 "settings.gradle", "settings.gradle.kts",
                 "gradle.properties", "libs.versions.toml",
                 "gradle-wrapper.properties" -> true;
            default -> false;
        };
    }
}
