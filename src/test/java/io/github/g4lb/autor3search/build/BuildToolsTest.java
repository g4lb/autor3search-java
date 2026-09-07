package io.github.g4lb.autor3search.build;

import io.github.g4lb.autor3search.discover.Benchmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolsTest {

    private static void touch(Path root, String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        Files.writeString(p, "", StandardCharsets.UTF_8);
    }

    private static Benchmark bench(String file) {
        return new Benchmark("a.B.run", file, "a.B", "run");
    }

    @Test
    void detectsMavenFromAPom(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        BuildTool tool = BuildTools.detect(root, "auto", List.of(bench("src/test/java/a/B.java")));
        assertEquals("maven", tool.name());
        assertEquals(".", tool.moduleDir());
    }

    @Test
    void detectsGradleFromAnyOfItsBuildFiles(@TempDir Path root) throws IOException {
        touch(root, "build.gradle.kts");
        assertEquals("gradle", BuildTools.detect(root, "auto", List.of()).name());
    }

    @Test
    void detectsTheWrapperWhenOneIsPresent(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        assertTrue(BuildTools.detect(root, "auto", List.of()).describe().contains("mvn on PATH"));
        touch(root, "mvnw");
        assertTrue(BuildTools.detect(root, "auto", List.of()).describe().contains("./mvnw"));
    }

    /** Which tool drives a repository with both cannot be inferred, so it is asked for. */
    @Test
    void refusesToGuessBetweenMavenAndGradle(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        touch(root, "build.gradle");
        BuildToolException e = assertThrows(BuildToolException.class,
                () -> BuildTools.detect(root, "auto", List.of()));
        assertTrue(e.getMessage().contains("build_tool:"), e.getMessage());

        // An explicit setting resolves it.
        assertEquals("maven", BuildTools.detect(root, "maven", List.of()).name());
        assertEquals("gradle", BuildTools.detect(root, "gradle", List.of()).name());
    }

    @Test
    void refusesARepositoryItCannotDrive(@TempDir Path root) {
        BuildToolException e = assertThrows(BuildToolException.class,
                () -> BuildTools.detect(root, "auto", List.of()));
        assertTrue(e.getMessage().contains("no pom.xml"), e.getMessage());
    }

    @Test
    void infersTheModuleFromWhereTheBenchmarksLive(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        touch(root, "core/pom.xml");
        BuildTool tool = BuildTools.detect(root, "auto",
                List.of(bench("core/src/test/java/a/B.java")));
        assertEquals("core", tool.moduleDir());
        assertEquals(root.resolve("core"), tool.moduleRoot(root));
    }

    /**
     * A classpath belongs to one module. Merging two would silently resolve a
     * dependency pinned differently in each to a single version, and the numbers
     * would then describe neither module as it actually builds.
     */
    @Test
    void refusesBenchmarksSpreadAcrossModules(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        touch(root, "core/pom.xml");
        touch(root, "web/pom.xml");
        BuildToolException e = assertThrows(BuildToolException.class,
                () -> BuildTools.detect(root, "auto", List.of(
                        bench("core/src/test/java/a/B.java"),
                        bench("web/src/test/java/a/C.java"))));
        assertTrue(e.getMessage().contains("more than one module"), e.getMessage());
        assertTrue(e.getMessage().contains("core"), e.getMessage());
        assertTrue(e.getMessage().contains("web"), e.getMessage());
    }

    /**
     * A benchmark file moved mid-run must not silently re-point the measurement at
     * a different module's classpath, so eval uses the module recorded at baseline
     * rather than re-inferring it.
     */
    @Test
    void forModuleUsesTheRecordedModuleWithoutReInferring(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        BuildTool tool = BuildTools.forModule(root, "maven", "core");
        assertEquals("core", tool.moduleDir());
        assertEquals(".", BuildTools.forModule(root, "maven", null).moduleDir());
        assertEquals(".", BuildTools.forModule(root, "maven", "  ").moduleDir());
    }

    @Test
    void mavenRejectsEveryPomAndMavenConfigFile(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        BuildTool tool = BuildTools.detect(root, "auto", List.of());
        assertTrue(tool.isDependencyFile("pom.xml"));
        assertTrue(tool.isDependencyFile("core/pom.xml"));
        assertTrue(tool.isDependencyFile(".mvn/extensions.xml"));
        assertTrue(tool.isDependencyFile(".mvn/maven.config"));
        assertFalse(tool.isDependencyFile("src/main/java/a/A.java"));
        assertFalse(tool.isDependencyFile("README.md"));
    }

    @Test
    void gradleRejectsEveryBuildAndVersionCatalogFile(@TempDir Path root) throws IOException {
        touch(root, "build.gradle");
        BuildTool tool = BuildTools.detect(root, "auto", List.of());
        for (String rel : List.of("build.gradle", "build.gradle.kts", "settings.gradle",
                "settings.gradle.kts", "gradle.properties", "core/build.gradle.kts",
                "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties")) {
            assertTrue(tool.isDependencyFile(rel), rel);
        }
        assertFalse(tool.isDependencyFile("src/main/java/a/A.java"));
    }

    @Test
    void gradleAddressesSubmodulesByProjectPath(@TempDir Path root) throws IOException {
        touch(root, "settings.gradle");
        assertTrue(BuildTools.forModule(root, "gradle", "core/api").describe().contains(":core:api"));
        assertTrue(BuildTools.forModule(root, "gradle", ".").describe().contains("project :"));
    }

    /**
     * The two tools address a module in opposite ways: Maven by being run inside
     * it, Gradle by project path from the settings file at the root. Running
     * Gradle from the module directory would work only when that module happens to
     * be a standalone build — which is exactly the kind of difference that works
     * on the demo and fails on a real repository.
     */
    @Test
    void eachToolRunsFromTheDirectoryItAddressesModulesFrom(@TempDir Path root) throws IOException {
        touch(root, "pom.xml");
        BuildTool maven = BuildTools.forModule(root, "maven", "core");
        assertEquals(root.resolve("core"), maven.workingDir(root));
        assertEquals(root.resolve("core"), maven.moduleRoot(root));

        BuildTool gradle = BuildTools.forModule(root, "gradle", "core");
        assertEquals(root, gradle.workingDir(root));
        assertEquals(root.resolve("core"), gradle.moduleRoot(root),
                "the benchmark JVM still runs in the module");
    }
}
