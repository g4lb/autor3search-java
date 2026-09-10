package io.github.autor3search.cli;

import io.github.autor3search.build.BuildTool;
import io.github.autor3search.build.BuildTools;
import io.github.autor3search.config.Config;
import io.github.autor3search.discover.Benchmark;
import io.github.autor3search.discover.Discovery;
import io.github.autor3search.freeze.Freeze;
import io.github.autor3search.freeze.Manifest;
import io.github.autor3search.git.Git;
import io.github.autor3search.measure.Jmh;
import io.github.autor3search.results.Results;
import io.github.autor3search.results.Row;
import io.github.autor3search.state.Baseline;
import io.github.autor3search.state.RunState;
import io.github.autor3search.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Establishes the fixed reference point for one experiment run: a fresh run
 * branch, a frozen golden copy of every test and benchmark source, a pinned
 * detached worktree at the baseline commit, and a baseline record — every one of
 * which lives OUTSIDE the repository being optimized. Only {@code results.tsv},
 * a human-readable log rather than part of the metric, stays in the (gitignored)
 * repository.
 *
 * <p>One working directory supports one run at a time: the git checkout and
 * {@code results.tsv} are both repository-scoped, not tag-scoped, so two
 * concurrent invocations against the same checkout would race on the branch
 * creation and on the results guard. Run concurrent baselines against separate
 * clones instead.
 */
public final class BaselineCommand {
    private BaselineCommand() {}

