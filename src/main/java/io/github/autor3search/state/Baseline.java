package io.github.autor3search.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference point(s) for a run.
 *
 * <p>Two distinct reference points are tracked here, deliberately kept apart:
 *
 * <ul>
 *   <li>{@link #commit} is the FROZEN anchor: the commit the run started from.
 *       It is recorded once by {@code baseline} and must NEVER change for the
 *       life of the run. The frozen snapshots are taken relative to it, and the
 *       scope gate diffs against it — using a fixed anchor there means the gate
 *       keeps re-validating the FULL accumulated diff against the human-approved
 *       starting point on every single evaluation, rather than trusting that
 *       anything already banked as a KEEP must have been in scope. An agent that
 *       could move this anchor could launder an out-of-scope edit into the
 *       "already accepted" state after one evaluation, invisible from then on.
 *   <li>{@link #measureCommit} is the ADVANCING measurement pointer: the commit
 *       the pinned baseline worktree is checked out to, and what every
 *       {@code eval} measures the candidate against. It starts equal to
 *       {@code commit} and is re-pointed to the candidate's own commit after
 *       every KEEP, so each evaluation answers "did THIS change help", not "is
 *       the tree better than when the run started" — the latter is what let a
 *       no-op experiment coast to KEEP on the strength of an earlier win.
 * </ul>
 *
 * <p>Collapsing these into one field would either freeze the measurement
 * baseline forever or let the scope gate's comparison point drift. Keep them
 * separate.
 */
public final class Baseline {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The human-chosen run identifier, e.g. "sep7". */
    public String tag;
    /** The run branch checked out when the baseline was recorded. */
    public String branch;
    /** The FROZEN anchor. Never changes after {@code baseline} records it. */
    public String commit;
    /** The ADVANCING measurement anchor. Moves to the candidate's commit after every KEEP. */
    public String measureCommit;
    /** When the baseline was recorded, in UTC, ISO-8601. */
    public String createdAt;
    /** The declared benchmark set measured at baseline. */
    public List<String> benchmarks = new ArrayList<>();
    /** The JMH selection regexp derived from {@link #benchmarks}. */
    public String pattern;
    /**
     * The SHA-256 of the in-repository config file at baseline time. The config
     * stays in the repository because humans own it and want it in version
     * control, so it is protected by integrity checking rather than by
     * relocation: an agent that raises {@code max_regress_pct} or shrinks the
     * benchmark set is caught at evaluation time when the hash no longer matches.
     */
    public String configSha256;
    /** "maven" or "gradle", resolved once at baseline so no later command re-infers it. */
    public String buildTool;
    /**
     * The repository-relative module that declares the benchmarks, "." for a
     * single-module build. Recorded rather than re-inferred, so a benchmark file
     * moved mid-run fails a gate instead of silently re-pointing the measurement
     * at a different module's classpath.
     */
    public String moduleDir;

    public void save(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
            w.write('\n');
        }
    }

    public static Baseline load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Baseline b = GSON.fromJson(r, Baseline.class);
            if (b == null) {
                throw new IOException("baseline " + path + " is empty");
            }
            if (b.benchmarks == null) b.benchmarks = new ArrayList<>();
            if (b.measureCommit == null || b.measureCommit.isBlank()) b.measureCommit = b.commit;
            if (b.moduleDir == null || b.moduleDir.isBlank()) b.moduleDir = ".";
            return b;
        } catch (NoSuchFileException e) {
            throw new IOException("no baseline at " + path
                    + ": run 'autor3search-java baseline -tag <tag>' first");
        } catch (JsonSyntaxException e) {
            throw new IOException("parse baseline " + path + ": " + e.getMessage(), e);
        }
    }
}
