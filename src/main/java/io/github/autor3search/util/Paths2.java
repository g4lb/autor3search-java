package io.github.autor3search.util;

import java.nio.file.Path;

/** Small path helpers shared by the gates, which all speak slash-separated repo-relative paths. */
public final class Paths2 {
    private Paths2() {}

    /** Renders a path with forward slashes, whatever the platform separator is. */
    public static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** Renders root-relative rel with forward slashes. */
    public static String rel(Path root, Path p) {
        return slash(root.relativize(p));
    }

    /**
     * Normalises a slash-separated relative path: collapses "." and empty
     * segments, resolves interior "..", and LEAVES a leading ".." in place rather
     * than resolving it away.
     *
     * <p>That last part is the whole point, and it is where
     * {@link java.nio.file.Path#normalize} differs. Callers reject a path that
     * still starts with ".." — a manifest entry, a scope candidate — so the
     * escape has to survive normalisation to be caught. Resolving it away would
     * hand them a path that looks ordinary and lands outside the repository.
     */
    public static String clean(String p) {
        String s = p.replace('\\', '/');
        boolean abs = s.startsWith("/");
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        int leadingUp = 0;
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty()) {
                    out.removeLast();
                } else if (!abs) {
                    leadingUp++;
                }
                continue;
            }
            out.addLast(part);
        }
        StringBuilder sb = new StringBuilder();
        if (abs) sb.append('/');
        for (int i = 0; i < leadingUp; i++) {
            sb.append(i == 0 ? "" : "/").append("..");
        }
        for (String part : out) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(part);
        }
        if (sb.length() == 0) return ".";
        return sb.toString();
    }
}
