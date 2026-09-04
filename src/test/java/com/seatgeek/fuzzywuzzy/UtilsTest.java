package com.seatgeek.fuzzywuzzy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code UtilsTest} from test_fuzzywuzzy.py:35.
 *
 * <p>The Python original only checks that these calls do not raise. Every expected value below was
 * captured from the reference interpreter, so this port also pins the results.
 */
class UtilsTest {

    /** {@code self.mixed_strings} from test_fuzzywuzzy.py:37. */
    private static final String LOREM =
            "Lorem Ipsum is simply dummy text of the printing and typesetting industry.";
    private static final String FRENCH = "C'est la vie";
    private static final String CEDILLA = "\u00c7a va?";
    private static final String TILDE = "C\u00e3es danados";
    private static final String NOT_SIGN = "\u00acCamar\u00f5es assados";
    private static final String MIXED_SCRIPTS = "a\u00ac\u1234\u20ac\u8000";
    private static final String ACUTE = "\u00c1";

    @Test
    void testAsciidammit() {
        assertEquals(LOREM, Utils.asciiDammit(LOREM), "pure ASCII survives untouched");
        assertEquals("C'est la vie", Utils.asciiDammit(FRENCH));
        assertEquals("a va?", Utils.asciiDammit(CEDILLA), "\u00c7 has no ASCII spelling and is dropped");
        assertEquals("Ces danados", Utils.asciiDammit(TILDE));
        assertEquals("Camares assados", Utils.asciiDammit(NOT_SIGN));
        assertEquals("a\u1234\u20ac\u8000", Utils.asciiDammit(MIXED_SCRIPTS),
                "only the Latin-1 code points are folded; the rest are left alone");
        assertEquals("", Utils.asciiDammit(ACUTE));
    }

    @Test
    void testAsciionly() {
        assertEquals(LOREM, Utils.asciiOnly(Utils.asciiDammit(LOREM)));
        assertEquals("C'est la vie", Utils.asciiOnly(Utils.asciiDammit(FRENCH)));
        assertEquals("a va?", Utils.asciiOnly(Utils.asciiDammit(CEDILLA)));
        assertEquals("Ces danados", Utils.asciiOnly(Utils.asciiDammit(TILDE)));
        assertEquals("Camares assados", Utils.asciiOnly(Utils.asciiDammit(NOT_SIGN)));
        assertEquals("a\u1234\u20ac\u8000", Utils.asciiOnly(Utils.asciiDammit(MIXED_SCRIPTS)),
                "asciionly is not idempotent after asciidammit: it keeps whatever asciidammit kept");
        assertEquals("", Utils.asciiOnly(Utils.asciiDammit(ACUTE)));
    }

    @Test
    void testFullProcess() {
        assertEquals("lorem ipsum is simply dummy text of the printing and typesetting industry",
                Utils.fullProcess(LOREM), "the trailing full stop becomes whitespace and is stripped");
        assertEquals("c est la vie", Utils.fullProcess(FRENCH), "the apostrophe becomes a space");
        assertEquals("\u00e7a va", Utils.fullProcess(CEDILLA), "accents survive without force_ascii");
        assertEquals("c\u00e3es danados", Utils.fullProcess(TILDE));
        assertEquals("camar\u00f5es assados", Utils.fullProcess(NOT_SIGN));
        assertEquals("a \u1234 \u8000", Utils.fullProcess(MIXED_SCRIPTS),
                "the currency sign is not a word character, so it becomes a space");
        assertEquals("\u00e1", Utils.fullProcess(ACUTE));
    }

    @Test
    void testFullProcessForceAscii() {
        assertEquals("lorem ipsum is simply dummy text of the printing and typesetting industry",
                Utils.fullProcess(LOREM, true));
        assertEquals("c est la vie", Utils.fullProcess(FRENCH, true));
        assertEquals("a va", Utils.fullProcess(CEDILLA, true), "force_ascii drops the cedilla first");
        assertEquals("ces danados", Utils.fullProcess(TILDE, true));
        assertEquals("camares assados", Utils.fullProcess(NOT_SIGN, true));
        assertEquals("a\u1234 \u8000", Utils.fullProcess(MIXED_SCRIPTS, true),
                "the not sign is removed outright, so no space is left behind in its place");
        assertEquals("", Utils.fullProcess(ACUTE, true));
    }

    @Test
    void testValidateString() {
        assertTrue(Utils.validateString("a"));
        assertFalse(Utils.validateString(""));
        assertFalse(Utils.validateString(null));
    }
}
