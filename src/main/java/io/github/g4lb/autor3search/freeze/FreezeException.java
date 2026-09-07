package io.github.g4lb.autor3search.freeze;

import java.io.IOException;

/** Tampering with the frozen set, as distinct from an ordinary I/O failure. */
public class FreezeException extends IOException {

    /** What kind of tampering, so the caller can choose the verdict reason. */
    public enum Kind {
        /**
         * A symlink somewhere along a frozen file's path. Reads and writes follow
         * links, so a link — on the file OR on any directory leading to it — would
         * redirect a restore to somewhere outside the repository entirely.
         */
        SYMLINK,
        /**
         * A frozen golden copy that no longer hashes to what the manifest recorded.
         * Without checking, the store is trusted blindly and an agent that rewrote a
         * copy inside it would have its weakened test restored into the working tree
         * by every later evaluation — the exact outcome freezing exists to prevent.
         */
        STORE_TAMPERED,
        /** A manifest entry that does not name a path inside the repository. */
        ESCAPES_ROOT
    }

    private final Kind kind;

    public FreezeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
