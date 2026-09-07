package io.github.g4lb.autor3search.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * The git commands the harness needs, and nothing else.
 *
 * <p>Stdout and stderr are captured separately so that chatter on an otherwise
 * successful command — git-lfs smudge filters, {@code advice.*} hints, locale
 * warnings, a user's own hooks writing to stderr — is never parsed as part of
 * the result. Stderr appears only in the message of a failure.
 */
public final class Git {
    private Git() {}

    /** A git invocation that exited non-zero. */
    public static class GitException extends IOException {
        public GitException(String message) {
            super(message);
        }
    }

    /** Runs a git subcommand in dir and returns its trimmed stdout. */
    public static String git(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new GitException("run git " + String.join(" ", args) + ": " + e.getMessage());
        }
        String out;
        String err;
        try (InputStream so = p.getInputStream(); InputStream se = p.getErrorStream()) {
            // Drain stderr on another thread: a command that fills the stderr pipe
            // buffer while we are blocked reading stdout would deadlock.
            StringBuilder errBuf = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    errBuf.append(new String(se.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The process is going away; nothing useful to add to the message.
                }
            });
            drain.setDaemon(true);
            drain.start();
            out = new String(so.readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            drain.join(TimeUnit.SECONDS.toMillis(5));
            err = errBuf.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("git " + String.join(" ", args) + " was interrupted");
        }
        if (p.exitValue() != 0) {
            throw new GitException("git " + String.join(" ", args) + " exited " + p.exitValue() + "\n" + err.trim());
        }
        return out.trim();
    }

    /** The repository root containing dir. */
    public static Path root(Path dir) throws IOException {
        return Path.of(git(dir, "rev-parse", "--show-toplevel"));
    }

    /** The short hash of HEAD. */
    public static String headCommit(Path dir) throws IOException {
        return git(dir, "rev-parse", "--short=7", "HEAD");
    }

    /**
     * The first line of HEAD's commit message. It is what {@code stop -force}
     * prints to identify the commit an abandoned experiment left behind, so only
     * the subject is wanted — never the body or its trailers.
     */
    public static String headSubject(Path dir) throws IOException {
        return git(dir, "log", "-1", "--format=%s");
    }

    /** The checked-out branch name. */
    public static String currentBranch(Path dir) throws IOException {
        return git(dir, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /** Whether a local branch exists. */
    public static boolean branchExists(Path dir, String name) throws IOException {
        try {
            git(dir, "show-ref", "--verify", "--quiet", "refs/heads/" + name);
            return true;
        } catch (GitException e) {
            return false;
        }
    }

    /** Creates and checks out a branch at HEAD. */
    public static void createBranch(Path dir, String name) throws IOException {
        git(dir, "checkout", "-b", name);
    }

    /** Switches the working tree to an existing ref. */
    public static void checkout(Path dir, String ref) throws IOException {
        git(dir, "checkout", ref);
    }

    /** Force-deletes a local branch, undoing a run branch a failed baseline left behind. */
    public static void deleteBranch(Path dir, String name) throws IOException {
        git(dir, "branch", "-D", name);
    }

    /** Whether the working tree has no changes at all. */
    public static boolean isClean(Path dir) throws IOException {
        return git(dir, "status", "--porcelain").isEmpty();
    }

    /**
     * Repository-relative paths modified since commit, including files that are
     * still untracked, sorted.
     *
     * <p>Both underlying calls use {@code -z}. Without it git quotes and
     * octal-escapes any path containing non-ASCII bytes, quotes or backslashes
     * (for example {@code "src/main/java/caf\303\251.java"}), and the escaped
     * spelling would then fail scope matching against the real path.
     */
    public static List<String> changedSince(Path dir, String commit) throws IOException {
        String tracked = git(dir, "diff", "--name-only", "-z", commit);
        String untracked = git(dir, "ls-files", "-z", "--others", "--exclude-standard");
        Set<String> out = new TreeSet<>();
        for (String block : List.of(tracked, untracked)) {
            for (String entry : block.split("\0")) {
                if (!entry.isEmpty()) out.add(entry);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /** Checks commit out into a new detached worktree at path. */
    public static void addWorktree(Path repoDir, Path path, String commit) throws IOException {
        git(repoDir, "worktree", "add", "--detach", path.toString(), commit);
    }

    /**
     * Moves an existing worktree to commit, detached. Used to advance the pinned
     * baseline worktree after a KEEP: re-pointing an existing worktree with a
     * plain checkout has less failure surface than removing and re-adding it,
     * which also re-registers the worktree in the main repository's metadata.
     * {@code -f} discards stray changes in the target — nothing should ever be
     * modifying the pinned worktree, but checking out over a dirty tree without
     * it would fail instead of re-pointing.
     */
    public static void checkoutDetached(Path dir, String commit) throws IOException {
        git(dir, "checkout", "-f", "--detach", commit);
    }

    /**
     * Deletes a worktree previously created by {@link #addWorktree}.
     *
     * <p>Passes {@code --force}, which silently discards uncommitted and
     * untracked changes in the target. git still refuses to remove the main
     * working tree or a path that is not a registered worktree, but callers must
     * only ever point this at a worktree the harness itself created — never at a
     * path a human or the agent might have unsaved work in.
     */
    public static void removeWorktree(Path repoDir, Path path) throws IOException {
        git(repoDir, "worktree", "remove", "--force", path.toString());
    }
}
