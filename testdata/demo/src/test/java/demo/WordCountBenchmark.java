package demo;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;

/** The metric. Frozen at baseline and restored before every evaluation. */
@State(Scope.Benchmark)
public class WordCountBenchmark {

    private static final String INPUT = "The Quick, Brown Fox! jumps over 2 lazy dogs. ".repeat(200);

    @Benchmark
    public void count(Blackhole bh) {
        Map<String, Integer> counts = WordCount.count(INPUT);
        if (counts.isEmpty()) {
            throw new IllegalStateException("empty result");
        }
        bh.consume(counts);
    }
}
