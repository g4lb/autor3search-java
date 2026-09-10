package io.github.autor3search.doctor;

import io.github.autor3search.build.BuildTool;
import io.github.autor3search.build.BuildTools;
import io.github.autor3search.discover.Benchmark;
import io.github.autor3search.discover.Discovery;
import io.github.autor3search.git.Git;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Checks whether this machine can measure JVM benchmarks reliably. */
public final class Doctor {
    private Doctor() {}

    /** How bad a finding is. */
    public enum Severity {
        /** The check ran and passed. */
        OK,
        /** The check ran and found something that will cost measurement quality. */
        WARN,
        /** The check ran and found something that makes measurement impossible. */
        FAIL,
        /**
         * The check did not run on this platform or in this environment. Distinct
         * from OK on purpose: "not checked" must never be reported as "fine".
         */
        NOT_APPLICABLE
    }

    /** One check's result. */
    public record Finding(String name, String detail, Severity severity) {}

    /** The smallest JDK the harness and the projects it drives are built against. */
    private static final int MIN_JAVA = 17;

    /** Runs every check against dir, a repository root or a directory inside it. */
    public static List<Finding> check(Path dir) {
        List<Finding> out = new ArrayList<>();
        out.add(checkJava());
        out.add(checkJdk());
        out.add(checkGit());
        Path root = null;
        try {
            root = Git.root(dir);
            out.add(new Finding("git repo", "working tree is a git repository", Severity.OK));
        } catch (IOException e) {
            out.add(new Finding("git repo", "not a git repository: " + e.getMessage(), Severity.FAIL));
        }
        out.add(checkBuildTool(root));
        out.add(checkCpu());
        out.add(checkLoad());
        Finding platform = checkPlatform();
        if (platform != null) out.add(platform);
        out.add(checkDisk(dir));
        return out;
    }

    private static Finding checkJava() {
        int major = Runtime.version().feature();
        if (major < MIN_JAVA) {
            return new Finding("java", "Java " + major + " is too old, need >= " + MIN_JAVA, Severity.FAIL);
        }
        return new Finding("java", System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"), Severity.OK);
    }

    /**
     * A JRE can run the harness but cannot build the project it is pointed at, and
     * the failure surfaces much later as an opaque Maven or Gradle error.
     */
    private static Finding checkJdk() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path javac = Path.of(home, "bin", windows ? "javac.exe" : "javac");
        if (!Files.isExecutable(javac)) {
            return new Finding("jdk", "no javac beside this JVM (" + home + ") — the benchmarks run on"
                    + " this runtime, but the build needs a full JDK", Severity.WARN);
        }
        return new Finding("jdk", "full JDK at " + home, Severity.OK);
    }

    private static Finding checkGit() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (p.exitValue() != 0) {
                return new Finding("git", "git not usable: " + out, Severity.FAIL);
            }
            return new Finding("git", out, Severity.OK);
        } catch (IOException e) {
            return new Finding("git", "git not found on PATH", Severity.FAIL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Finding("git", "interrupted while checking git", Severity.NOT_APPLICABLE);
        }
    }

    private static Finding checkBuildTool(Path root) {
        if (root == null) {
            return new Finding("build", "not checked (no git repository to look in)", Severity.NOT_APPLICABLE);
        }
        try {
            List<Benchmark> benches = Discovery.benchmarks(root);
            BuildTool tool = BuildTools.detect(root, configuredBuildTool(root), benches);
            if (benches.isEmpty()) {
                return new Finding("build", tool.describe() + " — but no @Benchmark methods were found;"
                        + " autor3search-java optimizes only what it can measure", Severity.WARN);
            }
            return new Finding("build", tool.describe() + ", " + benches.size() + " benchmark(s) discovered",
                    Severity.OK);
        } catch (IOException e) {
            return new Finding("build", e.getMessage(), Severity.FAIL);
        }
    }

    /**
     * The {@code build_tool} the repository has already settled on, or "auto"
     * before there is a config to read.
     *
     * <p>Re-detecting with "auto" regardless would report a hard FAIL on a
     * repository that is configured perfectly well — a library that publishes to
     * Maven Central but builds with Gradle keeps both files, and telling its owner
     * their machine cannot measure would be simply wrong.
     */
    private static String configuredBuildTool(Path root) {
        Path config = root.resolve(io.github.autor3search.config.Config.PATH);
        if (!Files.exists(config)) return "auto";
        try {
            return io.github.autor3search.config.ConfigLoader.load(config).buildTool;
        } catch (IOException | RuntimeException e) {
            // An unreadable config is the config check's problem, not this one's.
            return "auto";
        }
    }

    private static Finding checkCpu() {
        int cpus = Runtime.getRuntime().availableProcessors();
        return new Finding("cpu", cpus + " logical CPU core(s)", cpus >= 2 ? Severity.OK : Severity.WARN);
    }

    /**
     * The one-minute load average, where the platform exposes one. A machine
     * already half busy cannot produce comparable timings, and interleaving does
     * not save a run whose noise is larger than the effect it is looking for.
     */
    private static Finding checkLoad() {
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load < 0) {
            return new Finding("load", "1-minute load average: not checked (not available on "
                    + System.getProperty("os.name") + ")", Severity.NOT_APPLICABLE);
        }
        double threshold = 0.5 * Runtime.getRuntime().availableProcessors();
        return new Finding("load", String.format(Locale.ROOT,
                "1-minute load average: %.2f (threshold: %.2f)", load, threshold),
                load > threshold ? Severity.WARN : Severity.OK);
    }

    private static Finding checkPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return new Finding("darwin", "P/E core scheduling adds variance; close other applications, and"
                    + " keep the machine on mains power", Severity.WARN);
        }
        if (os.startsWith("linux")) {
            return checkGovernor(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        }
        return null;
    }

    /** Injectable for tests, which cannot rely on a real /sys on every platform. */
    static Finding checkGovernor(Path governorPath) {
        if (!Files.exists(governorPath)) {
            // Missing cpufreq is normal on VMs and containers without frequency
            // scaling; the check simply did not run, which is not a pass.
            return new Finding("cpufreq", "CPU scaling governor: not checked (cpufreq not available,"
                    + " likely a VM or container)", Severity.NOT_APPLICABLE);
        }
        try {
            String governor = Files.readString(governorPath, StandardCharsets.UTF_8).trim();
            return new Finding("cpufreq", "CPU scaling governor: " + governor,
                    governor.equals("performance") ? Severity.OK : Severity.WARN);
        } catch (IOException e) {
            return new Finding("cpufreq", "CPU scaling governor: not checked (" + e.getMessage() + ")",
                    Severity.NOT_APPLICABLE);
        }
    }

    private static Finding checkDisk(Path dir) {
        try {
            FileStore store = Files.getFileStore(dir.toAbsolutePath());
            long freeGb = store.getUsableSpace() / (1024L * 1024 * 1024);
            // The pinned baseline worktree is a second checkout, and both trees carry
            // full build output. A disk that fills mid-run fails an experiment for a
            // reason nothing in the verdict explains.
            Severity sev = freeGb < 2 ? Severity.FAIL : freeGb < 10 ? Severity.WARN : Severity.OK;
            return new Finding("disk", freeGb + " GB free on " + store.name(), sev);
        } catch (IOException e) {
            return new Finding("disk", "not checked (" + e.getMessage() + ")", Severity.NOT_APPLICABLE);
        }
    }
}
