package io.github.g4lb.autor3search.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @Test
    void parsesEveryUnit() {
        assertEquals(Duration.ofNanos(5), Durations.parse("5ns"));
        assertEquals(Duration.ofNanos(1500), Durations.parse("1500ns"));
        assertEquals(Duration.ofMillis(200), Durations.parse("200ms"));
        assertEquals(Duration.ofSeconds(1), Durations.parse("1s"));
        assertEquals(Duration.ofMinutes(15), Durations.parse("15m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h"));
    }

    @Test
    void parsesCompoundAndFractional() {
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"));
        assertEquals(Duration.ofMillis(1500), Durations.parse("1.5s"));
    }

    /**
     * A bare number is refused rather than assumed to be seconds. Guessing the
     * unit of a measurement budget silently makes two runs incomparable.
     */
    @Test
    void refusesABareNumber() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("15"));
    }

    @Test
    void refusesIso8601AndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("PT15M"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(""));
    }

    @Test
    void recognisesTheFixedIterationCountForm() {
        assertTrue(Durations.isCountForm("100x"));
        assertTrue(Durations.isCountForm("1x"));
        assertTrue(!Durations.isCountForm("1s"));
        assertTrue(!Durations.isCountForm("x"));
    }

    @Test
    void formatsBackIntoSomethingParseCanRead() {
        for (String s : new String[]{"250ms", "1s", "15m", "2h"}) {
            assertEquals(Durations.parse(s), Durations.parse(Durations.format(Durations.parse(s))), s);
        }
    }
}
