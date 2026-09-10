package io.github.autor3search.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads JMH's own JSON result format into a {@link BenchSet}.
 *
 * <p>JSON rather than JMH's console output, which is formatted for humans and
 * whose columns move between versions. The JSON carries the score, its unit, and
 * the parameters that distinguish one variant of a benchmark from another — all
 * three of which the scorer needs and none of which can be recovered reliably
 * from a text table.
 */
public final class JmhResults {
    private JmhResults() {}

    /**
     * JMH's normalised allocation-rate metric, in bytes per operation. It appears
     * only when the run asked for {@code -prof gc}.
     *
     * <p>Two spellings, because JMH itself has used both: the interpunct is its
     * naming convention for a profiler-contributed metric, and it appears in the
     * console table always but in the JSON only in some versions. Matching one
     * spelling would silently drop the allocation hint on the other, which is a
     * failure mode nothing in the output would explain.
     */
    private static final List<String> GC_ALLOC_NORM_KEYS =
            List.of("gc.alloc.rate.norm", "\u00b7gc.alloc.rate.norm");

    /** Parses a JMH result document. */
    public static BenchSet parse(Reader reader) {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (JsonSyntaxException e) {
            throw new BenchException("parse JMH results: " + e.getMessage());
        }
        if (root == null || root.isJsonNull()) {
            throw new BenchException("parse JMH results: the result document was empty");
        }
        if (!root.isJsonArray()) {
            throw new BenchException("parse JMH results: expected a JSON array of benchmark results");
        }
        BenchSet set = new BenchSet();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            record(set, e.getAsJsonObject());
        }
        return set;
    }

    private static void record(BenchSet set, JsonObject o) {
        if (!o.has("benchmark")) return;
        String base = o.get("benchmark").getAsString();
        String name = base + paramSuffix(o);

        JsonObject primary = o.getAsJsonObject("primaryMetric");
        if (primary != null && primary.has("score")) {
            String unit = primary.has("scoreUnit") ? primary.get("scoreUnit").getAsString() : "";
            double ns = toNanosPerOp(name, primary.get("score").getAsDouble(), unit);
            set.record(name, base, BenchSet.UNIT_TIME, ns);
        }

        JsonObject secondary = o.getAsJsonObject("secondaryMetrics");
        if (secondary == null) return;
        for (String key : GC_ALLOC_NORM_KEYS) {
            if (!secondary.has(key)) continue;
            JsonObject gc = secondary.getAsJsonObject(key);
            if (!gc.has("score")) continue;
            double bytes = gc.get("score").getAsDouble();
            // JMH reports NaN for a benchmark the GC profiler saw no allocation in.
            // That is a real "zero", but recording NaN would poison the median and
            // every comparison drawn from it.
            if (!Double.isNaN(bytes)) {
                set.record(name, base, BenchSet.UNIT_BYTES, bytes);
            }
            return;
        }
    }

    /**
     * The parameter suffix that distinguishes one {@code @Param} combination from
     * another, sorted so the same combination always spells the same name. JMH
     * emits one result object per combination, all under the same
     * {@code benchmark} value, so without this every combination's observations
     * would be pooled into one series and their differences read as noise.
     */
    private static String paramSuffix(JsonObject o) {
        JsonObject params = o.getAsJsonObject("params");
        if (params == null || params.size() == 0) return "";
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : params.entrySet()) {
            sorted.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
        }
        List<String> parts = new ArrayList<>();
        sorted.forEach((k, v) -> parts.add(k + "=" + v));
        return "/" + String.join(",", parts);
    }

    /**
     * Converts a JMH primary score to nanoseconds per operation.
     *
     * <p>A throughput unit is REFUSED rather than inverted. Higher-is-better and
     * lower-is-better cannot share one scoring rule: every ratio, every
     * regression guard and the sign of every percentage in the report assumes
     * lower is better, and silently inverting one benchmark would make its
     * regressions read as improvements. The harness forces {@code -bm avgt -tu ns}
     * on every invocation, so reaching here means something overrode that.
     */
    private static double toNanosPerOp(String name, double score, String unit) {
        String u = unit.trim();
        return switch (u) {
            case "ns/op" -> score;
            case "us/op", "µs/op" -> score * 1e3;
            case "ms/op" -> score * 1e6;
            case "s/op" -> score * 1e9;
            case "m/op" -> score * 6e10;
            default -> throw new BenchException(name + " reported its score in \"" + unit
                    + "\", which is not a per-operation time. autor3search-java scores lower-is-better"
                    + " time only, and runs JMH with -bm avgt -tu ns to guarantee it; a throughput or"
                    + " single-shot unit here means something overrode that. Remove any @BenchmarkMode"
                    + " that a JMH option cannot override, or drop this benchmark from the declared set.");
        };
    }
}
