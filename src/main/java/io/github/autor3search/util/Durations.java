package io.github.autor3search.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Go-style duration strings ("1s", "200ms", "15m", "1h30m").
 *
 * <p>Not {@link Duration#parse}, which wants ISO-8601 ("PT15M"). The
 * configuration file is meant to be hand-edited, and every benchmarking tool a
 * Java developer already uses — JMH's own {@code -r} and {@code -w} flags
 * included — spells a duration this way. Accepting {@code PT15M} and rejecting
 * {@code 15m} would be a footgun in the one file humans own.
 */
public final class Durations {
    private Durations() {}

    private static final Pattern UNIT = Pattern.compile("([0-9]*\\.?[0-9]+)(ns|us|µs|ms|s|m|h)");
    private static final Pattern WHOLE = Pattern.compile("^(?:[0-9]*\\.?[0-9]+(?:ns|us|µs|ms|s|m|h))+$");

    /**
     * Matches a fixed iteration COUNT where a duration is required, e.g. "100x".
     *
     * <p>Not a form JMH accepts, and not one this tool accepts either — it is
     * recognised only so that writing it earns an explanation instead of a bare
     * parse error. Other benchmarking tools spell a fixed count this way, and
     * someone reaching for it here has made a reasonable assumption rather than a
     * typo. Why a fixed count is refused outright is in the message
     * {@code Config.validate} raises.
     */
    private static final Pattern COUNT_FORM = Pattern.compile("^[0-9]+x$");

    /** Reports whether s asks for a fixed iteration count rather than a duration. */
    public static boolean isCountForm(String s) {
        return s != null && COUNT_FORM.matcher(s.trim()).matches();
    }

    /**
     * Parses s, or throws {@link IllegalArgumentException} naming what was wrong.
     * A bare number is rejected rather than assumed to be seconds: guessing the
     * unit of a measurement budget is exactly the kind of silent difference that
     * makes two runs incomparable.
     */
    public static Duration parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("duration must not be empty");
        }
        String t = s.trim();
        if (!WHOLE.matcher(t).matches()) {
            throw new IllegalArgumentException(
                    "\"" + s + "\" is not a duration: expected a number followed by ns, us, ms, s, m or h "
                            + "(e.g. 1s, 200ms, 15m)");
        }
        Matcher m = UNIT.matcher(t);
        double nanos = 0;
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            nanos += value * unitNanos(m.group(2));
        }
        return Duration.ofNanos((long) nanos);
    }

    private static double unitNanos(String unit) {
        return switch (unit) {
            case "ns" -> 1d;
            case "us", "µs" -> 1_000d;
            case "ms" -> 1_000_000d;
            case "s" -> 1_000_000_000d;
            case "m" -> 60d * 1_000_000_000d;
            case "h" -> 3600d * 1_000_000_000d;
            default -> throw new IllegalArgumentException("unknown unit " + unit);
        };
    }

    /** Renders d the way {@link #parse} reads it, for human-facing output. */
    public static String format(Duration d) {
        long ms = d.toMillis();
        if (ms % 3_600_000 == 0 && ms != 0) return (ms / 3_600_000) + "h";
        if (ms % 60_000 == 0 && ms != 0) return (ms / 60_000) + "m";
        if (ms % 1000 == 0) return (ms / 1000) + "s";
        return ms + "ms";
    }
}
