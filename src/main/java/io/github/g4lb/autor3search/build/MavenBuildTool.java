package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Drives a Maven build. */
public final class MavenBuildTool implements BuildTool {

    /**
     * Pinned rather than left to the project's own plugin management. An
     * unversioned {@code dependency:build-classpath} resolves to whatever the
     * project (or the superpom) happens to pin, which differs between the
     * repository and a fresh CI checkout — and a classpath resolved by two
     * different plugin versions is exactly the kind of difference that makes a
     * baseline and a candidate incomparable for reasons unrelated to the code.
     */
    private static final String DEPENDENCY_PLUGIN =
            "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath";

    private final String moduleDir;
    private final boolean wrapper;

    MavenBuildTool(String moduleDir, boolean wrapper) {
        this.moduleDir = moduleDir;
        this.wrapper = wrapper;
    }

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public String moduleDir() {
        return moduleDir;
    }

    @Override
    public String describe() {
        return "maven (" + (wrapper ? "./mvnw" : "mvn on PATH") + ", module " + moduleDir + ")";
    }

    /**
     * The executable for one tree. The wrapper is resolved against that tree's own
     * root rather than cached, because the pinned baseline worktree is a separate
     * checkout with its own {@code mvnw} — and running the repository's wrapper
     * against the worktree would build the wrong tree's Maven configuration.
     */
    private String exe(Path treeRoot) {
        boolean windows = isWindows();
        if (wrapper) {
            Path w = treeRoot.resolve(windows ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(w)) return w.toAbsolutePath().toString();
        }
        return defaultExecutable(windows);
    }

    /**
     * The launcher to look for on PATH.
     *
     * <p>"mvn.cmd", not "mvn", on Windows: Maven there is a batch script, and
     * ProcessBuilder resolves a PATH entry literally rather than trying the
     * PATHEXT extensions a shell would. Asking for "mvn" fails with "The system
     * cannot find the file specified" and no hint as to why.
     */
    static String defaultExecutable(boolean windows) {
        return windows ? "mvn.cmd" : "mvn";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /**
     * Maven runs from the TREE ROOT and selects the module with {@code -pl}, not
     * from inside the module.
     *
     * <p>Running inside it looks equivalent and is not. A module there resolves
     * its siblings from the local repository — installed jars — so a sibling the
     * agent has just edited is either missing entirely (the build fails with a
     * dependency resolution error) or, once someone runs {@code mvn install} to
     * make that go away, is measured from a STALE artifact that will never again
     * reflect a single edit. The second outcome is far worse than the first: every
     * experiment touching that module would be measured against code that did not
     * change, and would DISCARD forever with nothing explaining why.
     *
     * <p>{@code -am} builds the modules the selected one depends on, from source,
     * in the same reactor — which is also what makes the classpath below point at
     * a sibling's {@code target/classes} rather than at its installed jar.
     */
    @Override
    public Path workingDir(Path treeRoot) {
        return real(treeRoot);
    }

    private List<String> base(Path treeRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe(treeRoot));
        cmd.add("-B");
        cmd.add("--no-transfer-progress");
        if (!moduleDir.equals(".")) {
            cmd.add("-pl");
            // A path RELATIVE to the execution root, in the platform's own
            // separators. Maven rejects an absolute selector outright, and it
            // resolves a relative one against its execution root before comparing
            // with each project's directory — which is why workingDir above
            // resolves that root. Left unresolved, the two sides can spell the same
            // directory differently (a Windows 8.3 short name against its long
            // form, /var against /private/var) and the reactor then reports "Could
            // not find the selected project", which says nothing about the cause.
            cmd.add(moduleDir.replace('/', java.io.File.separatorChar));
            cmd.add("-am");
        }
        return cmd;
    }

    /** The path with symlinks and short names resolved, or unchanged if it does not exist yet. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @Override
    public ProcResult compile(Path treeRoot, ProcRunner runner) throws IOException {
        List<String> cmd = base(treeRoot);
        cmd.add("-DskipTests");
        cmd.add("test-compile");
        return runner.run(cmd);
    }

    @Override
    public ProcResult test(Path treeRoot, ProcRunner runner) throws IOException {
        List<String> cmd = base(treeRoot);
        cmd.add("test");
        return runner.run(cmd);
    }

    /**
     * Resolves the test-scope classpath and prepends the module's own compiled
     * output.
     *
     * <p>The plugin is asked to write a FILE rather than to print, because its
     * stdout is interleaved with Maven's reactor output and with any other plugin
     * that logs during the same run. Parsing a classpath out of that is a guess,
     * and a wrong guess here is a benchmark that silently cannot find its own
     * classes.
     */
    @Override
    public String benchClasspath(Path treeRoot, ProcRunner runner) throws IOException {
        Path module = moduleRoot(treeRoot);
        Path out = Files.createTempFile("autor3search-cp", ".txt");
        try {
            List<String> cmd = base(treeRoot);
            cmd.add("-DskipTests");
            cmd.add("-Dmdep.outputFile=" + out.toAbsolutePath());
            cmd.add("-Dmdep.includeScope=test");
            // test-compile FIRST, in the same invocation. The classpath goal on its
            // own cannot resolve a sibling module that has not been built in this
            // reactor session, and it is that same session which makes Maven hand
            // back the sibling's target/classes instead of its installed jar.
            cmd.add("test-compile");
            cmd.add(DEPENDENCY_PLUGIN);
            ProcResult res = runner.run(cmd);
            if (!res.ok()) {
                throw new BuildToolException("maven could not resolve the benchmark classpath for module "
                        + moduleDir + " (exit " + res.exitCode() + "):\n" + res.tail(30));
            }
            String deps = Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8).trim() : "";
            List<String> entries = new ArrayList<>();
            entries.add(module.resolve("target/classes").toString());
            entries.add(module.resolve("target/test-classes").toString());
            if (!deps.isEmpty()) entries.add(deps);
            return String.join(File.pathSeparator, entries);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Override
    public boolean isDependencyFile(String rel) {
        String s = rel.replace('\\', '/');
        return s.equals("pom.xml") || s.endsWith("/pom.xml")
                || s.equals(".mvn/extensions.xml") || s.endsWith("/.mvn/extensions.xml")
                || s.equals(".mvn/maven.config") || s.endsWith("/.mvn/maven.config");
    }
}
