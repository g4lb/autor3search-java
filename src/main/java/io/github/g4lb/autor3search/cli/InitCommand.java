package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.build.BuildTool;
import io.github.g4lb.autor3search.build.BuildTools;
import io.github.g4lb.autor3search.config.Config;
import io.github.g4lb.autor3search.config.ConfigRenderer;
import io.github.g4lb.autor3search.discover.Benchmark;
import io.github.g4lb.autor3search.discover.Discovery;
import io.github.g4lb.autor3search.git.Git;
import io.github.g4lb.autor3search.pipeline.Pipeline;
import io.github.g4lb.autor3search.results.Results;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans a repository, discovers its benchmarks, and writes the two files
 * everything else assumes are there: the run configuration and {@code program.md}.
 */
public final class InitCommand {
    private InitCommand() {}

    /**
     * The lines {@code init} adds to .gitignore.
     *
     * <p>{@code .autor3search/*} ignores everything the harness writes under that
     * directory, while the negation re-includes the config — the one file there
     * that is meant to be version-controlled, since humans own it and want their
     * edits tracked. Note the {@code /*}: git cannot re-include a file whose
     * parent DIRECTORY is excluded, so {@code .autor3search/} followed by a
     * negation would silently fail to un-ignore it.
     */
    static final List<String> GITIGNORE_ENTRIES = List.of(
            ".autor3search/*", "!.autor3search/config.yaml", "results.tsv", "run.log");

    public static int run(String[] args) {
        Flags f = new Flags("init");
        f.string("C", ".", "repository root (or a directory inside it)");
        f.bool("force", false, "overwrite an existing " + Config.PATH);
        // Needed before the config exists: a repository carrying both a pom.xml and
        // a Gradle build cannot be detected, and pointing the user at a config file
        // this command has not written yet is advice they cannot act on.
        f.string("build-tool", "auto", "auto, maven or gradle");
        if (!f.parse(args)) return ExitCodes.USAGE;

        try {
            Path root = Git.root(Path.of(f.get("C")));
            Path configPath = root.resolve(Config.PATH);
            if (Files.exists(configPath) && !f.flag("force")) {
                System.err.println("autor3search-java init: " + configPath
                        + " already exists; pass -force to overwrite");
                return ExitCodes.USAGE;
            }

            List<Benchmark> benches = Discovery.benchmarks(root);
            if (benches.isEmpty()) {
                System.err.println("autor3search-java init: no JMH benchmarks found. autor3search-java"
                        + " optimizes what it can measure — add a @Benchmark method first, or see the"
                        + " README section \"Repos with no benchmarks\".");
                return ExitCodes.USAGE;
            }
            List<String> names = Discovery.names(benches);

            Config cfg = Config.defaults();
            cfg.benchmarks = names;
            cfg.buildTool = f.get("build-tool");
            try {
                cfg.validate();
            } catch (io.github.g4lb.autor3search.config.ConfigException e) {
                System.err.println("autor3search-java init: " + e.getMessage());
                return ExitCodes.USAGE;
            }
            // Detected here so a repository the harness cannot drive says so now,
            // rather than at 3am when the agent has already committed something.
            BuildTool tool = BuildTools.detect(root, cfg.buildTool, benches);

            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, ConfigRenderer.render(cfg), StandardCharsets.UTF_8);

            Path programPath = root.resolve("program.md");
            Files.writeString(programPath, programTemplate(), StandardCharsets.UTF_8);

            ensureGitignore(root, GITIGNORE_ENTRIES);

            printSummary(names, benches, tool, configPath, programPath);
            warnAboutLicenceChecks(root);
            return ExitCodes.OK;
        } catch (IOException e) {
            System.err.println("autor3search-java init: " + e.getMessage());
            return ExitCodes.USAGE;
        }
    }

    /** The agent instruction file written into a repository, shipped inside the jar. */
    public static String programTemplate() throws IOException {
        try (InputStream in = InitCommand.class.getResourceAsStream("/program.md")) {
            if (in == null) {
                throw new IOException("program.md is missing from this build of autor3search-java");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Appends whichever entries are not already a line in root's .gitignore,
     * creating the file if necessary. Never duplicates a line already there.
     */
    static void ensureGitignore(Path root, List<String> entries) throws IOException {
        Path path = root.resolve(".gitignore");
        Set<String> existing = new LinkedHashSet<>();
        StringBuilder content = new StringBuilder();
        if (Files.exists(path)) {
            content.append(Files.readString(path, StandardCharsets.UTF_8));
            for (String line : content.toString().split("\n", -1)) {
                existing.add(line.trim());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String e : entries) {
            if (!existing.contains(e)) missing.add(e);
        }
        if (missing.isEmpty()) return;
        if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }
        for (String e : missing) {
            content.append(e).append('\n');
        }
        Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
    }

    private static void printSummary(List<String> names, List<Benchmark> benches, BuildTool tool,
                                     Path configPath, Path programPath) {
        System.out.println("autor3search-java init: found " + names.size() + " benchmark(s):");
        for (String n : names) {
            System.out.println("  - " + n);
        }
        System.out.println("\nbuild:  " + tool.describe());
        System.out.println("\nwrote:");
        System.out.println("  " + configPath);
        System.out.println("  " + programPath);
        System.out.println("\nnext:");
        System.out.println("  git add -A && git commit -m \"autor3search-java init\"");
        System.out.println("  autor3search-java doctor");
        System.out.println("  autor3search-java baseline -tag " + defaultTag());
    }

    /** Plugin names that enforce a licence header on every file in the tree. */
    private static final List<String> LICENCE_PLUGINS =
            List.of("apache-rat-plugin", "license-maven-plugin", "licence-maven-plugin");

    /**
     * Warns when the build enforces licence headers, because {@code program.md}
     * has none and the first {@code eval} will fail on it.
     *
     * <p>Only the project's own build files are read, so this sees the common
     * case — commons-codec, for one, names apache-rat in its own pom — but not a
     * check a parent pom configures entirely on the project's behalf. Resolving
     * the parent chain would mean an effective-pom run, which costs seconds and
     * needs a build that already works. The cheap check runs here; the reliable
     * one is the diagnosis attached to the failure itself, which sees whatever
     * actually happened.
     */
    static void warnAboutLicenceChecks(Path root) {
        for (String file : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path p = root.resolve(file);
            if (!Files.isRegularFile(p)) continue;
            String text;
            try {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue; // an unreadable build file is the build tool's problem
            }
            String found = LICENCE_PLUGINS.stream().filter(text::contains).findFirst().orElse(null);
            if (found == null) continue;
            System.out.println();
            System.out.println("note: this build runs " + found + ", which checks every file for a licence");
            System.out.println("      header. program.md has none, so exclude the harness's files from that");
            System.out.println("      check before running baseline:");
            System.out.println("        program.md, " + Results.PATH + ", " + Pipeline.RUN_LOG_NAME
                    + ", .autor3search/**");
            return;
        }
    }

    /** A run tag suggested from today's date, e.g. "sep7". */
    public static String defaultTag() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("MMMd", Locale.ENGLISH)).toLowerCase(Locale.ROOT);
    }
}
