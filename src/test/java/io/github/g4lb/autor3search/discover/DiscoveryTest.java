package io.github.g4lb.autor3search.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryTest {

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static String benchmarkSource(String pkg, String type, String... methods) {
        StringBuilder sb = new StringBuilder("package " + pkg + ";\n\n"
                + "import org.openjdk.jmh.annotations.Benchmark;\n\n"
                + "public class " + type + " {\n");
        for (String m : methods) {
            sb.append("    @Benchmark\n    public int ").append(m).append("() { return 1; }\n");
        }
        return sb.append("}\n").toString();
    }

    @Test
    void findsBenchmarksWithTheirFullyQualifiedNames(@TempDir Path root) throws IOException {
        write(root, "src/test/java/com/example/ParseBenchmark.java",
                benchmarkSource("com.example", "ParseBenchmark", "small", "large"));
        List<Benchmark> found = Discovery.benchmarks(root);
        assertEquals(List.of("com.example.ParseBenchmark.large", "com.example.ParseBenchmark.small"),
                Discovery.names(found));
        assertEquals("src/test/java/com/example/ParseBenchmark.java", found.get(0).file());
        assertEquals("com.example.ParseBenchmark", found.get(0).type());
    }

    @Test
    void readsThroughOtherAnnotationsBeforeTheMethod(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/B.java", """
                package a;
                import org.openjdk.jmh.annotations.*;
                public class B {
                    @Benchmark
                    @BenchmarkMode(Mode.AverageTime)
                    @OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
                    @Fork(value = 1, jvmArgs = {"-Xmx1g"})
                    public java.util.Map<String, Integer> run(Blackhole bh) { return null; }
                }
                """);
        assertEquals(List.of("a.B.run"), Discovery.names(Discovery.benchmarks(root)));
    }

    /**
     * The scanner strips comments and string literals first, so text that merely
     * mentions the annotation is not mistaken for a declaration of one.
     */
    @Test
    void ignoresTheAnnotationInsideCommentsAndStrings(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/C.java", """
                package a;
                import org.openjdk.jmh.annotations.Benchmark;
                public class C {
                    // @Benchmark commentedOut()
                    /* @Benchmark alsoCommented() */
                    private static final String DOC = "@Benchmark inAString()";
                    @Benchmark
                    public int real() { return 1; }
                }
                """);
        assertEquals(List.of("a.C.real"), Discovery.names(Discovery.benchmarks(root)));
    }

    @Test
    void doesNotDescendIntoBuildOutput(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/Good.java", benchmarkSource("a", "Good", "run"));
        write(root, "target/test-classes/a/Generated.java", benchmarkSource("a", "Generated", "run"));
        write(root, "build/a/Generated.java", benchmarkSource("a", "Generated", "run"));
        write(root, ".git/a/Generated.java", benchmarkSource("a", "Generated", "run"));
        assertEquals(List.of("a.Good.run"), Discovery.names(Discovery.benchmarks(root)));
    }

    @Test
    void frozenSetCoversTestSourcesAndAnyBenchmarkWhereverItLives(@TempDir Path root) throws IOException {
        write(root, "src/main/java/a/Main.java", "package a;\npublic class Main {}\n");
        write(root, "src/main/java/a/MainBenchmark.java", benchmarkSource("a", "MainBenchmark", "run"));
        write(root, "src/test/java/a/MainTest.java", "package a;\nclass MainTest {}\n");
        write(root, "src/jmh/java/a/JmhBenchmark.java", benchmarkSource("a", "JmhBenchmark", "run"));

        assertEquals(List.of(
                        "src/jmh/java/a/JmhBenchmark.java",
                        "src/main/java/a/MainBenchmark.java",
                        "src/test/java/a/MainTest.java"),
                Discovery.frozenFiles(root, List.of()));
    }

    @Test
    void unfreezeRemovesAFileFromTheFrozenSet(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/AT.java", "package a;\nclass AT {}\n");
        write(root, "src/test/java/a/BT.java", "package a;\nclass BT {}\n");
        assertEquals(List.of("src/test/java/a/BT.java"),
                Discovery.frozenFiles(root, List.of("src/test/java/a/AT.java")));
    }

    @Test
    void recognisesTestPathsInAnyModule() {
        assertTrue(Discovery.isTestPath("src/test/java/a/A.java"));
        assertTrue(Discovery.isTestPath("core/src/test/java/a/A.java"));
        assertTrue(Discovery.isTestPath("src/jmh/java/a/A.java"));
        assertTrue(Discovery.isTestPath("modules/core/src/jmh/java/a/A.java"));
        assertFalse(Discovery.isTestPath("src/main/java/a/A.java"));
        assertFalse(Discovery.isTestPath("src/test/resources/a.txt"));
    }

    @Test
    void anUnparseableFileIsSkippedRatherThanFailingDiscovery(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/Broken.java", "package a; public class Broken { @Benchmark");
        write(root, "src/test/java/a/Good.java", benchmarkSource("a", "Good", "run"));
        assertEquals(List.of("a.Good.run"), Discovery.names(Discovery.benchmarks(root)));
    }

    @Test
    void namesAreSortedAndDeduplicated(@TempDir Path root) throws IOException {
        write(root, "src/test/java/a/Z.java", benchmarkSource("a", "Z", "b", "a"));
        write(root, "src/test/java/a/A.java", benchmarkSource("a", "A", "c"));
        assertEquals(List.of("a.A.c", "a.Z.a", "a.Z.b"), Discovery.names(Discovery.benchmarks(root)));
    }

    @Test
    void aRepositoryWithNoBenchmarksReportsNone(@TempDir Path root) throws IOException {
        write(root, "src/main/java/a/Main.java", "package a;\npublic class Main {}\n");
        assertTrue(Discovery.benchmarks(root).isEmpty());
    }
}
