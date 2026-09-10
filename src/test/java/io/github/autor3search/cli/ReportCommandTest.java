package io.github.autor3search.cli;

import io.github.autor3search.results.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCommandTest {

    private static String summarize(List<Row> rows) {
        return Capture.run(() -> {
            ReportCommand.printSummary(rows);
            return 0;
        }).out();
    }

    private static Row row(String status, double score, double best, String desc) {
        return new Row("abc1234", score, best, -10, status, desc);
    }

    @Test
    void anEmptyLogSaysSoRatherThanPrintingZeroes() {
        assertTrue(summarize(List.of()).contains("no experiments recorded"));
    }

    @Test
    void countsEveryStatus() {
        String out = summarize(List.of(
                row("keep", 0.9, -10, "a"), row("discard", 1.0, 0, "b"),
                row("fail", 0, 0, "c"), row("crash", 0, 0, "d")));
        assertTrue(out.contains("4 total experiments"), out);
        assertTrue(out.contains("kept: 1"), out);
        assertTrue(out.contains("discarded: 1"), out);
        assertTrue(out.contains("failed: 1"), out);
        assertTrue(out.contains("crashed: 1"), out);
    }

    /**
     * The measurement baseline advances after every KEEP, so each kept score is
     * only that experiment's own incremental contribution. The run's total is the
     * PRODUCT of them, the way compounding percentage changes works — taking the
     * latest score alone would under-report a long honest run.
     */
    @Test
    void cumulativeSpeedupIsTheProductOfEveryKeptScore() {
        // 0.9 * 0.8 = 0.72, a 28.0% cumulative speedup.
        String out = summarize(List.of(
                row("keep", 0.9, -10, "first"),
                row("discard", 1.0, 0, "nope"),
                row("keep", 0.8, -20, "second")));
        assertTrue(out.contains("cumulative speedup: 28.0%"), out);
    }

    @Test
    void saysSoWhenNothingWasKept() {
        String out = summarize(List.of(row("discard", 1.0, 0, "a")));
        assertTrue(out.contains("cumulative speedup: no experiments kept"), out);
    }

    @Test
    void ranksTheLargestWinsFirstAndShowsAtMostFive() {
        String out = summarize(List.of(
                row("keep", 0.99, -1, "smallest-win"),
                row("keep", 0.5, -50, "huge"),
                row("keep", 0.9, -10, "medium"),
                row("keep", 0.95, -5, "little"),
                row("keep", 0.97, -3, "tiny"),
                row("keep", 0.98, -2, "sixth")));
        assertTrue(out.contains("  1. huge"), out);
        assertTrue(out.contains("  2. medium"), out);
        assertTrue(out.indexOf("huge") < out.indexOf("medium"), out);
        // Six kept experiments, five listed: the smallest win drops off the end.
        assertTrue(!out.contains("smallest-win"), out);
    }
}
