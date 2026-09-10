package io.github.autor3search.git;

import io.github.autor3search.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitTest {

    private static TestRepo repo(Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("repo"));
        r.write("a.txt", "one\n").commit("initial");
        return r;
    }

    @Test
    void readsTheBasicsOfARepository(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        assertEquals(r.root().toRealPath(), Git.root(r.root()).toRealPath());
        assertEquals("main", Git.currentBranch(r.root()));
        assertEquals(7, Git.headCommit(r.root()).length());
        assertEquals("initial", Git.headSubject(r.root()));
        assertTrue(Git.isClean(r.root()));
    }

    @Test
    void seesAnUncommittedChangeAsDirty(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        r.write("a.txt", "two\n");
        assertFalse(Git.isClean(r.root()));
    }

    @Test
    void branchesCanBeCreatedCheckedOutAndDeleted(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        assertFalse(Git.branchExists(r.root(), "run/x"));
        Git.createBranch(r.root(), "run/x");
        assertTrue(Git.branchExists(r.root(), "run/x"));
        assertEquals("run/x", Git.currentBranch(r.root()));
        Git.checkout(r.root(), "main");
        Git.deleteBranch(r.root(), "run/x");
        assertFalse(Git.branchExists(r.root(), "run/x"));
    }

    @Test
    void changedSinceListsTrackedAndUntrackedPaths(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        String base = Git.headCommit(r.root());
        r.write("a.txt", "changed\n");
        r.write("src/new.java", "class New {}\n");
        r.commit("edit and add");
        r.write("untracked.txt", "not committed\n");

        assertEquals(List.of("a.txt", "src/new.java", "untracked.txt"), Git.changedSince(r.root(), base));
    }

    /**
     * Without -z, git quotes and octal-escapes any path with non-ASCII bytes, and
     * the escaped spelling would then fail scope matching against the real path.
     */
    @Test
    void changedSinceReportsNonAsciiPathsUnescaped(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        String base = Git.headCommit(r.root());
        r.write("src/café.java", "class Cafe {}\n");
        r.commit("add an accented path");
        assertEquals(List.of("src/café.java"), Git.changedSince(r.root(), base));
    }

    @Test
    void worktreesCanBePinnedRepointedAndRemoved(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        String first = Git.headCommit(r.root());
        r.write("a.txt", "two\n");
        String second = r.commit("second");

        Path worktree = dir.resolve("pinned");
        Git.addWorktree(r.root(), worktree, first);
        assertEquals(first, Git.headCommit(worktree));
        assertEquals("one\n", Files.readString(worktree.resolve("a.txt")));

        Git.checkoutDetached(worktree, second);
        assertEquals(second, Git.headCommit(worktree));
        assertEquals("two\n", Files.readString(worktree.resolve("a.txt")));

        Git.removeWorktree(r.root(), worktree);
        assertFalse(Files.exists(worktree));
    }

    /**
     * Nothing should ever be modifying the pinned baseline worktree, but checking
     * out over a dirty tree without -f would fail instead of re-pointing it.
     */
    @Test
    void repointingDiscardsStrayChangesInThePinnedWorktree(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        String first = Git.headCommit(r.root());
        r.write("a.txt", "two\n");
        String second = r.commit("second");

        Path worktree = dir.resolve("pinned");
        Git.addWorktree(r.root(), worktree, first);
        Files.writeString(worktree.resolve("a.txt"), "tampered\n");
        Git.checkoutDetached(worktree, second);
        assertEquals("two\n", Files.readString(worktree.resolve("a.txt")));
    }

    @Test
    void aDirectoryOutsideAnyRepositoryIsAnError(@TempDir Path dir) throws IOException {
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertThrows(IOException.class, () -> Git.root(plain));
    }

    /** Stderr chatter on a successful command must never be parsed as the result. */
    @Test
    void stdoutIsCapturedSeparatelyFromStderr(@TempDir Path dir) throws IOException {
        TestRepo r = repo(dir);
        assertFalse(Git.headCommit(r.root()).contains("\n"));
        assertEquals(Git.headCommit(r.root()).trim(), Git.headCommit(r.root()));
    }
}
