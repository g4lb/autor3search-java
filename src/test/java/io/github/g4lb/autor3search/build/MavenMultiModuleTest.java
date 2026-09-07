package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.runner.ProcResult;
import io.github.g4lb.autor3search.runner.ProcRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real two-module Maven reactor, because the failure this guards against is
 * invisible to anything smaller.
 *
 * <p>A module resolved from inside its own directory pulls its siblings from the
 * local repository. If they are not installed the build simply fails; if someone
 * installs them to make that stop, every later measurement silently uses a STALE
 * sibling jar that will never reflect another edit — so an experiment touching
 * that module is measured against code that did not change, and discards forever
 * with nothing explaining why. That is the outcome this test exists to make
 * impossible.
 */
class MavenMultiModuleTest {

    private static Path build(Path root) throws IOException {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <modules><module>core</module><module>app</module></modules>
                </project>
                """);
        write(root.resolve("core/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        write(root.resolve("app/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies><dependency>
                    <groupId>demo</groupId><artifactId>core</artifactId><version>1.0</version>
                  </dependency></dependencies>
                </project>
                """);
        write(root.resolve("core/src/main/java/core/Core.java"),
                "package core;\npublic class Core { public static int v() { return 1; } }\n");
        write(root.resolve("app/src/main/java/app/App.java"),
                "package app;\npublic class App { public static int v() { return core.Core.v(); } }\n");
        return root;
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Test
    void aSiblingModuleIsBuiltFromSourceAndPutOnTheClasspath(@TempDir Path root) throws IOException {
        Assumptions.assumeTrue(onPath(MavenBuildTool.defaultExecutable(
                        System.getProperty("os.name", "").toLowerCase().startsWith("windows"))),
                "maven is not on PATH");
        build(root);

        BuildTool tool = BuildTools.forModule(root, "maven", "app");
        // Commands must run from the reactor root, or -pl cannot select anything.
        assertTrue(tool.workingDir(root).equals(root), "maven must drive a reactor from its root");

        ProcRunner runner = new ProcRunner(tool.workingDir(root), Duration.ofMinutes(10), null);
        ProcResult compiled = tool.compile(root, runner);
        assertTrue(compiled.ok(), "compile failed:\n" + compiled.tail(30));
        assertTrue(Files.exists(root.resolve("core/target/classes/core/Core.class")),
                "the sibling module was not built from source");

        String classpath = tool.benchClasspath(root, runner);
        // Compared as real paths: the harness prepends the module's own output
        // using the path it was given, while Maven emits dependencies through its
        // own resolution — and on macOS one of those is /var and the other
        // /private/var for the same directory.
        List<Path> entries = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            entries.add(real(Path.of(entry)));
        }
        assertTrue(entries.contains(real(root.resolve("core/target/classes"))),
                "the sibling must come from its target/classes, not an installed jar:\n" + classpath);
        assertTrue(entries.contains(real(root.resolve("app/target/classes"))), classpath);
    }

    /** The path with symlinked ancestors resolved, or unchanged if it does not exist. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }
}
