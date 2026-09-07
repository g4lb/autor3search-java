package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.state.RunState;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Everything a command needs to address ONE run: where the repository is, which
 * run it belongs to, and where that run's out-of-tree state lives.
 *
 * <p>It stops short of loading the baseline record, so {@code stop} can brake a
 * run whose {@code baseline} never finished.
 */
public record RunRef(Path root, String branch, String tag, Path stateDir) {

    /** The run-branch naming convention {@code baseline} establishes. */
    public static final String BRANCH_PREFIX = "autor3search-java/";

    /**
     * The pinned baseline worktree for this run. Its presence is not guaranteed:
     * {@code baseline} writes the record before pinning the worktree, so an
     * interrupted baseline leaves the record without the worktree.
     */
    public Path worktreeDir() {
        return stateDir.resolve(RunState.WORKTREE_NAME);
    }

    /** A failure to resolve a run, already phrased for the human. */
    public static final class ResolveException extends Exception {
        public ResolveException(String message) {
            super(message);
        }
    }

    /**
     * Locates the run a command should act on.
     *
     * <p>The tag normally comes from the checked-out branch rather than a flag, so
     * there is no way to point {@code eval} at the wrong run by mistake.
     * {@code tagOverride} relaxes that for the commands a HUMAN runs from their
     * own shell ({@code status}, {@code stop}), which must keep working when that
     * shell is on another branch — a brake that only works from the right branch
     * is not a brake. It is never offered to {@code eval}.
     */
    public static RunRef resolve(String cmdName, String dir, String tagOverride) throws ResolveException {
        Path root;
        try {
            root = Git.root(Path.of(dir));
        } catch (IOException e) {
            throw new ResolveException(dir + " is not inside a git repository: " + e.getMessage());
        }
        String branch;
        try {
            branch = Git.currentBranch(root);
        } catch (IOException e) {
            throw new ResolveException(e.getMessage());
        }

        String tag = tagOverride;
        if (tag == null || tag.isBlank()) {
            if (!branch.startsWith(BRANCH_PREFIX)) {
                throw new ResolveException("current branch \"" + branch + "\" is not a run branch (expected "
                        + BRANCH_PREFIX + "<tag>). Check out your run branch first, e.g. `git checkout "
                        + BRANCH_PREFIX + "sep7`, name the run with -tag, or run `autor3search-java baseline`"
                        + " if you have not started a run yet.");
            }
            tag = branch.substring(BRANCH_PREFIX.length());
        }

        // A tag taken from a branch name has already passed git's own ref rules,
        // but a -tag flag has passed nothing. Both are validated here: the value is
        // about to be joined into an out-of-tree path that will be created, long
        // before any git operation could reject it.
        try {
            RunState.validateTag(tag);
            return new RunRef(root, branch, tag, RunState.stateDir(root, tag));
        } catch (IllegalArgumentException | IOException e) {
            throw new ResolveException(e.getMessage());
        }
    }
}
