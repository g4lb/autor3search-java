package io.github.autor3search.discover;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds JMH benchmarks and the source files that must be frozen, by reading the
 * repository rather than by building it.
 *
 * <p>Two sets come out of here and they are not the same set. The BENCHMARKS are
 * what gets measured. The FROZEN FILES are everything whose content the success
 * criteria depend on: the unit tests that gate correctness, and the benchmark
 * sources that define the metric itself. An agent that could rewrite either one
 * could make itself win without making anything faster, so both are snapshotted
 * at baseline and restored before every evaluation.
 */
public final class Discovery {
    private Discovery() {}

    /** Directory names never descended into: build output, VCS metadata, tool caches. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "build", "out", "node_modules", "bin");

    /**
     * Source-root segments whose contents are frozen wholesale. {@code src/jmh}
     * is the Gradle JMH plugin's convention; {@code src/test} is where a Maven
     * project almost always puts both its tests and its benchmarks.
     */
    private static final List<String> TEST_ROOT_SEGMENTS = List.of("src/test/java/", "src/jmh/java/");

    /** Reports whether a repository-relative path lies under a frozen source root. */
    public static boolean isTestPath(String rel) {
        String s = rel.replace('\\', '/');
        for (String seg : TEST_ROOT_SEGMENTS) {
            if (s.startsWith(seg) || s.contains("/" + seg)) return true;
        }
        return false;
    }

    /** Every benchmark declared in the repository, sorted by fully qualified name. */
    public static List<Benchmark> benchmarks(Path root) throws IOException {
        List<Benchmark> out = new ArrayList<>();
        walkJavaFiles(root, rel -> out.addAll(benchmarksIn(root, rel)));
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    /**
     * Every repository-relative source file whose content the metric depends on,
     * minus {@code exclude}, sorted. That is every {@code .java} file under a
     * test or jmh source root, plus any file anywhere that declares a
     * {@code @Benchmark} — a benchmark parked in {@code src/main/java} is still
     * the definition of what "faster" means and still has to be frozen.
     */
    public static List<String> frozenFiles(Path root, List<String> exclude) throws IOException {
        Set<String> skip = new LinkedHashSet<>();
        if (exclude != null) {
            for (String e : exclude) skip.add(e.replace('\\', '/'));
        }
        Set<String> out = new TreeSet<>();
        walkJavaFiles(root, rel -> {
            if (skip.contains(rel)) return;
            if (isTestPath(rel) || !benchmarksIn(root, rel).isEmpty()) {
                out.add(rel);
            }
        });
        return new ArrayList<>(out);
    }

    /** The benchmarks' fully qualified names, sorted and deduplicated. */
    public static List<String> names(List<Benchmark> benchmarks) {
        Set<String> seen = new TreeSet<>();
        for (Benchmark b : benchmarks) seen.add(b.name());
        return new ArrayList<>(seen);
    }

    /** A consumer of repository-relative paths that may throw. */
    private interface PathConsumer {
        void accept(String rel) throws IOException;
    }

    private static void walkJavaFiles(Path root, PathConsumer fn) throws IOException {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.startsWith("_") || SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                    fn.accept(root.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // An unreadable file is not fatal to discovery, and failing the
                    // whole walk over one would make `init` unusable on a tree with
                    // a stale symlink or a permission oddity somewhere in it.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The benchmarks declared in one file, in declaration order. */
    private static List<Benchmark> benchmarksIn(Path root, String rel) {
        String src;
        try {
            src = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // Unreadable or not valid UTF-8: skip it rather than fail discovery.
            return List.of();
        }
        if (!src.contains("@Benchmark")) return List.of();
        return scan(JavaSource.strip(src), rel);
    }

    /**
     * Walks the stripped source tracking the enclosing type stack, and records a
     * benchmark for every {@code @Benchmark} that is followed by a method
     * declaration.
     */
    private static List<Benchmark> scan(String s, String rel) {
        String pkg = JavaSource.packageOf(s);
        List<Benchmark> out = new ArrayList<>();
        Deque<String> types = new ArrayDeque<>();
        String pendingType = null;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '{') {
                types.addLast(pendingType == null ? "" : pendingType);
                pendingType = null;
                continue;
            }
            if (c == '}') {
                if (!types.isEmpty()) types.removeLast();
                continue;
            }
            if (c == '@' && s.startsWith("@Benchmark", i)
                    && (i + 10 >= n || !JavaSource.isIdentPart(s.charAt(i + 10)))) {
                String method = JavaSource.methodNameAfter(s, i + 10);
                if (method != null) {
                    String type = qualify(pkg, types);
                    if (!type.isEmpty()) {
                        out.add(new Benchmark(type + "." + method, rel, type, method));
                    }
                }
                i += 9;
                continue;
            }
            if (!JavaSource.isIdentPart(c) || (i > 0 && JavaSource.isIdentPart(s.charAt(i - 1)))) {
                continue;
            }
            int end = i;
            while (end < n && JavaSource.isIdentPart(s.charAt(end))) end++;
            String word = s.substring(i, end);
            if (word.equals("class") || word.equals("interface") || word.equals("enum") || word.equals("record")) {
                int j = end;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                int k = j;
                while (k < n && JavaSource.isIdentPart(s.charAt(k))) k++;
                if (k > j) pendingType = s.substring(j, k);
            }
            i = end - 1;
        }
        return out;
    }

    /** Joins the package and the enclosing type stack into a qualified type name. */
    private static String qualify(String pkg, Deque<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String t : types) {
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(t);
        }
        if (sb.length() == 0) return "";
        return pkg.isEmpty() ? sb.toString() : pkg + "." + sb;
    }
}
