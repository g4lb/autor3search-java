package io.github.g4lb.autor3search.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStateTest {

    @Test
    void acceptsOrdinaryTags() {
        for (String tag : List.of("sep7", "2026-09-07", "run_1", "v1.2.3", "A")) {
            RunState.validateTag(tag);
        }
    }

    /**
     * The tag is joined straight into an out-of-tree path that is then created —
     * long before git's own ref-name rules would get a chance to reject it. Without
     * this check a traversal tag creates a directory wherever it lands.
     */
    @Test
    void refusesAnythingThatCouldBecomeAPath() {
        for (String tag : List.of("../evil", "a/b", "/etc/passwd", ".", "..", "", "a b", "a:b", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> RunState.validateTag(tag), tag);
        }
    }

    @Test
    void stateDirIsKeyedByRepositoryAndTag(@TempDir Path dir) throws IOException {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Path aOne = RunState.stateDir(a, "one");
        Path aTwo = RunState.stateDir(a, "two");
        Path bOne = RunState.stateDir(b, "one");

        assertEquals("one", aOne.getFileName().toString());
        assertNotEquals(aOne, aTwo);
        assertNotEquals(aOne, bOne);
        // Two checkouts of the same project never share state.
        assertNotEquals(aOne.getParent(), bOne.getParent());
    }

    /**
     * The same repository reached by two spellings — a symlinked ancestor, say —
     * must hash to the same key, or `eval` and `stop` address different state for
     * one run.
     */
    @Test
    void stateDirResolvesSymlinkedAncestors(@TempDir Path dir) throws IOException {
        Path real = Files.createDirectories(dir.resolve("real/repo"));
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, dir.resolve("real"));
        assertEquals(RunState.stateDir(real, "t"), RunState.stateDir(link.resolve("repo"), "t"));
    }

    @Test
    void stateDirValidatesTheTagItself(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> RunState.stateDir(dir, "../escape"));
    }

    @Test
    void stateHomeSitsUnderAToolNamedDirectory() throws IOException {
        // The env override is not set in this JVM, so this exercises the default.
        if (System.getenv(RunState.STATE_HOME_ENV) == null) {
            assertEquals(RunState.STATE_DIR_NAME, RunState.stateHome().getFileName().toString());
        }
    }

    @Test
    void userCacheDirIsAbsolute() throws IOException {
        assertTrue(RunState.userCacheDir().isAbsolute());
    }
}
