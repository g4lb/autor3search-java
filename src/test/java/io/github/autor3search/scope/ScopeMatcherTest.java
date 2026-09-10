package io.github.autor3search.scope;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeMatcherTest {

    @Test
    void recursivePatternMatchesEverythingBeneathIt() {
        ScopeMatcher m = new ScopeMatcher(List.of("./src/main/java/..."));
        assertTrue(m.match("src/main/java/A.java"));
        assertTrue(m.match("src/main/java/com/example/deep/A.java"));
        assertFalse(m.match("src/test/java/A.java"));
        assertFalse(m.match("pom.xml"));
    }

    @Test
    void nonRecursivePatternMatchesOnlyItsOwnDirectory() {
        ScopeMatcher m = new ScopeMatcher(List.of("./src/main/java/com/example"));
        assertTrue(m.match("src/main/java/com/example/A.java"));
        assertFalse(m.match("src/main/java/com/example/deep/A.java"));
    }

    @Test
    void rootRecursiveMatchesTheWholeRepository() {
        ScopeMatcher m = new ScopeMatcher(List.of("./..."));
        assertTrue(m.match("pom.xml"));
        assertTrue(m.match("src/main/java/A.java"));
    }

    /**
     * The default "./..." compiles to a recursive rule with an empty prefix, which
     * matches every path handed to it — so without an explicit rejection, the one
     * pattern meaning "the whole repository" would also mean "anywhere on the
     * disk".
     */
    @Test
    void neverMatchesAPathThatEscapesTheRoot() {
        ScopeMatcher m = new ScopeMatcher(List.of("./..."));
        assertFalse(m.match("../outside.java"));
        assertFalse(m.match("../../etc/passwd"));
        assertFalse(m.match("/etc/passwd"));
        assertFalse(m.match("a/../../outside.java"));
    }

    /**
     * A blank entry matches nothing rather than being read as the repository root.
     * A stray empty item in a YAML list must not silently widen the scope.
     */
    @Test
    void blankPatternsAreSkippedNotTreatedAsRoot() {
        ScopeMatcher m = new ScopeMatcher(Arrays.asList("", "   "));
        assertFalse(m.match("A.java"));
        assertFalse(m.match("src/main/java/A.java"));
    }

    @Test
    void handlesWindowsSeparatorsInTheCandidate() {
        ScopeMatcher m = new ScopeMatcher(List.of("./src/main/java/..."));
        assertTrue(m.match("src\\main\\java\\A.java"));
    }

    @Test
    void emptyPatternListMatchesNothing() {
        assertFalse(new ScopeMatcher(List.of()).match("A.java"));
        assertFalse(new ScopeMatcher(null).match("A.java"));
    }
}
