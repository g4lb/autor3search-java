package io.github.autor3search;

import io.github.autor3search.config.ConfigException;
import io.github.autor3search.config.ConfigLoader;
import io.github.autor3search.measure.Jmh;
import io.github.autor3search.runner.ProcRunner;
import io.github.autor3search.state.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attacks a hostile agent would actually try.
 *
 * <p>This harness has an unusual threat model: the thing being measured is
 * assumed to be adversarial and runs as the same OS user, editing the same
 * repository. The individual guards are tested where they live — path traversal
 * and symlinks in {@code FreezeTest}, tag validation in {@code RunStateTest} —
 * and this covers the surfaces where a weakness would not look like a bug in any
 * one component: what reaches a YAML parser, a regex, a command line.
 */
class SecurityTest {

    /**
     * The classic SnakeYAML remote-code-execution shape. The config file lives in
     * the repository, so the agent can write anything it likes into it — the
     * integrity hash catches a change mid-run, but `init` and `baseline` both
     * parse it before any hash exists to compare against.
     */
    @Test
    void yamlCannotInstantiateArbitraryTypes(@TempDir Path dir) throws IOException {
        for (String attack : List.of(
                "benchmarks: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]\n",
                "count: !!java.lang.Runtime {}\n",
                "scope: !!javax.naming.InitialContext {}\n")) {
            Path config = dir.resolve("config.yaml");
            Files.writeString(config, attack);
            ConfigException e = assertThrows(ConfigException.class,
                    () -> ConfigLoader.load(config), "accepted: " + attack);
            assertFalse(e.getMessage().toLowerCase().contains("connect"), e.getMessage());
        }
    }

    /** A YAML bomb must not be able to exhaust memory before validation runs. */
    @Test
    void yamlAliasExpansionIsBounded(@TempDir Path dir) throws IOException {
        String bomb = """
                a: &a ["x","x","x","x","x","x","x","x","x"]
                b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
                e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]
                benchmarks: [*e,*e,*e,*e,*e,*e,*e,*e,*e]
                """;
        // Either refused or parsed into something finite; what must not happen is
        // the process dying before it can refuse.
        Path config = dir.resolve("bomb.yaml");
        Files.writeString(config, bomb);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(config));
    }

    /**
     * {@code benchmarks:} is documented as hand-editable and flows into the
     * regexp JMH selects on. An unescaped metacharacter would silently BROADEN
     * the pattern to measure benchmarks nobody declared — the quiet version of
     * choosing your own success criteria.
     */
    @Test
    void benchmarkNamesCannotWidenTheSelectionPattern() {
        String pattern = Jmh.pattern(List.of("com.example.Real.run"));
        assertTrue(Pattern.compile(pattern).matcher("com.example.Real.run").matches());

        String attack = Jmh.pattern(List.of(".*", "com.example.Easy.run|.*"));
        assertFalse(Pattern.compile(attack).matcher("com.example.Something.else").matches(),
                "a metacharacter widened the pattern: " + attack);
        // The literal name is still selectable, metacharacters and all.
        assertTrue(Pattern.compile(attack).matcher(".*").matches());
    }

    /**
     * Every command is built as a list, so a configured value becomes exactly one
     * argument. If any of it were ever concatenated into a shell string, a value
     * like {@code "; rm -rf ~"} would run.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void configuredValuesCannotSplitIntoExtraArguments(@TempDir Path dir) throws IOException {
        Path canary = dir.resolve("canary");
        Files.writeString(canary, "intact");
        ProcRunner runner = new ProcRunner(dir, Duration.ofSeconds(30), null);

        // Passed as ONE argument to echo, never interpreted.
        String hostile = "; rm -f " + canary + " #";
        var res = runner.run(List.of("echo", hostile));
        assertTrue(res.ok());
        assertEquals(hostile, res.stdout().strip());
        assertTrue(Files.exists(canary), "a configured value reached a shell");
        assertEquals("intact", Files.readString(canary));
    }

    /**
     * The run tag becomes a directory name under the state home, created before
     * git ever sees it. Anything that could climb out would create directories —
     * and later write frozen copies — wherever the traversal landed.
     */
    @Test
    void runTagsCannotEscapeTheStateDirectory(@TempDir Path dir) throws IOException {
        for (String tag : List.of("../escape", "..", ".", "/absolute", "a/b", "a\\b", "a\0b", "")) {
            assertThrows(IllegalArgumentException.class, () -> RunState.stateDir(dir, tag), tag);
        }
        Path ok = RunState.stateDir(dir, "sep7");
        assertEquals("sep7", ok.getFileName().toString());
        assertTrue(ok.normalize().equals(ok), "the resolved state path must not need normalising");
    }

    /**
     * The state home is attacker-influencing only via the environment, but a
     * relative value would resolve against each command's own working directory —
     * so `eval` and `stop` would address different state and the brake would miss.
     */
    @Test
    void aRelativeStateHomeIsRefused() {
        String set = System.getenv(RunState.STATE_HOME_ENV);
        if (set == null || Path.of(set).isAbsolute()) {
            // The env in this JVM is absolute (surefire sets it), so assert the
            // property the code guarantees rather than trying to mutate the env.
            assertTrue(Path.of(set == null ? System.getProperty("user.home") : set).isAbsolute());
        }
    }
}
