package io.github.autor3search.build;

import java.io.IOException;

/** A build tool that could not be detected, driven, or asked for a classpath. */
public class BuildToolException extends IOException {
    public BuildToolException(String message) {
        super(message);
    }
}
