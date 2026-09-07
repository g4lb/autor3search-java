package io.github.g4lb.autor3search.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@link Config} from YAML, applying defaults for omitted fields.
 *
 * <p>Keys are mapped by hand rather than by bean reflection so that a typo is
 * reported instead of ignored. A knob file whose misspelled key silently does
 * nothing is worse than one that refuses to load: the run would proceed at the
 * default the human thought they had changed, and every verdict afterwards
 * would be scored by rules they did not choose.
 */
public final class ConfigLoader {
    private ConfigLoader() {}

    private static final Set<String> KNOWN = new LinkedHashSet<>(List.of(
            "benchmarks", "scope", "count", "forks", "warmup_iterations", "measurement_iterations",
            "benchtime", "jvm_args", "max_regress_pct", "min_effect_pct", "timeout", "unfreeze",
            "build_tool"));

    public static Config load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(r, path.toString());
        }
    }

    static Config parse(Reader reader, String origin) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException e) {
            throw new ConfigException("parse " + origin + ": " + e.getMessage());
        }
        Config cfg = Config.defaults();
        if (raw == null) {
            cfg.validate();
            return cfg;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigException("parse " + origin + ": expected a mapping of settings at the top level");
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!KNOWN.contains(key)) {
                throw new ConfigException("invalid " + origin + ": unknown setting \"" + key
                        + "\"; known settings are " + String.join(", ", KNOWN));
            }
            Object v = e.getValue();
            switch (key) {
                case "benchmarks" -> cfg.benchmarks = strings(key, v);
                case "scope" -> cfg.scope = strings(key, v);
                case "count" -> cfg.count = integer(key, v);
                case "forks" -> cfg.forks = integer(key, v);
                case "warmup_iterations" -> cfg.warmupIterations = integer(key, v);
                case "measurement_iterations" -> cfg.measurementIterations = integer(key, v);
                case "benchtime" -> cfg.benchtime = string(key, v);
                case "jvm_args" -> cfg.jvmArgs = strings(key, v);
                case "max_regress_pct" -> cfg.maxRegressPct = number(key, v);
                case "min_effect_pct" -> cfg.minEffectPct = number(key, v);
                case "timeout" -> cfg.timeout = string(key, v);
                case "unfreeze" -> cfg.unfreeze = strings(key, v);
                case "build_tool" -> cfg.buildTool = string(key, v);
                default -> throw new IllegalStateException("unreachable: " + key);
            }
        }
        try {
            cfg.validate();
        } catch (ConfigException ce) {
            throw new ConfigException("invalid " + origin + ": " + ce.getMessage());
        }
        return cfg;
    }

    private static List<String> strings(String key, Object v) {
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            throw new ConfigException(key + " must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String string(String key, Object v) {
        if (v == null) throw new ConfigException(key + " must not be empty");
        return String.valueOf(v);
    }

    private static int integer(String key, Object v) {
        if (v instanceof Number n) return n.intValue();
        throw new ConfigException(key + " must be a whole number, got \"" + v + "\"");
    }

    private static double number(String key, Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new ConfigException(key + " must be a number, got \"" + v + "\"");
    }
}
