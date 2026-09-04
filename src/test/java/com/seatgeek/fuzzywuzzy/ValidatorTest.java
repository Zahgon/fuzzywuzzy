package com.seatgeek.fuzzywuzzy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Port of {@code ValidatorTest} from test_fuzzywuzzy.py:279.
 *
 * <p>The Python original decorates {@code lambda *args, **kwargs: (args, kwargs)}, an arbitrary
 * two-argument function, and asserts the result is {@code 0} for rejected input and something
 * other than {@code 0} otherwise. The Java stand-in returns a non-zero sentinel.
 */
class ValidatorTest {

    private static final int SENTINEL = 42;

    private final Scorer testFunc = (s1, s2) -> SENTINEL;

    @Test
    void testCheckForNone() {
        String[][] invalidInput = {
                {null, null},
                {"Some", null},
                {null, "Some"},
        };
        Scorer decorated = Utils.checkForNone(testFunc);
        for (String[] i : invalidInput) {
            assertEquals(0, decorated.score(i[0], i[1]));
        }
        assertNotEquals(0, decorated.score("Some", "Some"));
    }

    @Test
    void testCheckEmptyString() {
        String[][] invalidInput = {
                {"", ""},
                {"Some", ""},
                {"", "Some"},
        };
        Scorer decorated = Utils.checkEmptyString(testFunc);
        for (String[] i : invalidInput) {
            assertEquals(0, decorated.score(i[0], i[1]));
        }
        assertNotEquals(0, decorated.score("Some", "Some"));
    }

    @Test
    void testCheckForEquivalence() {
        Scorer decorated = Utils.checkForEquivalence(testFunc);
        assertEquals(100, decorated.score("Some", "Some"));
        assertEquals(SENTINEL, decorated.score("Some", "Other"));
    }
}
