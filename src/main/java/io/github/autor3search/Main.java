package io.github.autor3search;

import io.github.autor3search.cli.BaselineCommand;
import io.github.autor3search.cli.DoctorCommand;
import io.github.autor3search.cli.EvalCommand;
import io.github.autor3search.cli.ExitCodes;
import io.github.autor3search.cli.InitCommand;
import io.github.autor3search.cli.ProfileCommand;
import io.github.autor3search.cli.ReportCommand;
import io.github.autor3search.cli.StatusCommand;
import io.github.autor3search.cli.StopCommand;
import io.github.autor3search.cli.VersionCommand;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A frozen measurement harness that lets an AI coding agent autonomously
 * optimize a Java repository.
 *
 * <p>The harness never edits source code. It gates correctness, measures the
 * candidate against the baseline, and returns a verdict.
 */
public final class Main {

    /** One subcommand. {@code args} excludes the subcommand name itself. */
    private record Command(String summary, Function<String[], Integer> run) {}

    private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("init", new Command(
                "scan the repo, discover benchmarks, write config and program.md", InitCommand::run));
        COMMANDS.put("doctor", new Command(
                "check whether this machine can measure reliably", DoctorCommand::run));
        COMMANDS.put("baseline", new Command(
                "create the run branch, freeze sources, record the baseline", BaselineCommand::run));
        COMMANDS.put("profile", new Command(
                "profile the declared benchmarks and report hot spots", ProfileCommand::run));
        COMMANDS.put("eval", new Command(
                "run one experiment step and return a verdict", EvalCommand::run));
        COMMANDS.put("status", new Command(
                "show where the run is: branch, worktree, experiments, stop state", StatusCommand::run));
        COMMANDS.put("stop", new Command(
                "ask the agent to end the run after the current experiment", StopCommand::run));
        COMMANDS.put("report", new Command("summarize results.tsv", ReportCommand::run));
        COMMANDS.put("version", new Command("print which build of the harness this is", VersionCommand::run));
    }

    private Main() {}

    public static void main(String[] args) {
        System.exit(dispatch(args));
    }

    static int dispatch(String[] args) {
        if (args.length == 0) {
            usage();
            return ExitCodes.USAGE;
        }
        Command cmd = COMMANDS.get(args[0]);
        if (cmd == null) {
            System.err.println("unknown command \"" + args[0] + "\"\n");
            usage();
            return ExitCodes.USAGE;
        }
        try {
            return cmd.run().apply(Arrays.copyOfRange(args, 1, args.length));
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // A bug in the harness must never reach the agent's loop wearing a
            // verdict's exit code. Letting one escape would exit 1 — which the loop
            // reads as DISCARD, so it would drop a perfectly good commit and carry on
            // as if the harness had decided something. Exit 64 instead, which is a
            // code no verdict uses.
            System.err.println("autor3search-java " + args[0] + ": internal error: " + describe(e));
            e.printStackTrace(System.err);
            System.err.println("\nThis is a bug in autor3search-java, not a verdict on your change."
                    + "\nPlease report it: https://github.com/autor3search/java/issues");
            return ExitCodes.USAGE;
        }
    }

    /** Names a throwable usefully even when it carries no message, as an NPE does. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getName() : message;
    }

    private static void usage() {
        System.err.println("autor3search-java — autonomous Java performance optimization harness");
        System.err.println("\nusage: autor3search-java <command> [flags]\n\ncommands:");
        COMMANDS.forEach((name, cmd) -> System.err.printf("  %-9s %s%n", name, cmd.summary()));
    }
}
