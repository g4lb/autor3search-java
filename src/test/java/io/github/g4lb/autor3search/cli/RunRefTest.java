package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.TestRepo;
import io.github.g4lb.autor3search.git.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRefTest {

    private static TestRepo repo(Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("repo"));
        r.write("a.txt", "x\n").commit("initial");
        return r;
    }

    @Test
    void takesTheTagFromTheRunBranch(@TempDir Path dir) throws Exception {
        TestRepo r = repo(dir);
        Git.createBranch(r.root(), RunRef.BRANCH_PREFIX + "sep7");
        RunRef ref = RunRef.resolve("eval", r.root().toString(), null);
        assertEquals("sep7", ref.tag());
        assertEquals(RunRef.BRANCH_PREFIX + "sep7", ref.branch());
        assertEquals("baseline-worktree", ref.worktreeDir().getFileName().toString());
    }

    /**
     * A brake that only works from the right branch is not a brake, so status and
     * stop accept a tag from anywhere.
     */
    @Test
    void anExplicitTagWorksFromAnyBranch(@TempDir Path dir) throws Exception {
        TestRepo r = repo(dir);
        RunRef ref = RunRef.resolve("stop", r.root().toString(), "sep7");
        assertEquals("sep7", ref.tag());
        assertEquals("main", ref.branch());
    }

    @Test
    void saysHowToFixBeingOnTheWrongBranch(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        RunRef.ResolveException e = assertThrows(RunRef.ResolveException.class,
                () -> RunRef.resolve("eval", r.root().toString(), null));
        assertTrue(e.getMessage().contains("git checkout"), e.getMessage());
        assertTrue(e.getMessage().contains("autor3search-java baseline"), e.getMessage());
    }

    /**
     * A tag from a branch name has passed git's ref rules; a -tag flag has passed
     * nothing, and it is about to become a directory.
     */
    @Test
    void validatesATagPassedAsAFlag(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        assertThrows(RunRef.ResolveException.class,
                () -> RunRef.resolve("stop", r.root().toString(), "../escape"));
    }

    @Test
    void reportsADirectoryOutsideAnyRepository(@TempDir Path dir) {
        RunRef.ResolveException e = assertThrows(RunRef.ResolveException.class,
                () -> RunRef.resolve("status", dir.toString(), "t"));
        assertTrue(e.getMessage().contains("not inside a git repository"), e.getMessage());
    }
}
