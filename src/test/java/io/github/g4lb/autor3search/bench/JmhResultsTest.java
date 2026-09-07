package io.github.g4lb.autor3search.bench;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmhResultsTest {

    private static BenchSet parse(String json) {
        return JmhResults.parse(new StringReader(json));
    }

    @Test
    void readsTheScoreAndTheAllocationHint() {
        BenchSet s = parse("""
                [{
                  "benchmark": "com.example.Parse.run",
                  "primaryMetric": {"score": 1234.5, "scoreUnit": "ns/op"},
                  "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 4096.0, "scoreUnit": "B/op"}}
                }]
                """);
        assertEquals(List.of("com.example.Parse.run"), s.names());
        assertEquals(1234.5, s.values("com.example.Parse.run", BenchSet.UNIT_TIME)[0], 1e-9);
        assertEquals(4096.0, s.values("com.example.Parse.run", BenchSet.UNIT_BYTES)[0], 1e-9);
    }

    /**
     * JMH has spelled the profiler metric both ways across versions. Matching one
     * spelling would drop the allocation hint on the other, with nothing in the
     * output explaining its absence.
     */
    @Test
    void acceptsBothSpellingsOfTheAllocationMetric() {
        for (String key : new String[]{"gc.alloc.rate.norm", "·gc.alloc.rate.norm"}) {
            BenchSet s = parse("""
                    [{
                      "benchmark": "a.B.run",
                      "primaryMetric": {"score": 1, "scoreUnit": "ns/op"},
                      "secondaryMetrics": {"%s": {"score": 64.0, "scoreUnit": "B/op"}}
                    }]
                    """.formatted(key));
            assertEquals(64.0, s.values("a.B.run", BenchSet.UNIT_BYTES)[0], 1e-9, key);
        }
    }

    @Test
    void convertsOtherTimeUnitsToNanoseconds() {
        assertEquals(1_000.0, first(parse(one("1", "us/op"))), 1e-9);
        assertEquals(1_000_000.0, first(parse(one("1", "ms/op"))), 1e-9);
        assertEquals(1_000_000_000.0, first(parse(one("1", "s/op"))), 1e-9);
    }

    /**
     * Higher-is-better and lower-is-better cannot share one scoring rule: every
     * ratio, the regression guard and the sign of every reported percentage assume
     * lower is better, so silently inverting a throughput benchmark would make its
     * regressions read as improvements.
     */
    @Test
    void refusesAThroughputUnitRatherThanInvertingIt() {
        BenchException e = assertThrows(BenchException.class, () -> parse(one("1", "ops/s")));
        assertTrue(e.getMessage().contains("lower-is-better"), e.getMessage());
    }

    /**
     * JMH emits one object per @Param combination, all under the same benchmark
     * name. Without a distinguishing suffix their observations would pool into one
     * series and their real differences would be read as noise.
     */
    @Test
    void parameterCombinationsBecomeSeparateSeriesUnderOneBaseName() {
        BenchSet s = parse("""
                [
                  {"benchmark": "a.B.run", "params": {"size": "big", "mode": "x"},
                   "primaryMetric": {"score": 10, "scoreUnit": "ns/op"}},
                  {"benchmark": "a.B.run", "params": {"size": "small", "mode": "x"},
                   "primaryMetric": {"score": 20, "scoreUnit": "ns/op"}}
                ]
                """);
        assertEquals(List.of("a.B.run/mode=x,size=big", "a.B.run/mode=x,size=small"), s.names());
        // Both still select on the base name a config would name.
        assertEquals(2, s.selectByBase(java.util.Set.of("a.B.run")).names().size());
    }

    /** A NaN allocation reading would poison the median and every comparison from it. */
    @Test
    void dropsANaNAllocationReading() {
        BenchSet s = parse("""
                [{
                  "benchmark": "a.B.run",
                  "primaryMetric": {"score": 1, "scoreUnit": "ns/op"},
                  "secondaryMetrics": {"gc.alloc.rate.norm": {"score": NaN, "scoreUnit": "B/op"}}
                }]
                """);
        assertTrue(s.has("a.B.run", BenchSet.UNIT_TIME));
        assertFalse(s.has("a.B.run", BenchSet.UNIT_BYTES));
    }

    @Test
    void aRunWithoutTheGcProfilerStillYieldsTimings() {
        BenchSet s = parse(one("42", "ns/op"));
        assertTrue(s.has("a.B.run", BenchSet.UNIT_TIME));
        assertFalse(s.has("a.B.run", BenchSet.UNIT_BYTES));
    }

    @Test
    void refusesADocumentThatIsNotAnArrayOfResults() {
        assertThrows(BenchException.class, () -> parse("{}"));
        assertThrows(BenchException.class, () -> parse(""));
        assertThrows(BenchException.class, () -> parse("[ not json"));
    }

    @Test
    void anEmptyArrayIsAnEmptySet() {
        assertTrue(parse("[]").isEmpty());
    }

    private static String one(String score, String unit) {
        return "[{\"benchmark\": \"a.B.run\", \"primaryMetric\": {\"score\": " + score
                + ", \"scoreUnit\": \"" + unit + "\"}}]";
    }

    private static double first(BenchSet s) {
        return s.values("a.B.run", BenchSet.UNIT_TIME)[0];
    }
}
