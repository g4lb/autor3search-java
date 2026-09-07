package io.github.g4lb.autor3search.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reports which build of the harness is running.
 *
 * <p>Worth having because a {@code results.tsv} row is only as reproducible as
 * the binary that produced it: "which version measured this" is otherwise
 * unanswerable from an installed jar.
 */
public final class VersionCommand {
    private VersionCommand() {}

    public static int run(String[] args) {
        Flags f = new Flags("version");
        if (!f.parse(args)) return ExitCodes.USAGE;
        System.out.println(describe());
        return ExitCodes.OK;
    }

    static String describe() {
        Properties props = new Properties();
        try (InputStream in = VersionCommand.class.getResourceAsStream("/autor3search-build.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            // A build without the stamp still runs; it just cannot name itself.
        }
        String version = props.getProperty("version", "");
        String commit = props.getProperty("commit", "");
        StringBuilder sb = new StringBuilder("autor3search-java ");
        sb.append(version.isEmpty() ? "(version unknown)" : version);
        // A build stamped with its commit says so, because the version alone does
        // not identify a build made between releases.
        if (!commit.isEmpty() && !commit.equals("unknown") && !commit.startsWith("$")) {
            sb.append(" (").append(commit).append(')');
        }
        sb.append("\nrunning on ").append(System.getProperty("java.vm.name"))
                .append(' ').append(System.getProperty("java.version"))
                .append(", ").append(System.getProperty("os.name"))
                .append('/').append(System.getProperty("os.arch"));
        return sb.toString();
    }
}
