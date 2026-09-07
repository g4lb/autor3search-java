package io.github.g4lb.autor3search.scope;

import io.github.g4lb.autor3search.util.Paths2;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which files the agent is allowed to modify, from a list of Go-style
 * path patterns ("./...", "./src/main/java/...", "./src/main/java/com/example").
 */
public final class ScopeMatcher {

    /** One compiled pattern: a normalized directory prefix, "" meaning the repository root. */
    private record Rule(String prefix, boolean recursive) {}

    private final List<Rule> rules = new ArrayList<>();

    /**
     * Compiles patterns. An empty or whitespace-only pattern is SKIPPED rather
     * than treated as the repository root, so a stray blank entry in a config
     * list matches nothing instead of silently granting root-level access.
     */
    public ScopeMatcher(List<String> patterns) {
        if (patterns == null) return;
        for (String raw : patterns) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            String p = Paths2.clean(stripPrefix(trimmed, "./"));
            boolean recursive = false;
            if (p.equals("...")) {
                p = "";
                recursive = true;
            } else if (p.endsWith("/...")) {
                p = p.substring(0, p.length() - "/...".length());
                recursive = true;
            }
            if (p.equals(".")) p = "";
            rules.add(new Rule(p, recursive));
        }
    }

    /**
     * Reports whether rel — a slash-separated path relative to the repository
     * root — is inside the allowed scope.
     *
     * <p>An absolute path, or one that climbs out of the root, is never in scope
     * however permissive the patterns are, and that has to be rejected
     * explicitly because it would otherwise be ADMITTED: cleaning leaves a
     * leading ".." in place, and the default "./..." pattern compiles to a
     * recursive rule with an empty prefix, which matches every path handed to
     * it. The one pattern meaning "the whole repository" would have been the one
     * meaning "anywhere on the disk".
     *
     * <p>Nothing produces such a path today — callers pass the output of
     * {@code git diff --name-only} and {@code git ls-files}, which are always
     * root-relative — so this is the gate refusing to depend on that staying true.
     */
    public boolean match(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        String s = rel.replace('\\', '/');
        if (s.startsWith("/") || isWindowsAbsolute(s)) return false;
        s = Paths2.clean(stripPrefix(s, "./"));
        if (s.equals("..") || s.startsWith("../")) return false;
        String dir = parent(s);
        for (Rule r : rules) {
            if (r.recursive()) {
                if (r.prefix().isEmpty() || s.equals(r.prefix()) || s.startsWith(r.prefix() + "/")) {
                    return true;
                }
                continue;
            }
            if (dir.equals(r.prefix())) return true;
        }
        return false;
    }

    /** The directory containing s, "" for a file at the repository root. */
    private static String parent(String s) {
        int i = s.lastIndexOf('/');
        return i < 0 ? "" : s.substring(0, i);
    }

    private static boolean isWindowsAbsolute(String s) {
        return s.length() >= 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && s.charAt(2) == '/';
    }

    private static String stripPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}