    public static int run(String[] args) {
        Flags f = new Flags("baseline");
        f.string("C", ".", "repository root (or a directory inside it)");
        f.string("tag", InitCommand.defaultTag(), "run identifier, e.g. sep7");
        f.bool("force", false, "discard an existing results.tsv that already holds experiment rows");
        if (!f.parse(args)) return ExitCodes.USAGE;

        String tag = f.get("tag").trim();
        if (tag.isEmpty()) {
            System.err.println("autor3search-java baseline: -tag must not be empty");
            return ExitCodes.USAGE;
        }
        // Validated before any filesystem path is built from it: resolving a tag
        // containing ".." would create a directory wherever the traversal lands,
        // long before git's own ref-name rules would reject it.
        try {
            RunState.validateTag(tag);
        } catch (IllegalArgumentException e) {
            System.err.println("autor3search-java baseline: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        Path root;
        Path stateDir;
        Config cfg;
        try {
            root = Git.root(Path.of(f.get("C")));
            stateDir = RunState.stateDir(root, tag);
            Files.createDirectories(stateDir);
            cfg = ConfigLoading.load("baseline", root);
        } catch (IOException | ConfigLoading.LoadException e) {
            System.err.println("autor3search-java baseline: " + e.getMessage());
            return ExitCodes.USAGE;
        }

        try {
            // A dirty tree means the commit the baseline pins would not reflect what
            // is actually on disk: the baseline would not be reproducible.
            if (!Git.isClean(root)) {
                System.err.println("autor3search-java baseline: the working tree is dirty; a baseline"
                        + " recorded against uncommitted changes is not reproducible. Commit or stash"
                        + " your changes first.");
                return ExitCodes.USAGE;
            }

            // A reused tag would silently compare later candidates against whatever
            // commit the old branch happens to point at now, not a fresh baseline.
            String branch = RunRef.BRANCH_PREFIX + tag;
            if (Git.branchExists(root, branch)) {
                System.err.println("autor3search-java baseline: branch " + branch + " already exists; the"
                        + " branch must be fresh — a reused tag would compare against the wrong commit."
                        + " Pick a different -tag.");
                return ExitCodes.USAGE;
            }

            List<Benchmark> discovered = Discovery.benchmarks(root);
            // A configured benchmark that does not exist would pin the run to
            // something no evaluation can ever measure: nothing would catch it until
            // the first measurement failed with "no benchmarks matched", by which
            // point the human has walked away for the night. Catch it here, before
            // the branch exists, so a config typo never needs rolling back.
            if (!cfg.benchmarks.isEmpty()) {
                Set<String> known = new LinkedHashSet<>(Discovery.names(discovered));
                List<String> unknown = new ArrayList<>();
                for (String n : cfg.benchmarks) {
                    if (!known.contains(n)) unknown.add(n);
                }
                if (!unknown.isEmpty()) {
                    System.err.println("autor3search-java baseline: " + root.resolve(Config.PATH)
                            + " names unknown benchmark(s): " + String.join(", ", unknown));
                    System.err.println("available: " + String.join(", ", known));
                    return ExitCodes.USAGE;
                }
            }
            BuildTool tool = BuildTools.detect(root, cfg.buildTool, discovered);

            // results.tsv is the sole durable record of a previous unattended run. A
            // fresh baseline must not silently truncate it: check — and refuse,
            // absent -force — before anything else is created or mutated, so a
            // refusal leaves no half-made branch, freeze or worktree behind.
            Path resultsPath = root.resolve(Results.PATH);
            List<Row> existing = Results.load(resultsPath);
            if (!existing.isEmpty() && !f.flag("force")) {
                System.err.println("autor3search-java baseline: " + resultsPath + " already holds "
                        + existing.size() + " experiment row(s) from a previous run; a fresh baseline would"
                        + " erase that record with no way to get it back.");
                System.err.println("Move or delete it if you no longer need it, or re-run with -force to"
                        + " discard it.");
                return ExitCodes.USAGE;
            }

            // Recorded before the branch is created so a failure anywhere below can
            // put the working tree back exactly where it found it.
            String originalBranch = Git.currentBranch(root);
            Git.createBranch(root, branch);

            try {
                Result r = finish(root, stateDir, resultsPath, branch, tag, cfg, tool);
                printSummary(branch, r.commit(), r.frozen(), cfg.benchmarks, tool);
                return ExitCodes.OK;
            } catch (IOException e) {
                // The run branch must not be left behind consuming this tag
                // permanently: roll back before reporting the failure.
                System.err.println("autor3search-java baseline: " + e.getMessage());
                try {
                    Git.checkout(root, originalBranch);
                    Git.deleteBranch(root, branch);
                } catch (IOException cleanup) {
                    System.err.println("autor3search-java baseline: cleanup did not fully succeed: "
                            + cleanup.getMessage() + "\nyou may need to run `git checkout " + originalBranch
                            + " && git branch -D " + branch + "` by hand.");
                }
                return ExitCodes.USAGE;
            }
        } catch (IOException e) {
            System.err.println("autor3search-java baseline: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    private record Result(String commit, int frozen) {}

    /**
     * Every remaining step once the run branch exists. Split out so a failure at
     * any point can be compensated for uniformly by its caller instead of
     * duplicating the rollback at every return site.
     */
    private static Result finish(Path root, Path stateDir, Path resultsPath, String branch, String tag,
                                 Config cfg, BuildTool tool) throws IOException {
        List<String> files = Discovery.frozenFiles(root, cfg.unfreeze);
        Path storeDir = stateDir.resolve(Freeze.STORE_DIR);
        Manifest manifest = Freeze.snapshot(root, storeDir, files);
        manifest.save(stateDir.resolve(Freeze.MANIFEST_PATH));

        String commit = Git.headCommit(root);

        Baseline b = new Baseline();
        b.tag = tag;
        b.branch = branch;
        b.commit = commit;
        // Starts equal to the frozen anchor; the pipeline advances it after every
        // KEEP. See Baseline's own documentation for why the two are separate.
        b.measureCommit = commit;
        b.createdAt = Instant.now().toString();
        b.benchmarks = List.copyOf(cfg.benchmarks);
        b.pattern = Jmh.pattern(cfg.benchmarks);
        b.configSha256 = Hashes.sha256File(root.resolve(Config.PATH));
        b.buildTool = tool.name();
        b.moduleDir = tool.moduleDir();
        b.save(stateDir.resolve(RunState.BASELINE_FILE));

        // Pin the baseline worktree OUTSIDE the repository, at the exact commit
        // just recorded. Any stale worktree from a previous attempt under the same
        // tag is removed first; that removal's failure is ignored because the
        // common case — nothing to remove yet — is itself an error from git rather
        // than a distinguishable "already absent" result.
        Path worktree = stateDir.resolve(RunState.WORKTREE_NAME);
        try {
            Git.removeWorktree(root, worktree);
        } catch (IOException ignored) {
            // Nothing was there to remove, which is the normal case.
        }
        Git.addWorktree(root, worktree, commit);

        // Start this run's log fresh: a baseline is a new fixed reference point, so
        // rows measured against a previous one no longer apply. The caller's guard
        // already established this is safe.
        Results.reset(resultsPath);

        return new Result(commit, manifest.files.size());
    }

    private static void printSummary(String branch, String commit, int frozen, List<String> benchmarks,
                                     BuildTool tool) {
        System.out.println("autor3search-java baseline: recorded a fresh baseline");
        System.out.println("  branch:     " + branch);
        System.out.println("  commit:     " + commit);
        System.out.println("  frozen:     " + frozen + " source file(s)");
        System.out.println("  build:      " + tool.describe());
        System.out.println("  benchmarks: " + (benchmarks.isEmpty() ? "(all discovered)"
                : String.join(", ", benchmarks)));
        System.out.println("\nnext:");
        System.out.println("  autor3search-java eval");
    }
}
