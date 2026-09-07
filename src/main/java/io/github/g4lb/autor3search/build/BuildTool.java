package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;

import java.io.IOException;
import java.nio.file.Path;

/**
 * How one repository is compiled, tested, and put on a classpath JMH can run
 * against.
 *
 * <p>Every method takes the tree it is acting on rather than the tool holding
 * one, because a single evaluation applies the same tool description to two
 * different trees: the candidate in the repository, and the pinned baseline in a
 * worktree outside it. Binding a directory into the tool would make it far too
 * easy to compile one tree and measure the other — a bug that would not fail,
 * only quietly score the wrong thing.
 *
 * <p>{@code runner}'s working directory must be {@link #workingDir} for the tree
 * passed alongside it — which is NOT the same directory for both tools, and that
 * is the reason it is a method rather than an assumption. Maven addresses a
 * module by being run inside it; Gradle addresses one by project path from the
 * settings file at the root. {@code treeRoot} is passed separately because the
 * build wrapper ({@code mvnw}, {@code gradlew}) lives at the tree root even when
 * the benchmarks live in a submodule.
 */
public interface BuildTool {

    /** "maven" or "gradle". */
    String name();

    /**
     * The repository-relative directory of the module that declares the
     * benchmarks, "." for a single-module build.
     */
    String moduleDir();

    /** A one-line description for {@code init} and {@code doctor} output. */
    String describe();

    /** Compiles main and test sources. The correctness gate's first stage. */
    ProcResult compile(Path treeRoot, ProcRunner runner) throws IOException;

    /** Runs the project's own test suite. Correctness is never traded for speed. */
    ProcResult test(Path treeRoot, ProcRunner runner) throws IOException;

    /**
     * Resolves the classpath JMH is launched on: the module's compiled classes
     * plus every dependency the benchmarks need. Expensive — it shells out to the
     * build tool — so callers resolve it once per evaluation, never per round.
     */
    String benchClasspath(Path treeRoot, ProcRunner runner) throws IOException;

    /**
     * Reports whether a repository-relative path describes the project's
     * DEPENDENCIES or its build. Such a file may never be modified by the agent,
     * regardless of what {@code scope} says: a swapped dependency or a changed
     * compiler flag changes what is being measured rather than how fast it runs,
     * and is a decision a human makes.
     */
    boolean isDependencyFile(String rel);

    /** The module directory inside a given tree. */
    default Path moduleRoot(Path treeRoot) {
        return moduleDir().equals(".") ? treeRoot : treeRoot.resolve(moduleDir());
    }

    /**
     * The directory this tool's own commands must run from, which callers use as
     * the runner's working directory. Defaults to the module; Gradle overrides it
     * to the tree root.
     */
    default Path workingDir(Path treeRoot) {
        return moduleRoot(treeRoot);
    }
}
