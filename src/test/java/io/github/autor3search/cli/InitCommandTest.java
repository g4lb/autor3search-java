package io.github.autor3search.cli;

import io.github.autor3search.TestRepo;
import io.github.autor3search.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitCommandTest {

    private static TestRepo mavenRepoWithABenchmark(Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("repo"));
        r.write("pom.xml", "<project/>\n");
        r.write("src/main/java/demo/WordCount.java", "package demo;\npublic class WordCount {}\n");
        r.write("src/test/java/demo/WordCountBenchmark.java", """
                package demo;
                import org.openjdk.jmh.annotations.Benchmark;
                public class WordCountBenchmark {
                    @Benchmark
                    public int count() { return 1; }
                }
                """);
        r.commit("initial");
        return r;
    }

    @Test
    void writesTheConfigAndProgramAndReportsWhatItFound(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertEquals(ExitCodes.OK, out.code(), out.err());
        assertTrue(out.out().contains("demo.WordCountBenchmark.count"), out.out());
        assertTrue(out.out().contains("maven"), out.out());

        Path config = r.root().resolve(Config.PATH);
        assertTrue(Files.exists(config));
        assertTrue(Files.readString(config).contains("demo.WordCountBenchmark.count"));
        assertTrue(Files.exists(r.root().resolve("program.md")));
    }

    /**
     * The tool optimizes what it can measure, and refuses to write a config with an
     * empty benchmark list that would silently optimize nothing.
     */
    @Test
    void refusesARepositoryWithNoBenchmarks(@TempDir Path dir) throws IOException {
        TestRepo r = TestRepo.init(dir.resolve("repo"));
        r.write("pom.xml", "<project/>\n");
        r.write("src/main/java/demo/A.java", "package demo;\npublic class A {}\n").commit("initial");

        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertEquals(ExitCodes.USAGE, out.code());
        assertTrue(out.err().contains("no JMH benchmarks found"), out.err());
        assertFalse(Files.exists(r.root().resolve(Config.PATH)));
    }

    @Test
    void refusesToOverwriteAnExistingConfigWithoutForce(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        Path config = r.root().resolve(Config.PATH);
        Files.writeString(config, "count: 42\n", StandardCharsets.UTF_8);

        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertEquals(ExitCodes.USAGE, out.code());
        assertTrue(out.err().contains("-force"), out.err());
        assertEquals("count: 42\n", Files.readString(config), "the existing config must survive");

        Capture.Output forced = Capture.run(
                () -> InitCommand.run(new String[]{"-C", r.root().toString(), "-force"}));
        assertEquals(ExitCodes.OK, forced.code(), forced.err());
        assertTrue(Files.readString(config).contains("count: 10"));
    }

    /**
     * git cannot re-include a file whose parent DIRECTORY is excluded, so the
     * config is un-ignored by excluding the directory's contents rather than the
     * directory itself.
     */
    @Test
    void gitignoreKeepsTheConfigTrackedAndTheRestIgnored(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        List<String> lines = Files.readAllLines(r.root().resolve(".gitignore"));
        assertTrue(lines.contains(".autor3search/*"), lines.toString());
        assertTrue(lines.contains("!.autor3search/config.yaml"), lines.toString());
        assertFalse(lines.contains(".autor3search/"), "excluding the directory would defeat the negation");
        assertTrue(lines.contains("results.tsv"));
        assertTrue(lines.contains("run.log"));
    }

    @Test
    void gitignoreEntriesAreNeverDuplicated(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        r.write(".gitignore", "target/\nresults.tsv\n");
        InitCommand.ensureGitignore(r.root(), InitCommand.GITIGNORE_ENTRIES);
        InitCommand.ensureGitignore(r.root(), InitCommand.GITIGNORE_ENTRIES);
        List<String> lines = Files.readAllLines(r.root().resolve(".gitignore"));
        assertEquals(1, lines.stream().filter(l -> l.equals("results.tsv")).count(), lines.toString());
        assertEquals(1, lines.stream().filter(l -> l.equals("run.log")).count(), lines.toString());
        assertTrue(lines.contains("target/"), "existing entries must survive");
    }

    @Test
    void appendsToAGitignoreWithNoTrailingNewline(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Files.writeString(r.root().resolve(".gitignore"), "target/", StandardCharsets.UTF_8);
        InitCommand.ensureGitignore(r.root(), InitCommand.GITIGNORE_ENTRIES);
        List<String> lines = Files.readAllLines(r.root().resolve(".gitignore"));
        assertEquals("target/", lines.get(0));
        assertTrue(lines.contains("results.tsv"));
    }

    /**
     * A repository with both a pom.xml and a Gradle build cannot be detected, and
     * this is common in the wild — a library that publishes to Maven Central but
     * builds with Gradle keeps both. Pointing the user at a config file `init`
     * has not written yet is advice they cannot act on, so the flag has to exist
     * and the message has to name it.
     */
    @Test
    void ambiguousBuildToolIsResolvableByFlagNotOnlyByConfig(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        r.write("build.gradle", "plugins { id 'java' }\n").commit("also a gradle build");

        Capture.Output ambiguous = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertEquals(ExitCodes.USAGE, ambiguous.code());
        assertTrue(ambiguous.err().contains("-build-tool"), ambiguous.err());
        assertFalse(Files.exists(r.root().resolve(Config.PATH)));

        Capture.Output resolved = Capture.run(() -> InitCommand.run(
                new String[]{"-C", r.root().toString(), "-build-tool", "gradle"}));
        assertEquals(ExitCodes.OK, resolved.code(), resolved.err());
        assertTrue(Files.readString(r.root().resolve(Config.PATH)).contains("build_tool: gradle"));
    }

    @Test
    void refusesAnUnknownBuildToolFlag(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Capture.Output out = Capture.run(() -> InitCommand.run(
                new String[]{"-C", r.root().toString(), "-build-tool", "bazel"}));
        assertEquals(ExitCodes.USAGE, out.code());
        assertTrue(out.err().contains("build_tool must be one of"), out.err());
    }

    /**
     * program.md has no licence header, so a build that checks for one fails on
     * the first eval after init. Caught here only when the project declares the
     * plugin itself — a parent pom can enable it invisibly, which is why the real
     * safety net is the diagnosis attached to the failure.
     */
    @Test
    void notesALicenceCheckThatWouldFailOnProgramMd(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        r.write("pom.xml", """
                <project><build><plugins><plugin>
                  <artifactId>apache-rat-plugin</artifactId>
                </plugin></plugins></build></project>
                """).commit("enforce licence headers");

        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertEquals(ExitCodes.OK, out.code(), out.err());
        assertTrue(out.out().contains("apache-rat-plugin"), out.out());
        assertTrue(out.out().contains("program.md"), out.out());
    }

    @Test
    void saysNothingAboutLicencesWhenTheBuildDoesNotCheckThem(@TempDir Path dir) throws IOException {
        TestRepo r = mavenRepoWithABenchmark(dir);
        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", r.root().toString()}));
        assertFalse(out.out().contains("licence"), out.out());
    }

    @Test
    void refusesADirectoryThatIsNotAGitRepository(@TempDir Path dir) {
        Capture.Output out = Capture.run(() -> InitCommand.run(new String[]{"-C", dir.toString()}));
        assertEquals(ExitCodes.USAGE, out.code());
    }

    /** The instruction file the agent follows has to be in the jar it ships in. */
    @Test
    void shipsTheProgramTemplate() throws IOException {
        String program = InitCommand.programTemplate();
        assertTrue(program.startsWith("# program.md"), program.substring(0, 40));
        assertTrue(program.contains("autor3search-java eval --json"));
        assertTrue(program.contains("stop_requested"));
        assertTrue(program.contains("NEVER STOP ON YOUR OWN"));
    }

    @Test
    void suggestsATagFromTodaysDate() {
        String tag = InitCommand.defaultTag();
        io.github.autor3search.state.RunState.validateTag(tag);
        assertTrue(tag.matches("[a-z]{3}\\d{1,2}"), tag);
    }
}
