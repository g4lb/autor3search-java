package io.github.autor3search.discover;

/**
 * Just enough Java lexing to answer two questions about a source file without
 * compiling it: which package and type it declares, and which of its methods
 * carry {@code @Benchmark}.
 *
 * <p>Not a parser, deliberately. Discovery has to work on a tree that does not
 * build — {@code init} runs before anything is compiled, and a repository whose
 * benchmarks can be found only after a successful build would be undiscoverable
 * exactly when a human most needs to know what the tool can see. Comments,
 * string literals, character literals and text blocks are stripped first, so an
 * {@code "@Benchmark"} inside a string is not mistaken for one in code.
 */
final class JavaSource {
    private JavaSource() {}

    /**
     * Returns src with every comment, string literal, character literal and text
     * block replaced by spaces of the same length, so byte offsets into the
     * result still index the original.
     */
    static String strip(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) {
                    if (out[i] != '\n') out[i] = ' ';
                }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                for (; i < end; i++) {
                    if (out[i] != '\n') out[i] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        i++;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The declared package, or "" for the default package. */
    static String packageOf(String stripped) {
        int i = indexOfKeyword(stripped, "package", 0);
        if (i < 0) return "";
        int end = stripped.indexOf(';', i);
        if (end < 0) return "";
        return stripped.substring(i + "package".length(), end).trim().replaceAll("\\s+", "");
    }

    /** Reports whether the keyword occurs at index i as a whole word. */
    private static int indexOfKeyword(String s, String kw, int from) {
        int i = from;
        while ((i = s.indexOf(kw, i)) >= 0) {
            boolean beforeOk = i == 0 || !isIdentPart(s.charAt(i - 1));
            int after = i + kw.length();
            boolean afterOk = after >= s.length() || !isIdentPart(s.charAt(after));
            if (beforeOk && afterOk) return i;
            i = after;
        }
        return -1;
    }

    static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /**
     * Reads the method name that follows an annotation at {@code from},
     * skipping any further annotations (with their argument lists), the
     * modifiers, the type parameters and the return type. Returns null when no
     * method declaration follows, which is what a {@code @Benchmark} on
     * something that is not a method looks like.
     */
    static String methodNameAfter(String s, int from) {
        int i = from;
        int n = s.length();
        while (true) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n || s.charAt(i) != '@') break;
            i++; // '@'
            while (i < n && (isIdentPart(s.charAt(i)) || s.charAt(i) == '.')) i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i < n && s.charAt(i) == '(') {
                i = skipBalanced(s, i, '(', ')');
            }
        }
        int open = s.indexOf('(', i);
        if (open < 0) return null;
        // A '{' or ';' before the '(' means what followed the annotation was not
        // a method declaration at all (a field, or the class body's own brace).
        for (int k = i; k < open; k++) {
            char c = s.charAt(k);
            if (c == '{' || c == ';' || c == '=') return null;
        }
        int end = open;
        while (end > i && Character.isWhitespace(s.charAt(end - 1))) end--;
        int start = end;
        while (start > i && isIdentPart(s.charAt(start - 1))) start--;
        if (start == end) return null;
        return s.substring(start, end);
    }

    /** Returns the index just past the balanced close of the group starting at i. */
    static int skipBalanced(String s, int i, char open, char close) {
        int depth = 0;
        int n = s.length();
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return n;
    }
}
