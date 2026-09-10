package io.github.autor3search.results;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads and appends the experiment log: the human's morning read. */
public final class Results {
    private Results() {}

    /** The log's location, relative to the repository root. */
    public static final String PATH = "results.tsv";

    /** The first line of every log file. */
    public static final String HEADER = "commit\tscore\tbest_bench_delta\tbytes_delta\tstatus\tdescription";

    /**
     * The longest description written verbatim.
     *
     * <p>{@code -desc} has no length cap of its own, and an agent pasting
     * something large — a stack trace, a diff — would otherwise produce a row long
     * enough to be unreadable and, worse, to make a hand-repair of the file
     * necessary before any future {@code report} could run. 256 characters is
     * generous for a one-line experiment summary.
     */
    static final int MAX_DESCRIPTION = 256;

    /** Appends one row, creating the file with a header when needed. */
    public static void append(Path path, Row row) throws IOException {
        boolean isNew = !Files.exists(path);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (isNew) {
                w.write(HEADER);
                w.write('\n');
            }
            w.write(String.format(Locale.ROOT, "%s\t%.4f\t%.2f\t%.2f\t%s\t%s%n",
                    clean(row.commit()), row.score(), row.bestBenchDelta(), row.bytesDelta(),
                    clean(row.status()), truncate(clean(row.description()))));
        }
    }

    /**
     * Reads every row. A missing file is an empty log, not an error.
     *
     * <p>Strict by design: a malformed line fails the whole load rather than being
     * skipped. Sanitising on write makes a malformed row nearly impossible, so one
     * is a real signal — a torn write or a hand edit — and the error names the file
     * and line so it can be fixed. Silently dropping rows would let a corrupted log
     * masquerade as a short one, which is worse for a file that is the sole record
     * of an overnight run.
     */
    public static List<Row> load(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        List<Row> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isEmpty() || line.equals(HEADER)) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 6) {
                    throw new IOException(path + ":" + lineNo + ": got " + parts.length + " fields, want 6");
                }
                rows.add(new Row(parts[0],
                        number(path, lineNo, "score", parts[1]),
                        number(path, lineNo, "best_bench_delta", parts[2]),
                        number(path, lineNo, "bytes_delta", parts[3]),
                        parts[4], parts[5]));
            }
        }
        return rows;
    }

    /** Writes a fresh, header-only log, discarding whatever was there. */
    public static void reset(Path path) throws IOException {
        Files.writeString(path, HEADER + "\n", StandardCharsets.UTF_8);
    }

    private static double number(Path path, int lineNo, String field, String text) throws IOException {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IOException(path + ":" + lineNo + ": " + field + ": \"" + text + "\" is not a number");
        }
    }

    /** Makes a field safe for a tab-separated, single-line record. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * Caps s at {@link #MAX_DESCRIPTION} code points, appending "..." when it had
     * to cut. Counting code points rather than chars means a supplementary
     * character is never split into an unpaired surrogate.
     */
    static String truncate(String s) {
        if (s.codePointCount(0, s.length()) <= MAX_DESCRIPTION) return s;
        int end = s.offsetByCodePoints(0, MAX_DESCRIPTION);
        return s.substring(0, end) + "...";
    }
}
