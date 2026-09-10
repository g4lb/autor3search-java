package io.github.autor3search.config;

import io.github.autor3search.util.Durations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The run configuration: what is measured, what the agent may edit, and how
 * strictly a result has to hold up before it is banked.
 *
 * <p>It lives in the repository, at {@link #PATH}, because humans own it and
 * want their edits in version control. That means the agent can reach it, so it
 * is protected by integrity checking rather than by relocation: {@code baseline}
 * records its SHA-256 and {@code eval} fails the run if it ever changes. Every
 * other piece of state the score depends on lives outside the repository
 * entirely.
 */
public final class Config {
    /** Where the config lives, relative to the repository root. */
    public static final String PATH = ".autor3search/config.yaml";

    /**
     * The smallest {@link #count} at which the significance test used by the
     * scorer (an exact Mann-Whitney rank-sum test) can ever report p &lt; 0.05,
     * however large or clean the improvement is. At 2 or 3 measured rounds per
     * side the best achievable two-sided p-value is 0.3333 and 0.1 — both above
     * the default alpha — so every experiment would DISCARD on a technicality,
     * with nothing in the output explaining why.
     */
    public static final int MIN_COUNT = 4;

    /** Benchmarks that count toward the score. Empty means every discovered one. */
    public List<String> benchmarks = new ArrayList<>();
    /** Path patterns the agent may modify, e.g. "./..." or "./src/main/java/...". */
    public List<String> scope = new ArrayList<>(List.of("./..."));
    /** Interleaved measurement rounds per side. One round is one JMH invocation. */
    public int count = 10;
    /** JMH forks per round. Each fork is a fresh JVM. */
    public int forks = 1;
    /** JMH warmup iterations per fork. The JVM needs these; do not set 0 lightly. */
    public int warmupIterations = 5;
    /** JMH measurement iterations per fork. Their mean is one observation. */
    public int measurementIterations = 5;
    /** Duration of each JMH warmup and measurement iteration. */
    public String benchtime = "1s";
    /** Extra JVM arguments for the forked benchmark JVMs, e.g. "-Xmx2g". */
    public List<String> jvmArgs = new ArrayList<>();
    /** Largest tolerated statistically significant regression, in percent. */
    public double maxRegressPct = 5.0;
    /**
     * The smallest geomean improvement, as a percentage, that a KEEP will accept:
     * the score must fall below {@code 1 - minEffectPct/100}, not merely below 1.
     */
    public double minEffectPct = 1.0;
    /** Bounds each subprocess phase (compile, test, one benchmark round). */
    public String timeout = "30m";
    /** Test sources deliberately exempted from the baseline freeze. */
    public List<String> unfreeze = new ArrayList<>();
    /** "auto", "maven" or "gradle". */
    public String buildTool = "auto";

    /** Returns the configuration used for any field a config file omits. */
    public static Config defaults() {
        return new Config();
    }

    /** Parsed {@link #timeout}. */
    public Duration timeoutDuration() {
        return Durations.parse(timeout);
    }

    /** Parsed {@link #benchtime}. */
    public Duration benchtimeDuration() {
        return Durations.parse(benchtime);
    }

    /** Throws {@link ConfigException} naming the first field that is unusable. */
    public void validate() {
        if (count < MIN_COUNT) {
            throw new ConfigException("count must be at least " + MIN_COUNT
                    + ": the significance test cannot report p < 0.05 with fewer than " + MIN_COUNT
                    + " measured rounds per side no matter how large the improvement is, so every experiment"
                    + " would be discarded regardless of what changed (the default is 10)");
        }
        if (forks < 1) {
            throw new ConfigException("forks must be at least 1: JMH measures in a forked JVM, and forks: 0"
                    + " runs the benchmark in the launcher JVM where earlier benchmarks have already"
                    + " polluted the JIT's profile");
        }
        if (warmupIterations < 0) {
            throw new ConfigException("warmup_iterations must not be negative");
        }
        if (warmupIterations == 0) {
            throw new ConfigException("warmup_iterations must be at least 1: a JVM measured cold reports"
                    + " interpreter and C1 timings, not the steady-state speed of the code being compared");
        }
        if (measurementIterations < 1) {
            throw new ConfigException("measurement_iterations must be at least 1");
        }
        if (maxRegressPct < 0) {
            throw new ConfigException("max_regress_pct must not be negative");
        }
        if (minEffectPct < 0 || minEffectPct >= 100) {
            throw new ConfigException("min_effect_pct must be at least 0 and less than 100");
        }
        if (scope == null || scope.isEmpty()) {
            throw new ConfigException("scope must list at least one path pattern");
        }
        for (String s : scope) {
            if (s == null || s.isBlank()) {
                throw new ConfigException("scope must not contain an empty or whitespace-only entry");
            }
        }
        if (Durations.isCountForm(benchtime)) {
            throw new ConfigException("benchtime \"" + benchtime + "\" asks for a fixed number of iterations,"
                    + " which is deliberately unsupported: a fixed count makes rounds incomparable, because a"
                    + " candidate that is twice as fast finishes in half the wall time and is therefore measured"
                    + " under different thermal conditions — exactly what the interleaved A/B design exists to"
                    + " eliminate. Use a duration instead, e.g. benchtime: 1s");
        }
        try {
            benchtimeDuration();
        } catch (IllegalArgumentException e) {
            throw new ConfigException("benchtime " + e.getMessage());
        }
        try {
            timeoutDuration();
        } catch (IllegalArgumentException e) {
            throw new ConfigException("timeout " + e.getMessage());
        }
        if (buildTool == null || !List.of("auto", "maven", "gradle").contains(buildTool)) {
            throw new ConfigException("build_tool must be one of auto, maven or gradle, got \"" + buildTool + "\"");
        }
    }
}
