package io.github.g4lb.autor3search.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A parsed collection of benchmark measurements: for each benchmark, for each
 * unit, every observation in the order it was made.
 */
public final class BenchSet {

    /** Measured units, spelled as {@link JmhResults} normalises them. */
    public static final String UNIT_TIME = "ns/op";
    public static final String UNIT_BYTES = "B/op";

    /** Everything measured for a single benchmark. */
    public static final class Series {
        /** The full name as reported, including parameters, e.g. "com.example.Parse.run/size=big". */
        public final String name;
        /** The name without parameters, which is what {@code benchmarks:} selects on. */
        public final String base;
        final Map<String, List<Double>> metrics = new LinkedHashMap<>();

        Series(String name, String base) {
            this.name = name;
            this.base = base;
        }
    }

    private final Map<String, Series> series = new TreeMap<>();

    public void record(String name, String base, String unit, double value) {
        Series s = series.computeIfAbsent(name, n -> new Series(n, base));
        s.metrics.computeIfAbsent(unit, u -> new ArrayList<>()).add(value);
    }

    /** Every benchmark name, sorted. */
    public List<String> names() {
        return new ArrayList<>(series.keySet());
    }

    public boolean isEmpty() {
        return series.isEmpty();
    }

    /** Whether any observation of one benchmark and unit is present. */
    public boolean has(String name, String unit) {
        Series s = series.get(name);
        return s != null && s.metrics.containsKey(unit);
    }

    /** A defensive copy of the observations for one benchmark and unit, or null. */
    public double[] values(String name, String unit) {
        Series s = series.get(name);
        if (s == null) return null;
        List<Double> v = s.metrics.get(unit);
        if (v == null) return null;
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    /** Appends every observation in other, preserving order. */
    public void addAll(BenchSet other) {
        for (Series s : other.series.values()) {
            for (Map.Entry<String, List<Double>> e : s.metrics.entrySet()) {
                for (double v : e.getValue()) {
                    record(s.name, s.base, e.getKey(), v);
                }
            }
        }
    }

    /**
     * The subset whose base names appear in {@code bases}; an empty or null
     * selection keeps everything. Base names let a configuration name
     * {@code com.example.Parse.run} and still match every parameterised variant
     * of it.
     */
    public BenchSet selectByBase(Set<String> bases) {
        BenchSet out = new BenchSet();
        for (Series s : series.values()) {
            if (bases != null && !bases.isEmpty() && !bases.contains(s.base)) continue;
            for (Map.Entry<String, List<Double>> e : s.metrics.entrySet()) {
                for (double v : e.getValue()) {
                    out.record(s.name, s.base, e.getKey(), v);
                }
            }
        }
        return out;
    }
}
