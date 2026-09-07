package io.github.g4lb.autor3search.config;

/** A configuration file that exists but cannot be used as written. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
