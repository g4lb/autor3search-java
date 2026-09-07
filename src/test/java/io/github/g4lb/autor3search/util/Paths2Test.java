package io.github.g4lb.autor3search.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Paths2Test {

    @Test
    void collapsesRedundantComponents() {
        assertEquals("a/b", Paths2.clean("a//b"));
        assertEquals("a/b", Paths2.clean("./a/b"));
        assertEquals("a/b", Paths2.clean("a/./b"));
        assertEquals("a", Paths2.clean("a/b/.."));
        assertEquals(".", Paths2.clean(""));
        assertEquals(".", Paths2.clean("."));
    }

    /**
     * A leading ".." must SURVIVE cleaning. Callers reject a path that still
     * climbs out of the root; resolving it away here would hand them a path that
     * looks perfectly ordinary and lands outside the repository.
     */
    @Test
    void keepsALeadingParentReference() {
        assertEquals("../x", Paths2.clean("../x"));
        assertEquals("..", Paths2.clean(".."));
        assertEquals("../..", Paths2.clean("../../"));
        assertEquals("../x", Paths2.clean("a/../../x"));
    }

    @Test
    void normalisesWindowsSeparators() {
        assertEquals("a/b/c.java", Paths2.clean("a\\b\\c.java"));
    }
}
