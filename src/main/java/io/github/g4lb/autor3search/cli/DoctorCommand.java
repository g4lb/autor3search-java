package io.github.g4lb.autor3search.cli;

import io.github.g4lb.autor3search.doctor.Doctor;

import java.nio.file.Path;

/** Prints whether this machine can measure reliably. Informational: always exits 0. */
public final class DoctorCommand {
    private DoctorCommand() {}

    public static int run(String[] args) {
        Flags f = new Flags("doctor");
        f.string("C", ".", "repository root (or a directory inside it)");
        if (!f.parse(args)) return ExitCodes.USAGE;

        for (Doctor.Finding finding : Doctor.check(Path.of(f.get("C")))) {
            System.out.println(symbol(finding.severity()) + " " + finding.name() + ": " + finding.detail());
        }
        // Always 0: doctor reports, it does not decide. A human reads it and makes
        // the call about whether this machine is fit to measure on tonight.
        return ExitCodes.OK;
    }

    private static String symbol(Doctor.Severity severity) {
        return switch (severity) {
            case OK -> "✓";
            case WARN -> "⚠";
            case FAIL -> "✗";
            case NOT_APPLICABLE -> "–";
        };
    }
}
