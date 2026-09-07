package io.github.g4lb.autor3search.state;

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

class BaselineTest {

    private static Baseline sample() {
        Baseline b = new Baseline();
        b.tag = "sep7";
        b.branch = "autor3search-java/sep7";
        b.commit = "a3f1c2d";
        b.measureCommit = "9b7e410";
        b.createdAt = "2026-09-07T10:00:00Z";
        b.benchmarks = List.of("a.B.run");
        b.pattern = "^(\\Qa.B.run\\E)$";
        b.configSha256 = "deadbeef";
        b.buildTool = "maven";
        b.moduleDir = "core";
        return b;
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("baseline.json");
        sample().save(path);
        Baseline back = Baseline.load(path);
        assertEquals("sep7", back.tag);
        assertEquals("a3f1c2d", back.commit);
        assertEquals("9b7e410", back.measureCommit);
        assertEquals(List.of("a.B.run"), back.benchmarks);
        assertEquals("^(\\Qa.B.run\\E)$", back.pattern);
        assertEquals("maven", back.buildTool);
        assertEquals("core", back.moduleDir);
    }

    /**
     * The frozen anchor and the advancing measurement pointer are separate fields
     * on purpose. A record written before the advancing one existed must not load
     * with an empty measurement commit, which would fail the worktree integrity
     * check on its very first evaluation.
     */
    @Test
    void anAbsentMeasureCommitFallsBackToTheFrozenAnchor(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("baseline.json");
        Files.writeString(path, """
                {"tag":"sep7","branch":"autor3search-java/sep7","commit":"a3f1c2d","benchmarks":[]}
                """, StandardCharsets.UTF_8);
        Baseline b = Baseline.load(path);
        assertEquals("a3f1c2d", b.measureCommit);
        assertEquals(".", b.moduleDir);
        assertEquals(List.of(), b.benchmarks);
    }

    @Test
    void aMissingBaselineNamesTheCommandThatCreatesOne(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> Baseline.load(dir.resolve("nope.json")));
        assertTrue(e.getMessage().contains("autor3search-java baseline"), e.getMessage());
    }

    @Test
    void aMalformedBaselineFailsLoudly(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("baseline.json");
        Files.writeString(path, "{ not json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> Baseline.load(path));
    }
}
