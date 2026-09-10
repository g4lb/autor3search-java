package io.github.autor3search.results;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultsTest {

    private static Row row(String desc) {
        return new Row("abc1234", 0.9123, -8.77, -12.5, "keep", desc);
    }

    @Test
    void writesAHeaderOnceThenAppends(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, row("first"));
        Results.append(log, row("second"));
        List<String> lines = Files.readAllLines(log);
        assertEquals(3, lines.size());
        assertEquals(Results.HEADER, lines.get(0));
        assertEquals(2, Results.load(log).size());
    }

    @Test
    void roundTripsEveryField(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, row("presize-map"));
        Row back = Results.load(log).get(0);
        assertEquals("abc1234", back.commit());
        assertEquals(0.9123, back.score(), 1e-9);
        assertEquals(-8.77, back.bestBenchDelta(), 1e-9);
        assertEquals(-12.5, back.bytesDelta(), 1e-9);
        assertEquals("keep", back.status());
        assertEquals("presize-map", back.description());
    }

    @Test
    void aMissingFileIsAnEmptyLogNotAnError(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), Results.load(dir.resolve("nothing.tsv")));
    }

    /**
     * A description carrying tabs or newlines would split one experiment across
     * several rows, and every row after it would be misparsed.
     */
    @Test
    void flattensTabsAndNewlinesInADescription(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, new Row("abc", 1, 0, 0, "fail", "line one\nline\ttwo\r\n"));
        assertEquals(1, Results.load(log).size());
        assertEquals("line one line two", Results.load(log).get(0).description());
    }

    /**
     * An agent pasting a stack trace into -desc must not be able to produce a row
     * that makes the file unreadable for every later report.
     */
    @Test
    void capsAnOverlongDescription(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, new Row("abc", 1, 0, 0, "fail", "x".repeat(5000)));
        String desc = Results.load(log).get(0).description();
        assertEquals(Results.MAX_DESCRIPTION + 3, desc.length());
        assertTrue(desc.endsWith("..."));
    }

    /** Truncating by code point, so a supplementary character is never split in half. */
    @Test
    void truncationNeverSplitsACodePoint() {
        String emoji = "🚀".repeat(400);
        String cut = Results.truncate(emoji);
        assertEquals(-1, cut.indexOf('�'));
        assertTrue(Character.isHighSurrogate(cut.charAt(0)));
        assertTrue(cut.endsWith("..."));
    }

    /**
     * Strict by design: a malformed line is a torn write or a hand edit, and
     * silently dropping it would let a corrupted log masquerade as a short one.
     */
    @Test
    void aMalformedLineFailsTheLoadAndNamesTheLine(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Files.writeString(log, Results.HEADER + "\nabc\t0.9\t-1\t-1\tkeep\tok\nbroken row\n",
                StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> Results.load(log));
        assertTrue(e.getMessage().contains(":3:"), e.getMessage());
        assertTrue(e.getMessage().contains("want 6"), e.getMessage());
    }

    @Test
    void aNonNumericColumnFailsTheLoad(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Files.writeString(log, Results.HEADER + "\nabc\tfast\t-1\t-1\tkeep\tok\n", StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> Results.load(log));
        assertTrue(e.getMessage().contains("score"), e.getMessage());
    }

    @Test
    void resetLeavesOnlyTheHeader(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, row("first"));
        Results.reset(log);
        assertEquals(List.of(), Results.load(log));
        assertEquals(List.of(Results.HEADER), Files.readAllLines(log));
    }

    @Test
    void headerAndRowHaveTheSameNumberOfColumns(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("results.tsv");
        Results.append(log, row("x"));
        List<String> lines = Files.readAllLines(log);
        assertEquals(lines.get(0).split("\t", -1).length, lines.get(1).split("\t", -1).length);
    }
}
