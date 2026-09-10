package io.github.autor3search.measure;

import io.github.autor3search.bench.BenchException;
import io.github.autor3search.bench.BenchSet;
import io.github.autor3search.bench.JmhResults;
import io.github.autor3search.config.Config;
import io.github.autor3search.runner.ProcResult;
import io.github.autor3search.runner.ProcRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Runs one JMH invocation and reads back what it measured. */
public final class Jmh {
    private Jmh() {}

    /** JMH's launcher. Invoked directly rather than through the build tool. */
    private static final String MAIN_CLASS = "org.openjdk.jmh.Main";

    /**
     * The JVM the benchmarks run in: the one running the harness.
     *
     * <p>Deliberately not whatever {@code java} the PATH resolves to, which can
     * differ between the shell the human started the run from and the shell the
     * agent invokes {@code eval} from. The baseline and the candidate must be
     * measured on the same JVM or the comparison means nothing, and pinning it to
     * this process's own runtime is the only way to guarantee that without asking
     * the user to configure it.
     */
    public static String javaExecutable() {
        String home = System.getProperty("java.home");
        String exe = System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "java.exe" : "java";
        Path p = Path.of(home, "bin", exe);
        return Files.isExecutable(p) ? p.toString() : "java";
    }

    /**
     * Builds a JMH selection regexp matching exactly these benchmarks. An empty
     * list yields ".", meaning every benchmark JMH can find.
     *
     * <p>Every name is quoted. Names discovered from source are always valid Java
     * identifiers and need no escaping, but {@code benchmarks:} is documented as
     * hand-editable, and a stray metacharacter in a hand-typed name would
     * silently BROADEN the pattern to measure benchmarks nobody selected.
     */
    public static String pattern(List<String> names) {
        if (names == null || names.isEmpty()) return ".";
        List<String> quoted = new ArrayList<>(names.size());
        for (String n : names) quoted.add(Pattern.quote(n));
        return "^(" + String.join("|", quoted) + ")$";
    }

    /**
     * Runs one measurement round and returns what it measured.
     *
     * <p>{@code -bm avgt -tu ns} is forced on every invocation rather than left to
     * the benchmark's own annotations. The scorer compares ratios of a
     * lower-is-better number; a benchmark annotated for throughput would report a
     * higher-is-better one, and its regressions would read as improvements.
     */
    public static BenchSet round(ProcRunner runner, Path workingDir, String classpath,
                                 String pattern, Config cfg) throws IOException {
        Path out = Files.createTempFile("autor3search-jmh", ".json");
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    javaExecutable(),
                    "-cp", classpath,
                    MAIN_CLASS,
                    pattern,
                    "-bm", "avgt",
                    "-tu", "ns",
                    "-f", String.valueOf(cfg.forks),
                    "-wi", String.valueOf(cfg.warmupIterations),
                    "-i", String.valueOf(cfg.measurementIterations),
                    "-w", cfg.benchtime,
                    "-r", cfg.benchtime,
                    "-foe", "true",
                    "-prof", "gc",
                    "-rf", "json",
                    "-rff", out.toAbsolutePath().toString()));
            if (cfg.jvmArgs != null && !cfg.jvmArgs.isEmpty()) {
                cmd.add("-jvmArgs");
                cmd.add(String.join(" ", cfg.jvmArgs));
            }
            ProcResult res = runner.run(cmd);
            if (res.timedOut()) {
                throw new IOException("benchmark round timed out after " + runner.timeout()
                        + " in " + workingDir);
            }
            if (!res.ok()) {
                throw new IOException(diagnose(res, workingDir, pattern));
            }
            if (!Files.exists(out) || Files.size(out) == 0) {
                throw new IOException("JMH exited cleanly but wrote no results in " + workingDir
                        + " — no benchmark matched " + pattern + ".\n" + res.tail(20));
            }
            BenchSet set;
            try (BufferedReader r = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
                set = JmhResults.parse(r);
            }
            if (set.isEmpty()) {
                throw new IOException("no benchmarks matched " + pattern + " in " + workingDir);
            }
            return set;
        } catch (BenchException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Turns a failed JMH invocation into a message that names the likely cause.
     * The two failures a Java repository actually hits — JMH absent from the
     * classpath, and the annotation processor never having generated the
     * benchmark classes — both surface as opaque JVM errors that say nothing
     * about what to fix.
     */
    private static String diagnose(ProcResult res, Path dir, String pattern) {
        String all = res.stdout() + "\n" + res.stderr();
        StringBuilder sb = new StringBuilder();
        sb.append("benchmark round failed in ").append(dir)
                .append(" (exit ").append(res.exitCode()).append(")");
        if (all.contains("ClassNotFoundException: " + MAIN_CLASS) || all.contains("Could not find or load main class")) {
            sb.append("\n\nJMH is not on this project's benchmark classpath. Add both artifacts to the module"
                    + " that declares the benchmarks:\n"
                    + "  org.openjdk.jmh:jmh-core           (the harness)\n"
                    + "  org.openjdk.jmh:jmh-generator-annprocess  (the annotation processor, which"
                    + " generates the runnable benchmark classes at compile time)");
        } else if (all.contains("/META-INF/BenchmarkList")) {
            sb.append("\n\nJMH's generated benchmark index (META-INF/BenchmarkList) is missing from the"
                    + " compiled output, which means jmh-generator-annprocess never ran. The @Benchmark"
                    + " methods compile without it, but the classes JMH actually executes are never"
                    + " generated.\nOn JDK 23 and later this is almost always the cause: javac refuses to"
                    + " run an annotation processor found only on the classpath unless asked explicitly."
                    + " Configure the module that declares the benchmarks:\n"
                    + "  Maven:  maven-compiler-plugin <configuration><proc>full</proc></configuration>\n"
                    + "  Gradle: tasks.withType(JavaCompile) { options.compilerArgs << '-proc:full' }\n"
                    + "or put the processor on an explicit annotation processor path.");
        } else if (all.contains("No matching benchmarks")) {
            sb.append("\n\nJMH found no benchmark matching ").append(pattern)
                    .append(".\nThis usually means jmh-generator-annprocess did not run: without it the"
                            + " @Benchmark methods compile, but the classes JMH actually executes are never"
                            + " generated. Check that it is on the annotation processor path of the module"
                            + " that declares the benchmarks.");
        }
        sb.append('\n').append(res.tail(30));
        return sb.toString();
    }
}
