package io.github.g4lb.autor3search.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Runs something with stdout and stderr redirected, and hands back what it printed. */
public final class Capture {

    public record Output(String out, String err, int code) {}

    private Capture() {}

    public static Output run(java.util.function.Supplier<Integer> body) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = body.get();
            System.out.flush();
            System.err.flush();
            return new Output(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
