package io.github.autor3search.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure this exists for looks exactly like a broken build and is not one.
 * An agent told "the build failed" will start fixing code that was never wrong.
 */
class BuildDiagnosticsTest {

    /** The real output from commons-codec, whose Apache parent enables apache-rat. */
    private static final String RAT_FAILURE = """
            [INFO] --- apache-rat:0.18:check (rat-check) @ commons-codec ---
            [ERROR] Unexpected count for UNAPPROVED, limit is [0,0].  Count: 1
            [ERROR] Failed to execute goal org.apache.rat:apache-rat-plugin:0.18:check (rat-check)
            on project commons-codec: Counter(s) UNAPPROVED exceeded minimum or maximum values.
            See RAT report in: '/tmp/commons-codec/target/rat.txt'.
              /program.md
            """;

    @Test
    void recognisesALicenceCheckAndNamesTheHarnessFile() {
        String hint = BuildDiagnostics.explain(RAT_FAILURE);
        assertNotNull(hint, "a rat failure went unexplained");
        assertTrue(hint.contains("not a problem with the change under test"), hint);
        assertTrue(hint.contains("program.md"), hint);
        assertTrue(hint.contains("<exclude>"), hint);
        // The fix touches build files, which may not be edited during a run.
        assertTrue(hint.contains("before `baseline`"), hint);
    }

    @Test
    void recognisesTheOtherCommonLicencePlugin() {
        assertNotNull(BuildDiagnostics.explain(
                "[ERROR] Failed to execute goal com.mycila:license-maven-plugin:4.5:check"));
        assertNotNull(BuildDiagnostics.explain("Some files do not have the expected license header"));
    }

    /**
     * An ordinary compile or test failure must pass through untouched. Attaching
     * licence advice to a genuine build break would be worse than saying nothing.
     */
    @Test
    void saysNothingAboutAnOrdinaryFailure() {
        assertNull(BuildDiagnostics.explain("""
                [ERROR] /src/main/java/demo/WordCount.java:[12,9] cannot find symbol
                  symbol:   variable counts
                [ERROR] BUILD FAILURE
                """));
        assertNull(BuildDiagnostics.explain("Tests run: 4, Failures: 1 -- WordCountTest.stripsCase"));
        assertNull(BuildDiagnostics.explain(""));
        assertNull(BuildDiagnostics.explain(null));
    }

    /** A licence failure about the project's own sources is still worth explaining. */
    @Test
    void explainsALicenceFailureThatDoesNotNameAHarnessFile() {
        String hint = BuildDiagnostics.explain(
                "[ERROR] Unexpected count for UNAPPROVED, limit is [0,0].  Count: 3");
        assertNotNull(hint);
        assertTrue(hint.contains("licence-header check"), hint);
    }
}
