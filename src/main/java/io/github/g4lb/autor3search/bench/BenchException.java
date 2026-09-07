package io.github.g4lb.autor3search.bench;

/** A measurement that cannot be parsed, compared or scored. */
public class BenchException extends RuntimeException {
    public BenchException(String message) {
        super(message);
    }
}
