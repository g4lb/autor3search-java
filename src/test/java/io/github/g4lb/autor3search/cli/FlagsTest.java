package io.github.g4lb.autor3search.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagsTest {

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private Flags flags() {
        return new Flags("eval", new PrintStream(err, true, StandardCharsets.UTF_8))
                .string("C", ".", "repository root")
                .string("desc", "", "description")
                .bool("json", false, "print JSON");
    }

    @Test
    void appliesDefaultsWhenNothingIsPassed() {
        Flags f = flags();
        assertTrue(f.parse(new String[]{}));
        assertEquals(".", f.get("C"));
        assertFalse(f.flag("json"));
    }

    @Test
    void readsSeparatedInlineAndDoubleDashedForms() {
        Flags f = flags();
        assertTrue(f.parse(new String[]{"-C", "/repo", "--desc=presize map", "--json"}));
        assertEquals("/repo", f.get("C"));
        assertEquals("presize map", f.get("desc"));
        assertTrue(f.flag("json"));
    }

    @Test
    void aBareBooleanIsTrueAndAnExplicitValueIsHonoured() {
        Flags on = flags();
        on.parse(new String[]{"-json"});
        assertTrue(on.flag("json"));

        Flags off = flags();
        off.parse(new String[]{"-json=false"});
        assertFalse(off.flag("json"));
    }

    @Test
    void collectsPositionalArguments() {
        Flags f = flags();
        f.parse(new String[]{"-json", "one", "two"});
        assertEquals(List.of("one", "two"), f.args());
    }

    @Test
    void everythingAfterADoubleDashIsPositional() {
        Flags f = flags();
        f.parse(new String[]{"--", "-json", "-C"});
        assertEquals(List.of("-json", "-C"), f.args());
        assertFalse(f.flag("json"));
    }

    @Test
    void refusesAnUnknownFlagAndPrintsTheUsage() {
        Flags f = flags();
        assertFalse(f.parse(new String[]{"-nope"}));
        String printed = err.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("unknown flag -nope"), printed);
        assertTrue(printed.contains("usage: autor3search-java eval"), printed);
    }

    @Test
    void refusesAValueFlagWithNoValue() {
        Flags f = flags();
        assertFalse(f.parse(new String[]{"-C"}));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("needs a value"));
    }

    @Test
    void anEmptyInlineValueIsAValue() {
        Flags f = flags();
        assertTrue(f.parse(new String[]{"-desc="}));
        assertEquals("", f.get("desc"));
    }
}
