package io.github.autor3search.cli;

import io.github.autor3search.git.Git;
import io.github.autor3search.results.Results;
import io.github.autor3search.results.Row;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Summarizes the experiment log: the human's morning read. */
public final class ReportCommand {
    private ReportCommand() {}

    public static int run(String[] args) {
        Flags f = new Flags("report");
        f.string("C", ".", "repository root (or a directory inside it)");
        if (!f.parse(args)) return ExitCodes.USAGE;

        try {
            Path root = Git.root(Path.of(f.get("C")));
            printSummary(Results.load(root.resolve(Results.PATH)));
            return ExitCodes.OK;
        } catch (IOException e) {
            System.err.println("autor3search-java report: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    static void printSummary(List<Row> rows) {
        if (rows.isEmpty()) {
            System.out.println("autor3search-java report: no experiments recorded");
            return;
        }

        int kept = 0;
        int discarded = 0;
        int failed = 0;
        int crashed = 0;
        for (Row r : rows) {
            switch (r.status()) {
                case "keep" -> kept++;
                case "discard" -> discarded++;
                case "fail" -> failed++;
                case "crash" -> crashed++;
                default -> { /* aborted rows are never written; anything else is a hand edit */ }
            }
        }

        // Cumulative improvement is the PRODUCT of every kept score, not the latest
        // kept score alone.
        //
        // This depends entirely on the measurement baseline advancing after every
        // KEEP: each evaluation measures the candidate against the immediately
        // preceding ACCEPTED state, not against the run's original commit. So every
        // kept row's score already reflects ONLY that experiment's own incremental
        // contribution — it is not cumulative on its own — and the run-level speedup
        // has to be composed by multiplying them, the way compounding percentage
        // changes works.
        //
        // If the measurement baseline ever stopped advancing, this reasoning would
        // invert: each kept score would already be cumulative and multiplying them
        // would double-count. The two must always change together.
        double cumulative = 1.0;
        List<Row> keptRows = new ArrayList<>();
        for (Row r : rows) {
            if (r.status().equals("keep")) {
                cumulative *= r.score();
                keptRows.add(r);
            }
        }
        keptRows.sort((a, b) -> Double.compare(a.bestBenchDelta(), b.bestBenchDelta()));

        System.out.println("autor3search-java report: " + rows.size() + " total experiments");
        System.out.println("  kept: " + kept);
        System.out.println("  discarded: " + discarded);
        System.out.println("  failed: " + failed);
        System.out.println("  crashed: " + crashed);
        if (keptRows.isEmpty()) {
            System.out.println("\ncumulative speedup: no experiments kept");
        } else {
            System.out.printf(Locale.ROOT, "%ncumulative speedup: %.1f%%%n", (1.0 - cumulative) * 100.0);
            System.out.println("\nlargest wins:");
            for (int i = 0; i < Math.min(5, keptRows.size()); i++) {
                Row w = keptRows.get(i);
                System.out.printf(Locale.ROOT, "  %d. %s (time: %.1f%%, bytes: %.1f%%)%n",
                        i + 1, w.description(), w.bestBenchDelta(), w.bytesDelta());
            }
        }
    }
}
