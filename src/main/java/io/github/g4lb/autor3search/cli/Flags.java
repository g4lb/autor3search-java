package io.github.g4lb.autor3search.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small flag parser.
 *
 * <p>Accepts {@code -name value}, {@code -name=value} and, for booleans, a bare
 * {@code -name}, treating a leading {@code --} as a synonym for {@code -} so that
 * both spellings work wherever a flag appears.
 *
 * <p>Hand-written rather than taken from a CLI library, for two reasons. The
 * command surface is nine subcommands with at most five flags each, so a
 * dependency would be most of a megabyte to parse a few dozen strings. And the
 * flags are part of a contract: {@code program.md} tells an agent to run
 * {@code eval --json -desc "..."}, and that has to keep meaning exactly what it
 * says across versions, which is easier to guarantee for code that lives here.
 */
public final class Flags {

    private record Spec(String name, String def, boolean isBool, String help) {}

    private final String command;
    private final Map<String, Spec> specs = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();
    private final PrintStream err;

    public Flags(String command) {
        this(command, System.err);
    }

    Flags(String command, PrintStream err) {
        this.command = command;
        this.err = err;
    }

    public Flags string(String name, String def, String help) {
        specs.put(name, new Spec(name, def, false, help));
        values.put(name, def);
        return this;
    }

    public Flags bool(String name, boolean def, String help) {
        specs.put(name, new Spec(name, String.valueOf(def), true, help));
        values.put(name, String.valueOf(def));
        return this;
    }

    /** Parses args, printing the reason and the usage to stderr on failure. */
    public boolean parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-") || arg.equals("-")) {
                positional.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                for (int j = i + 1; j < args.length; j++) positional.add(args[j]);
                break;
            }
            String body = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
            String name = body;
            String inline = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                name = body.substring(0, eq);
                inline = body.substring(eq + 1);
            }
            Spec spec = specs.get(name);
            if (spec == null) {
                err.println("autor3search-java " + command + ": unknown flag -" + name);
                usage();
                return false;
            }
            if (inline != null) {
                values.put(name, inline);
                continue;
            }
            if (spec.isBool()) {
                values.put(name, "true");
                continue;
            }
            if (i + 1 >= args.length) {
                err.println("autor3search-java " + command + ": flag -" + name + " needs a value");
                usage();
                return false;
            }
            values.put(name, args[++i]);
        }
        return true;
    }

    public String get(String name) {
        return values.get(name);
    }

    public boolean flag(String name) {
        return Boolean.parseBoolean(values.get(name));
    }

    /** Positional arguments, in order. */
    public List<String> args() {
        return List.copyOf(positional);
    }

    public void usage() {
        err.println("usage: autor3search-java " + command + " [flags]");
        for (Spec s : specs.values()) {
            String value = s.isBool() ? "" : " <value>";
            err.printf("  -%-8s%-9s %s%s%n", s.name(), value, s.help(),
                    s.isBool() || s.def() == null || s.def().isEmpty() ? "" : " (default " + s.def() + ")");
        }
    }
}
