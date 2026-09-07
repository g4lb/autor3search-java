package io.github.g4lb.autor3search.freeze;

import io.github.g4lb.autor3search.util.Hashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeTest {

    private static final String REL = "src/test/java/a/AT.java";

    private static Path write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private record Fixture(Path repo, Path store, Manifest manifest) {}

    private static Fixture setUp(Path dir) throws IOException {
        Path repo = dir.resolve("repo");
        Path store = dir.resolve("state/frozen");
        write(repo, REL, "original\n");
        return new Fixture(repo, store, Freeze.snapshot(repo, store, List.of(REL)));
    }

    @Test
    void snapshotRecordsAHashAndKeepsTheLayout(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        assertEquals(Hashes.sha256("original\n".getBytes(StandardCharsets.UTF_8)), f.manifest().files.get(REL));
        assertEquals("original\n", Files.readString(f.store().resolve(REL)));
    }

    @Test
    void restoreOverwritesAnEditedFile(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        write(f.repo(), REL, "weakened\n");
        assertEquals(List.of(REL), Freeze.restore(f.repo(), f.store(), f.manifest()));
        assertEquals("original\n", Files.readString(f.repo().resolve(REL)));
    }

    @Test
    void restoreRecreatesADeletedFile(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Files.delete(f.repo().resolve(REL));
        assertEquals(List.of(REL), Freeze.restore(f.repo(), f.store(), f.manifest()));
        assertEquals("original\n", Files.readString(f.repo().resolve(REL)));
    }

    /** The common case costs one read per file and never opens the store at all. */
    @Test
    void restoreReportsNothingChangedWhenNothingWasTouched(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        assertEquals(List.of(), Freeze.restore(f.repo(), f.store(), f.manifest()));
    }

    @Test
    void verifyReportsEditedAndDeletedFiles(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        assertEquals(List.of(), Freeze.verify(f.repo(), f.manifest()));
        write(f.repo(), REL, "changed\n");
        assertEquals(List.of(REL), Freeze.verify(f.repo(), f.manifest()));
        Files.delete(f.repo().resolve(REL));
        assertEquals(List.of(REL), Freeze.verify(f.repo(), f.manifest()));
    }

    /**
     * Without checking the store against the recorded hash, an agent that rewrote
     * a golden copy would have its weakened test restored into the working tree by
     * every later evaluation — the exact outcome freezing exists to prevent.
     */
    @Test
    void restoreRefusesAStoreCopyThatNoLongerMatchesItsHash(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        write(f.repo(), REL, "weakened\n");
        write(f.store(), REL, "also weakened\n");
        FreezeException e = assertThrows(FreezeException.class,
                () -> Freeze.restore(f.repo(), f.store(), f.manifest()));
        assertEquals(FreezeException.Kind.STORE_TAMPERED, e.kind());
    }

    @Test
    void restoreRefusesToWriteThroughASymlinkedFile(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Path outside = dir.resolve("outside.java");
        Files.writeString(outside, "elsewhere\n");
        Files.delete(f.repo().resolve(REL));
        Files.createSymbolicLink(f.repo().resolve(REL), outside);

        FreezeException e = assertThrows(FreezeException.class,
                () -> Freeze.restore(f.repo(), f.store(), f.manifest()));
        assertEquals(FreezeException.Kind.SYMLINK, e.kind());
        assertEquals("elsewhere\n", Files.readString(outside));
    }

    /**
     * Checking only the final component is not enough: replacing a parent
     * DIRECTORY with a link redirects the write just as effectively, and a check
     * on the file itself then sees a perfectly ordinary regular file because it
     * has already followed the link to get there.
     */
    @Test
    void restoreRefusesASymlinkedParentDirectory(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Path elsewhere = dir.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        Files.writeString(elsewhere.resolve("AT.java"), "outside\n");

        Path pkg = f.repo().resolve("src/test/java/a");
        Files.delete(pkg.resolve("AT.java"));
        Files.delete(pkg);
        Files.createSymbolicLink(pkg, elsewhere);

        FreezeException e = assertThrows(FreezeException.class,
                () -> Freeze.restore(f.repo(), f.store(), f.manifest()));
        assertEquals(FreezeException.Kind.SYMLINK, e.kind());
        assertTrue(e.getMessage().contains("src/test/java/a"), e.getMessage());
        assertEquals("outside\n", Files.readString(elsewhere.resolve("AT.java")));
    }

    @Test
    void snapshotRefusesASymlinkedSource(@TempDir Path dir) throws IOException {
        Path repo = dir.resolve("repo");
        Path outside = dir.resolve("outside.java");
        Files.writeString(outside, "elsewhere\n");
        Files.createDirectories(repo.resolve("src/test/java/a"));
        Files.createSymbolicLink(repo.resolve(REL), outside);

        FreezeException e = assertThrows(FreezeException.class,
                () -> Freeze.snapshot(repo, dir.resolve("store"), List.of(REL)));
        assertEquals(FreezeException.Kind.SYMLINK, e.kind());
    }

    /** Verify treats a symlinked path as changed rather than following it. */
    @Test
    void verifyTreatsASymlinkAsChanged(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Path outside = dir.resolve("outside.java");
        Files.writeString(outside, "original\n"); // identical content, still refused
        Files.delete(f.repo().resolve(REL));
        Files.createSymbolicLink(f.repo().resolve(REL), outside);
        assertEquals(List.of(REL), Freeze.verify(f.repo(), f.manifest()));
    }

    /**
     * Manifest entries come from a JSON file on disk, so they are untrusted input:
     * restore writes through them before every evaluation.
     */
    @Test
    void refusesAManifestEntryThatEscapesTheRoot(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Manifest evil = new Manifest();
        evil.files.put("../escaped.java", f.manifest().files.get(REL));
        FreezeException e = assertThrows(FreezeException.class,
                () -> Freeze.restore(f.repo(), f.store(), evil));
        assertEquals(FreezeException.Kind.ESCAPES_ROOT, e.kind());
        assertFalse(Files.exists(dir.resolve("escaped.java")));
    }

    @Test
    void refusesAnAbsoluteManifestEntry(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Manifest evil = new Manifest();
        evil.files.put(dir.resolve("absolute.java").toString(), "deadbeef");
        assertThrows(FreezeException.class, () -> Freeze.restore(f.repo(), f.store(), evil));
    }

    @Test
    void manifestRoundTripsThroughDisk(@TempDir Path dir) throws IOException {
        Fixture f = setUp(dir);
        Path path = dir.resolve("state/frozen/manifest.json");
        f.manifest().save(path);
        Manifest back = Manifest.load(path);
        assertEquals(f.manifest().files, back.files);
        assertEquals(List.of(REL), back.paths());
    }
}
