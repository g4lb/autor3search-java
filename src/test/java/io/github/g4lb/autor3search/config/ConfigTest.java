package io.github.g4lb.autor3search.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private static Config parse(String yaml) {
        return ConfigLoader.parse(new StringReader(yaml), "config.yaml");
    }

    @Test
    void appliesDefaultsForOmittedFields() {
        Config c = parse("count: 8\n");
        assertEquals(8, c.count);
        assertEquals(1, c.forks);
        assertEquals("1s", c.benchtime);
        assertEquals(5.0, c.maxRegressPct);
        assertEquals(1.0, c.minEffectPct);
        assertEquals(List.of("./..."), c.scope);
        assertEquals("auto", c.buildTool);
    }

    @Test
    void anEmptyDocumentIsAllDefaults() {
        Config c = parse("");
        assertEquals(Config.defaults().count, c.count);
    }

    @Test
    void readsEveryKnownSetting() {
        Config c = parse("""
                benchmarks:
                  - com.example.A.run
                scope:
                  - ./src/main/java/...
                count: 12
                forks: 2
                warmup_iterations: 3
                measurement_iterations: 4
                benchtime: 250ms
                jvm_args:
                  - -Xmx2g
                max_regress_pct: 7.5
                min_effect_pct: 2
                timeout: 45m
                unfreeze:
                  - src/test/java/demo/ScratchTest.java
                build_tool: gradle
                """);
        assertEquals(List.of("com.example.A.run"), c.benchmarks);
        assertEquals(List.of("./src/main/java/..."), c.scope);
        assertEquals(12, c.count);
        assertEquals(2, c.forks);
        assertEquals(3, c.warmupIterations);
        assertEquals(4, c.measurementIterations);
        assertEquals("250ms", c.benchtime);
        assertEquals(List.of("-Xmx2g"), c.jvmArgs);
        assertEquals(7.5, c.maxRegressPct);
        assertEquals(2.0, c.minEffectPct);
        assertEquals("45m", c.timeout);
        assertEquals(List.of("src/test/java/demo/ScratchTest.java"), c.unfreeze);
        assertEquals("gradle", c.buildTool);
    }

    /**
     * A misspelled key is refused, not ignored. The run would otherwise proceed at
     * the default the human thought they had changed, and every verdict afterwards
     * would be scored by rules they did not choose.
     */
    @Test
    void refusesAnUnknownSetting() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("max_regres_pct: 9\n"));
        assertTrue(e.getMessage().contains("max_regres_pct"), e.getMessage());
        assertTrue(e.getMessage().contains("known settings"), e.getMessage());
    }

    @Test
    void refusesADuplicateKey() {
        assertThrows(ConfigException.class, () -> parse("count: 8\ncount: 9\n"));
    }

    /**
     * Below four rounds the exact rank-sum test cannot reach p < 0.05 however
     * large the improvement is, so every experiment would DISCARD on a
     * technicality with nothing in the output explaining why.
     */
    @Test
    void refusesACountBelowTheSignificanceFloor() {
        for (int n : new int[]{0, 1, 2, 3}) {
            ConfigException e = assertThrows(ConfigException.class, () -> parse("count: " + n + "\n"));
            assertTrue(e.getMessage().contains("count must be at least 4"), e.getMessage());
        }
    }

    @Test
    void refusesZeroWarmupIterations() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("warmup_iterations: 0\n"));
        assertTrue(e.getMessage().contains("interpreter"), e.getMessage());
    }

    @Test
    void refusesZeroForks() {
        assertThrows(ConfigException.class, () -> parse("forks: 0\n"));
    }

    /**
     * A fixed iteration count makes rounds incomparable: a candidate twice as fast
     * finishes in half the wall time and is measured under different thermal
     * conditions — exactly what interleaving exists to eliminate.
     */
    @Test
    void refusesTheFixedIterationCountBenchtimeWithAnExplanation() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("benchtime: 100x\n"));
        assertTrue(e.getMessage().contains("thermal"), e.getMessage());
    }

    @Test
    void refusesOutOfRangePercentages() {
        assertThrows(ConfigException.class, () -> parse("max_regress_pct: -1\n"));
        assertThrows(ConfigException.class, () -> parse("min_effect_pct: -0.5\n"));
        assertThrows(ConfigException.class, () -> parse("min_effect_pct: 100\n"));
    }

    @Test
    void refusesAnEmptyOrBlankScope() {
        assertThrows(ConfigException.class, () -> parse("scope: []\n"));
        assertThrows(ConfigException.class, () -> parse("scope:\n  - '  '\n"));
    }

    @Test
    void refusesAnUnknownBuildTool() {
        assertThrows(ConfigException.class, () -> parse("build_tool: bazel\n"));
    }

    @Test
    void refusesAnUnparseableDuration() {
        assertThrows(ConfigException.class, () -> parse("timeout: soon\n"));
        assertThrows(ConfigException.class, () -> parse("benchtime: 1\n"));
    }

    /**
     * What init writes must be what the loader reads back, or the very first
     * baseline of a fresh repository fails on a file nobody has touched.
     */
    @Test
    void rendersConfigThatLoadsBackIdentically() {
        Config original = Config.defaults();
        original.benchmarks = List.of("com.example.A.run", "com.example.B.run");
        original.jvmArgs = List.of("-Xmx2g");
        Config reloaded = parse(ConfigRenderer.render(original));
        assertEquals(original.benchmarks, reloaded.benchmarks);
        assertEquals(original.scope, reloaded.scope);
        assertEquals(original.count, reloaded.count);
        assertEquals(original.forks, reloaded.forks);
        assertEquals(original.warmupIterations, reloaded.warmupIterations);
        assertEquals(original.measurementIterations, reloaded.measurementIterations);
        assertEquals(original.benchtime, reloaded.benchtime);
        assertEquals(original.jvmArgs, reloaded.jvmArgs);
        assertEquals(original.maxRegressPct, reloaded.maxRegressPct);
        assertEquals(original.minEffectPct, reloaded.minEffectPct);
        assertEquals(original.timeout, reloaded.timeout);
        assertEquals(original.unfreeze, reloaded.unfreeze);
        assertEquals(original.buildTool, reloaded.buildTool);
    }

    @Test
    void renderedConfigExplainsEveryKnob() {
        String rendered = ConfigRenderer.render(Config.defaults());
        for (String key : new String[]{"benchmarks", "scope", "count", "forks", "warmup_iterations",
                "measurement_iterations", "benchtime", "jvm_args", "max_regress_pct", "min_effect_pct",
                "timeout", "unfreeze", "build_tool"}) {
            assertTrue(rendered.contains(key + ":"), "missing " + key);
        }
        assertTrue(rendered.contains("# autor3search-java run configuration."));
    }
}
