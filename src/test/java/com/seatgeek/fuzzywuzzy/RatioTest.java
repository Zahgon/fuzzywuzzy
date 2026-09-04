package com.seatgeek.fuzzywuzzy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code RatioTest} from test_fuzzywuzzy.py:76.
 */
class RatioTest {

    private final String s1 = "new york mets";
    private final String s1a = "new york mets";
    private final String s2 = "new YORK mets";
    private final String s3 = "the wonderful new york mets";
    private final String s4 = "new york mets vs atlanta braves";
    private final String s5 = "atlanta braves vs new york mets";
    private final String s7 = "new york city mets - atlanta braves";

    private final String s8 = "{";
    private final String s8a = "{";
    private final String s9 = "{a";
    private final String s9a = "{a";
    private final String s10 = "a{";
    private final String s10a = "{b";

    @Test
    void testEqual() {
        assertEquals(100, Fuzz.ratio(s1, s1a));
        assertEquals(100, Fuzz.ratio(s8, s8a));
        assertEquals(100, Fuzz.ratio(s9, s9a));
    }

    @Test
    void testCaseInsensitive() {
        assertNotEquals(100, Fuzz.ratio(s1, s2));
        assertEquals(100, Fuzz.ratio(Utils.fullProcess(s1), Utils.fullProcess(s2)));
    }

    @Test
    void testPartialRatio() {
        assertEquals(100, Fuzz.partialRatio(s1, s3));
    }

    @Test
    void testTokenSortRatio() {
        assertEquals(100, Fuzz.tokenSortRatio(s1, s1a));
    }

    @Test
    void testPartialTokenSortRatio() {
        assertEquals(100, Fuzz.partialTokenSortRatio(s1, s1a));
        assertEquals(100, Fuzz.partialTokenSortRatio(s4, s5));
        assertEquals(100, Fuzz.partialTokenSortRatio(s8, s8a, true, false));
        assertEquals(100, Fuzz.partialTokenSortRatio(s9, s9a, true, true));
        assertEquals(100, Fuzz.partialTokenSortRatio(s9, s9a, true, false));
        assertEquals(50, Fuzz.partialTokenSortRatio(s10, s10a, true, false));
    }

    @Test
    void testTokenSetRatio() {
        assertEquals(100, Fuzz.tokenSetRatio(s4, s5));
        assertEquals(100, Fuzz.tokenSetRatio(s8, s8a, true, false));
        assertEquals(100, Fuzz.tokenSetRatio(s9, s9a, true, true));
        assertEquals(100, Fuzz.tokenSetRatio(s9, s9a, true, false));
        assertEquals(50, Fuzz.tokenSetRatio(s10, s10a, true, false));
    }

    @Test
    void testPartialTokenSetRatio() {
        assertEquals(100, Fuzz.partialTokenSetRatio(s4, s7));
    }

    @Test
    void testQuickRatioEqual() {
        assertEquals(100, Fuzz.quickRatio(s1, s1a));
    }

    @Test
    void testQuickRatioCaseInsensitive() {
        assertEquals(100, Fuzz.quickRatio(s1, s2));
    }

    @Test
    void testQuickRatioNotEqual() {
        assertNotEquals(100, Fuzz.quickRatio(s1, s3));
    }

    @Test
    void testWRatioEqual() {
        assertEquals(100, Fuzz.weightedRatio(s1, s1a));
    }

    @Test
    void testWRatioCaseInsensitive() {
        assertEquals(100, Fuzz.weightedRatio(s1, s2));
    }

    @Test
    void testWRatioPartialMatch() {
        assertEquals(90, Fuzz.weightedRatio(s1, s3));
    }

    @Test
    void testWRatioMisorderedMatch() {
        assertEquals(95, Fuzz.weightedRatio(s4, s5));
    }

    @Test
    void testWRatioUnicode() {
        assertEquals(100, Fuzz.weightedRatio(s1, s1a));
    }

    /**
     * test_fuzzywuzzy.py:173 names this case QRatio but calls {@code fuzz.WRatio}; the port keeps
     * both the name and the call it actually makes.
     */
    @Test
    void testQRatioUnicode() {
        assertEquals(100, Fuzz.weightedRatio(s1, s1a));
    }

