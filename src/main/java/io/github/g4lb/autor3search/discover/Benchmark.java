package io.github.g4lb.autor3search.discover;

/**
 * One discovered JMH benchmark method.
 *
 * @param name   the fully qualified name JMH reports, e.g. "com.example.WordCountBenchmark.count"
 * @param file   the repository-relative source file declaring it
 * @param type   the declaring type's fully qualified name
 * @param method the method name
 */
public record Benchmark(String name, String file, String type, String method) {}
