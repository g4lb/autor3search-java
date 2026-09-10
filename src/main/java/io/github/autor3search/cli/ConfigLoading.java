package io.github.autor3search.cli;

import io.github.autor3search.config.Config;
import io.github.autor3search.config.ConfigException;
import io.github.autor3search.config.ConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a repository's config and reports a usable error when it cannot.
 *
 * <p>It distinguishes "no config yet" from "a config exists but is invalid".
 * {@code init} refuses to overwrite an existing config without {@code -force}, so
 * telling an agent to run {@code init} when one is already there just hands it a
 * second error and no path forward. Only a genuinely absent config should point
 * at {@code init}; an existing-but-invalid one needs the named field corrected in
 * place.
 */
public final class ConfigLoading {
    private ConfigLoading() {}

    /** A config that could not be loaded, already phrased for the human. */
    public static final class LoadException extends Exception {
        public LoadException(String message) {
            super(message);
        }
    }

    public static Config load(String cmdName, Path root) throws LoadException {
        Path path = root.resolve(Config.PATH);
        if (!Files.exists(path)) {
            throw new LoadException("no config at " + path + "\nrun `autor3search-java init` first.");
        }
        try {
            return ConfigLoader.load(path);
        } catch (ConfigException e) {
            throw new LoadException(e.getMessage() + "\n" + path + " exists but is invalid; correct the named"
                    + " field and try again (init will refuse to regenerate it without -force).");
        } catch (IOException e) {
            throw new LoadException("read " + path + ": " + e.getMessage());
        }
    }
}
