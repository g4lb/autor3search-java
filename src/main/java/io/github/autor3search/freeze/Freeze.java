package io.github.autor3search.freeze;

import io.github.autor3search.util.Hashes;
import io.github.autor3search.util.Paths2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshots the files the success criteria depend on at baseline time, and
 * restores them before every evaluation, so an agent cannot weaken its own
 * success criteria.
 *
 * <p>The store and its manifest live under the run's OUT-OF-TREE state
 * directory, never under the repository root. They are part of what the metric
 * depends on, so they have to live where the agent being measured cannot reach
 * them; a caller that joined these onto the repository root instead would
 * silently reintroduce exactly the hole this package closes.
 */
public final class Freeze {
    private Freeze() {}

    /** The golden copies, relative to the run's state directory. */
    public static final String STORE_DIR = "frozen";
    /** The manifest, relative to the run's state directory. */
    public static final String MANIFEST_PATH = "frozen/manifest.json";

    /**
     * Copies each file into storeDir and records its hash. Paths are
     * repository-relative and are preserved inside the store.
     */
    public static Manifest snapshot(Path repoRoot, Path storeDir, List<String> files) throws IOException {
        Manifest m = new Manifest();
        for (String rel : files) {
            Path src = safeResolve(repoRoot, rel, "snapshot");
            requireNoSymlink(repoRoot, rel, "snapshot", "in the repository");
            byte[] content = Files.readAllBytes(src);
            Path dst = safeResolve(storeDir, rel, "snapshot");
            // The store is harness-owned, but a directory left by an earlier attempt
            // under the same tag is not necessarily pristine: refuse to write the
            // golden copy through a link there either.
            requireNoSymlink(storeDir, rel, "snapshot", "inside the frozen store");
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.write(dst, content);
            m.files.put(rel, Hashes.sha256(content));
        }
        return m;
    }

    /**
     * Rewrites every frozen file in the working tree from the store, recreating
     * ones the agent deleted, and returns the paths it changed.
     *
     * <p>Both sides are checked against the hash the manifest recorded at
     * baseline, and the working tree is examined BEFORE the store is read. That
     * ordering is what makes the common case — an evaluation where the agent
     * touched no frozen file — cost one read per file instead of two: the
     * destination already hashes to the manifest value, so the golden copy is
     * never opened at all.
     */
    public static List<String> restore(Path repoRoot, Path storeDir, Manifest m) throws IOException {
        List<String> changed = new ArrayList<>();
        for (String rel : m.paths()) {
            Path dst = safeResolve(repoRoot, rel, "restore");
            // Before any read OR write of dst: reading follows links just as writing
            // does, so this has to come first, or we would read through one and
            // conclude the file was fine.
            requireNoSymlink(repoRoot, rel, "restore",
                    "in the repository; refusing to write through it, which could reach a file outside it");
            String want = m.files.get(rel);
            if (Files.isRegularFile(dst) && Hashes.sha256File(dst).equals(want)) {
                continue; // already the frozen content; the store need not be read
            }

            Path src = safeResolve(storeDir, rel, "restore");
            requireNoSymlink(storeDir, rel, "restore",
                    "inside the frozen store; refusing to restore content read through it");
            byte[] golden = Files.readAllBytes(src);
            String got = Hashes.sha256(golden);
            if (!got.equals(want)) {
                throw new FreezeException(FreezeException.Kind.STORE_TAMPERED,
                        "restore " + rel + ": the frozen store copy hashes to " + got
                                + " but the manifest records " + want);
            }
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.write(dst, golden);
            changed.add(rel);
        }
        return changed;
    }

    /** Which frozen files currently differ from the baseline. A deleted file counts as changed. */
    public static List<String> verify(Path repoRoot, Manifest m) throws IOException {
        List<String> changed = new ArrayList<>();
        for (String rel : m.paths()) {
            Path p = safeResolve(repoRoot, rel, "verify");
            if (symlinkComponent(repoRoot, rel) != null) {
                // A frozen path with a link anywhere along it is at least as
                // suspicious as a deleted one. Report it as changed rather than
                // following the link to read whatever it points at.
                changed.add(rel);
                continue;
            }
            if (!Files.exists(p)) {
                changed.add(rel);
                continue;
            }
            if (!Hashes.sha256File(p).equals(m.files.get(rel))) {
                changed.add(rel);
            }
        }
        return changed;
    }

    /**
     * Joins rel onto root, rejecting anything that would escape it. Manifest
     * entries come from a JSON file on disk, so they are untrusted input:
     * {@link #restore} writes through them before every single evaluation.
     */
    static Path safeResolve(Path root, String rel, String op) throws IOException {
        String s = rel.replace('\\', '/');
        if (s.startsWith("/") || Path.of(rel).isAbsolute()) {
            throw new FreezeException(FreezeException.Kind.ESCAPES_ROOT,
                    op + " " + rel + ": frozen paths must be relative");
        }
        String clean = Paths2.clean(s);
        if (clean.equals("..") || clean.startsWith("../")) {
            throw new FreezeException(FreezeException.Kind.ESCAPES_ROOT,
                    op + " " + rel + ": frozen path escapes the repository root");
        }
        return root.resolve(clean);
    }

    private static void requireNoSymlink(Path root, String rel, String op, String where) throws IOException {
        String link = symlinkComponent(root, rel);
        if (link != null) {
            throw new FreezeException(FreezeException.Kind.SYMLINK,
                    op + " " + rel + ": " + link + " is a symlink " + where);
        }
    }

    /**
     * The first component of rel beneath root that is a symlink — as a
     * slash-separated path relative to root — or null when none is.
     *
     * <p>Checking only the FINAL component is not enough, and that is the hole
     * this closes: reads and writes resolve the WHOLE path, so replacing a parent
     * DIRECTORY with a link redirects a write exactly as effectively as replacing
     * the file itself does, and lands the frozen content outside the repository.
     * A check on the file alone then reports a perfectly ordinary regular file,
     * because it has already followed the link to get there.
     *
     * <p>{@code root} itself is deliberately not examined. A repository
     * legitimately reached through a symlinked ancestor — macOS's {@code /tmp}, a
     * home directory on a linked volume, a checkout under a symlinked mount — is
     * not tampering, and refusing to work there would break ordinary setups.
     */
    static String symlinkComponent(Path root, String rel) {
        String[] parts = Paths2.clean(rel.replace('\\', '/')).split("/");
        Path p = root;
        StringBuilder seen = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            p = p.resolve(part);
            if (seen.length() > 0) seen.append('/');
            seen.append(part);
            if (Files.isSymbolicLink(p)) {
                return seen.toString();
            }
        }
        return null;
    }
}
