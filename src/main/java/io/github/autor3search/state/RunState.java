package io.github.autor3search.state;

import io.github.autor3search.util.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Locates the OUT-OF-TREE directory holding every piece of state a run's score
 * depends on.
 *
 * <p>NONE of this may live inside the repository. The agent edits the
 * repository, so in-tree state would be silently writable by the very agent it
 * constrains. An agent could edit the frozen golden copies, delete a key from
 * the manifest, or — worst — edit the pinned baseline WORKTREE to make the
 * BASELINE slow, after which every candidate "improves" and every experiment
 * returns KEEP without anything having been optimized.
 */
public final class RunState {
    private RunState() {}

    /** The per-repository state directory name under the user cache. */
    public static final String STATE_DIR_NAME = "autor3search-java";

    /**
     * Relocates every run's out-of-tree state, replacing the default under the
     * user cache.
     *
     * <p>It exists for the two cases where the user cache is the wrong place: a
     * container or CI runner with no durable cache to speak of, and a TEST SUITE,
     * which would otherwise accumulate a directory per temporary repository in
     * the developer's real cache forever.
     */
    public static final String STATE_HOME_ENV = "AUTOR3SEARCH_JAVA_STATE_HOME";

    /** The baseline record, relative to a run's state directory. */
    public static final String BASELINE_FILE = "baseline.json";
    /** The pinned baseline worktree, relative to a run's state directory. */
    public static final String WORKTREE_NAME = "baseline-worktree";

    /**
     * The strict allow-list {@link #validateTag} enforces. Notably absent is
     * '/' — or any other path separator — which alone blocks both directory
     * traversal ("../../etc") and an absolute path.
     */
    private static final Pattern VALID_TAG = Pattern.compile("^[A-Za-z0-9._-]+$");

    /**
     * Throws unless tag is safe to use as a filesystem path segment.
     *
     * <p>{@link #stateDir} joins the tag straight into an out-of-tree path, and
     * callers create that path immediately afterwards — long before git's own
     * ref-name rules would ever get a chance to reject it. Without this check a
     * tag like {@code ../../../../tmp/evil} would create a directory wherever the
     * traversal lands. The check is an allow-list rather than an attempt to
     * enumerate dangerous sequences, and "." and ".." are rejected explicitly even
     * though both characters are individually allowed, since either one alone
     * means "this directory" or "the parent" rather than naming anything.
     */
    public static void validateTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("tag must not be empty");
        }
        if (tag.equals(".") || tag.equals("..")) {
            throw new IllegalArgumentException("tag \"" + tag + "\" is not allowed: \"" + tag
                    + "\" is a directory reference, not a run identifier");
        }
        if (!VALID_TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("tag \"" + tag
                    + "\" is not allowed: tags may contain only letters, digits, '.', '_' and '-'");
        }
    }

    /**
     * The state directory for one repository and run tag, keyed by a hash of the
     * repository's real path so two checkouts of the same project never share it.
     */
    public static Path stateDir(Path repoRoot, String tag) throws IOException {
        validateTag(tag);
        Path abs = repoRoot.toAbsolutePath().normalize();
        try {
            // Resolve symlinked ancestors (macOS's /tmp -> /private/tmp, a home
            // directory on a linked volume) so the same repository reached by two
            // spellings hashes to the same key.
            abs = abs.toRealPath();
        } catch (IOException e) {
            // The path does not exist yet; the unresolved absolute path still keys
            // consistently for every command in this run.
        }
        String key = Hashes.sha256(abs.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        return stateHome().resolve(key).resolve(tag);
    }

    /**
     * The directory holding every repository's run state.
     *
     * <p>A relative override is REFUSED rather than resolved. Resolving one would
     * make the location depend on the working directory each command happened to
     * be invoked from, so {@code eval} run from a submodule and {@code stop} run
     * from the repository root would address different state for the same run —
     * and the brake would silently miss.
     */
    public static Path stateHome() throws IOException {
        String override = System.getenv(STATE_HOME_ENV);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (!p.isAbsolute()) {
                throw new IOException(STATE_HOME_ENV + " must be an absolute path, got \"" + override
                        + "\": a relative state home would resolve differently depending on where each"
                        + " command is run from");
            }
            return p;
        }
        return userCacheDir().resolve(STATE_DIR_NAME);
    }

    /** The platform's conventional cache directory. */
    static Path userCacheDir() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.startsWith("mac")) {
            return Path.of(home, "Library", "Caches");
        }
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) return Path.of(local);
            return Path.of(home, "AppData", "Local");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) return Path.of(xdg);
        if (home == null || home.isBlank()) {
            throw new IOException("cannot locate a user cache directory; set " + STATE_HOME_ENV);
        }
        return Path.of(home, ".cache");
    }

    /** Creates dir and every missing parent. */
    public static void mkdirs(Path dir) throws IOException {
        Files.createDirectories(dir);
    }
}
