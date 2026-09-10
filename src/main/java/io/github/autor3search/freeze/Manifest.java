package io.github.autor3search.freeze;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Maps repository-relative paths to the SHA-256 they had at baseline time. */
public final class Manifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Map<String, String> files = new TreeMap<>();

    /** The frozen paths, sorted, so every message and every walk is deterministic. */
    public List<String> paths() {
        return new ArrayList<>(new TreeMap<>(files).keySet());
    }

    public void save(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
            w.write('\n');
        }
    }

    public static Manifest load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Manifest m = GSON.fromJson(r, Manifest.class);
            if (m == null) m = new Manifest();
            if (m.files == null) m.files = new TreeMap<>();
            return m;
        } catch (JsonSyntaxException e) {
            throw new IOException("parse manifest " + path + ": " + e.getMessage(), e);
        }
    }
}