    @Test
    void testEmptyStringsScore100() {
        assertEquals(100, Fuzz.ratio("", ""));
        assertEquals(100, Fuzz.partialRatio("", ""));
    }

    @Test
    void testIssueSeven() {
        String a = "HSINCHUANG";
        String b = "SINJHUAN";
        String c = "LSINJHUANG DISTRIC";
        String d = "SINJHUANG DISTRICT";

        assertTrue(Fuzz.partialRatio(a, b) > 75);
        assertTrue(Fuzz.partialRatio(a, c) > 75);
        assertTrue(Fuzz.partialRatio(a, d) > 75);
    }

    @Test
    void testRatioUnicodeString() {
        assertEquals(0, Fuzz.ratio("\u00c1", "ABCD"));
    }

    @Test
    void testPartialRatioUnicodeString() {
        assertEquals(0, Fuzz.partialRatio("\u00c1", "ABCD"));
    }

    @Test
    void testWRatioUnicodeString() {
        assertEquals(0, Fuzz.weightedRatio("\u00c1", "ABCD"));

        String cyrillic1 = "\u043f\u0441\u0438\u0445\u043e\u043b\u043e\u0433";
        String cyrillic2 = "\u043f\u0441\u0438\u0445\u043e\u0442\u0435\u0440\u0430\u043f\u0435\u0432\u0442";
        assertNotEquals(0, Fuzz.weightedRatio(cyrillic1, cyrillic2, false, true));

        String chinese1 = "\u6211\u4e86\u89e3\u6570\u5b66";
        String chinese2 = "\u6211\u5b66\u6570\u5b66";
        assertNotEquals(0, Fuzz.weightedRatio(chinese1, chinese2, false, true));
    }

    @Test
    void testQRatioUnicodeString() {
        assertEquals(0, Fuzz.quickRatio("\u00c1", "ABCD"));

        String cyrillic1 = "\u043f\u0441\u0438\u0445\u043e\u043b\u043e\u0433";
        String cyrillic2 = "\u043f\u0441\u0438\u0445\u043e\u0442\u0435\u0440\u0430\u043f\u0435\u0432\u0442";
        assertNotEquals(0, Fuzz.quickRatio(cyrillic1, cyrillic2, false, true));

        String chinese1 = "\u6211\u4e86\u89e3\u6570\u5b66";
        String chinese2 = "\u6211\u5b66\u6570\u5b66";
        assertNotEquals(0, Fuzz.quickRatio(chinese1, chinese2, false, true));
    }

    @Test
    void testQratioForceAscii() {
        String a = "ABCD\u00c1";
        String b = "ABCD";

        assertEquals(100, Fuzz.quickRatio(a, b, true, true));
        assertTrue(Fuzz.quickRatio(a, b, false, true) < 100);
    }

    /**
     * test_fuzzywuzzy.py:248 spells this case QRatio with a capital R and calls {@code fuzz.WRatio};
     * {@link #testQratioForceAscii()} above is the lower-case sibling that really does call QRatio.
     */
    @Test
    void testQRatioForceAscii() {
        String a = "ABCD\u00c1";
        String b = "ABCD";

        assertEquals(100, Fuzz.weightedRatio(a, b, true, true));
        assertTrue(Fuzz.weightedRatio(a, b, false, true) < 100);
    }

    @Test
    void testTokenSetForceAscii() {
        String a = "ABCD\u00c1 HELP\u00c1";
        String b = "ABCD HELP";

        assertEquals(100, Fuzz.partialTokenSetRatio(a, b, true, true));
        assertTrue(Fuzz.partialTokenSetRatio(a, b, false, true) < 100);
    }

    @Test
    void testTokenSortForceAscii() {
        String a = "ABCD\u00c1 HELP\u00c1";
        String b = "ABCD HELP";

        assertEquals(100, Fuzz.partialTokenSortRatio(a, b, true, true));
        assertTrue(Fuzz.partialTokenSortRatio(a, b, false, true) < 100);
    }
}
