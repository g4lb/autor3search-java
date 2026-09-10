package io.github.autor3search.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCommandTest {

    /**
     * A results.tsv row is only as reproducible as the binary that produced it, so
     * the build has to be able to name itself.
     */
    @Test
    void namesTheToolAndTheRuntimeItIsOn() {
        String out = VersionCommand.describe();
        assertTrue(out.startsWith("autor3search-java "), out);
        assertFalse(out.contains("${"), "the build stamp was not filtered: " + out);
        assertTrue(out.contains("running on"), out);
        assertTrue(out.contains(System.getProperty("os.arch")), out);
    }

    @Test
    void printsAndExitsZero() {
        Capture.Output result = Capture.run(() -> VersionCommand.run(new String[]{}));
        assertTrue(result.out().contains("autor3search-java"));
        assertTrue(result.code() == ExitCodes.OK);
    }
}
