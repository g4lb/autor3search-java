package demo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The correctness gate. The agent cannot change this file. */
class WordCountTest {

    @Test
    void countsRepeatedWords() {
        assertEquals(Map.of("the", 2, "quick", 1, "brown", 1),
                WordCount.count("the quick brown the"));
    }

    @Test
    void stripsPunctuationAndCase() {
        assertEquals(Map.of("hello", 2, "world", 1),
                WordCount.count("Hello, WORLD! hello?"));
    }
}
