package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyStr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Port of {@code StringProcessingTest} from test_fuzzywuzzy.py:17.
 */
class StringProcessingTest {

    private static final String[] STRINGS = {
            "new york mets - atlanta braves",
            "C\u00e3es danados",
            "New York //// Mets $$$",
            "\u00c7a va?",
    };

    @Test
    void testReplaceNonLettersNonNumbersWithWhitespace() {
        for (String string : STRINGS) {
            String processed = StringProcessor.replaceNonLettersNonNumbersWithWhitespace(string);
            for (int cp : PyStr.toCodePoints(processed)) {
                if (!PyStr.isWord(cp)) {
                    assertEquals(' ', cp,
                            "every non-word code point left in " + processed + " must be a space");
                }
            }
        }

        assertEquals("new york mets   atlanta braves", replace(STRINGS[0]),
                "one hyphen surrounded by two spaces yields three spaces, not one");
        assertEquals("C\u00e3es danados", replace(STRINGS[1]),
                "accented letters are word characters under Python's re.UNICODE");
        assertEquals("New York      Mets    ", replace(STRINGS[2]));
        assertEquals("\u00c7a va ", replace(STRINGS[3]));
        assertEquals("", replace(""));
        assertEquals("a_b", replace("a_b"), "underscore is a word character");
        assertEquals(" x", replace("\uD83D\uDE00x"),
                "an astral symbol is one non-word code point, so it yields exactly one space");
    }

    @Test
    void testDontCondenseWhitespace() {
        String s1 = "new york mets - atlanta braves";
        String s2 = "new york mets atlanta braves";
        String p1 = replace(s1);
        String p2 = replace(s2);
        assertEquals("new york mets   atlanta braves", p1);
        assertEquals("new york mets atlanta braves", p2);
        assertNotEquals(p1, p2);
    }

    private static String replace(String value) {
        return StringProcessor.replaceNonLettersNonNumbersWithWhitespace(value);
    }
}
