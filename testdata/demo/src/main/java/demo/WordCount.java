package demo;

import java.util.HashMap;
import java.util.Map;

/** The code the agent is allowed to change. */
public final class WordCount {

    private WordCount() {}

    /** Returns how many times each lowercase word appears in s. */
    public static Map<String, Integer> count(String s) {
        Map<String, Integer> counts = new HashMap<>();
        for (String field : s.split("\\s+")) {
            String word = "";
            for (int i = 0; i < field.length(); i++) {
                char c = field.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    c += 'a' - 'A';
                }
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    word = word + c; // quadratic: rebuilds the string every character
                }
            }
            if (!word.isEmpty()) {
                counts.merge(word, 1, Integer::sum);
            }
        }
        return counts;
    }
}
