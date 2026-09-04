package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyRepr;
import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.matcher.DifflibSequenceMatcher;
import com.seatgeek.fuzzywuzzy.matcher.LevenshteinStringMatcher;
import com.seatgeek.fuzzywuzzy.model.EditType;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Python-language behaviour the port has to reproduce for fuzzywuzzy to score identically.
 *
 * <p>None of this is visible in the reference test suite, yet all of it changes results:
 * {@code utils.full_process} stringifies non-string choices with {@code str()}, the empty-query
 * warning calls {@code len()} on whatever the processor returned, {@code StringMatcher} branches on
 * the truthiness of its {@code isjunk} argument, and {@code str.upper()} can lengthen a string.
 *
 * <p>Every expectation below was produced by CPython 3.12 on the same input and is quoted verbatim.
 */
class PythonSemanticsTest {

    private static String captureWarnings(Runnable body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PyWarnings.setSink(new PrintStream(buffer, true, UTF_8));
        try {
            body.run();
        } finally {
            PyWarnings.setSink(null);
        }
        return buffer.toString(UTF_8);
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** The 32 Unicode noncharacters, which no Unicode revision will ever assign or case. */
    private static String noncharacters() {
        StringBuilder sb = new StringBuilder(32);
        for (int cp = 0xFDD0; cp <= 0xFDEF; cp++) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    @Test
    void reprOfBuiltinContainersMatchesPython() {
        assertEquals("None", PyRepr.repr(null));
        assertEquals("True", PyRepr.repr(true));
        assertEquals("False", PyRepr.repr(false));
        assertEquals("3", PyRepr.repr(3));
        assertEquals("-7", PyRepr.repr(-7));
        assertEquals("0", PyRepr.repr(0));

        assertEquals("[]", PyRepr.repr(List.of()));
        assertEquals("['a', 'b']", PyRepr.repr(List.of("a", "b")));
        assertEquals("[1, 2]", PyRepr.repr(List.of(1, 2)));
        assertEquals("['a', 'b', 'c']", PyRepr.repr(List.of("a", "b", "c")));
        assertEquals("[[]]", PyRepr.repr(List.of(List.of())));
        assertEquals("[[1], [2]]", PyRepr.repr(List.of(List.of(1), List.of(2))));
        assertEquals("[True, False, None]", PyRepr.repr(Arrays.asList(true, false, null)));
        assertEquals("[0.5]", PyRepr.repr(List.of(0.5)));
        assertEquals("[1.0]", PyRepr.repr(List.of(1.0)));
        assertEquals("['new york mets', 42]", PyRepr.repr(List.of("new york mets", 42)));

        assertEquals("{}", PyRepr.repr(ordered()));
        assertEquals("{'a': 1}", PyRepr.repr(ordered("a", 1)));
        assertEquals("{'k': 'v', 'n': None}", PyRepr.repr(ordered("k", "v", "n", null)));
        assertEquals("{'a': True}", PyRepr.repr(ordered("a", true)));
        assertEquals("{'a': {'b': 'c'}}", PyRepr.repr(ordered("a", ordered("b", "c"))));
        assertEquals("{'x': []}", PyRepr.repr(ordered("x", List.of())));
        assertEquals("{'n': 1.5}", PyRepr.repr(ordered("n", 1.5)));
        assertEquals("{1: [2, 3]}", PyRepr.repr(Map.of(1, List.of(2, 3))));

        assertEquals("set()", PyRepr.repr(Set.of()),
                "an empty Python set has no literal form, so repr falls back to the constructor");
        assertEquals("{'a'}", PyRepr.repr(Set.of("a")));

        assertEquals("[{'a': [1, None]}]",
                PyRepr.repr(List.of(ordered("a", Arrays.asList(1, null)))));

        assertEquals("['a', 'b']", PyRepr.repr(new Object[] {"a", "b"}),
                "arrays are the Java stand-in for a Python list");
        assertEquals("[]", PyRepr.repr(new Object[0]));
    }

    @Test
    void reprQuotingMatchesPythonStringRepr() {
        assertEquals("''", PyRepr.repr(""));
        assertEquals("'plain'", PyRepr.repr("plain"));
        assertEquals("':::::::'", PyRepr.repr(":::::::"));
        assertEquals("'new york mets'", PyRepr.repr("new york mets"));

        assertEquals("\"it's\"", PyRepr.repr("it's"),
                "Python switches to double quotes when the value holds a single quote and no double");
        assertEquals("\"o'reilly\"", PyRepr.repr("o'reilly"));
        assertEquals("'\"quoted\"'", PyRepr.repr("\"quoted\""));
        assertEquals("'both \\' and \"'", PyRepr.repr("both ' and \""),
                "with both quote characters present Python keeps single quotes and escapes them");

        assertEquals("'a\\nb'", PyRepr.repr("a\nb"));
        assertEquals("'a\\tb'", PyRepr.repr("a\tb"));
        assertEquals("'a\\rb'", PyRepr.repr("a\rb"));
        assertEquals("'a\\\\b'", PyRepr.repr("a\\b"));
        assertEquals("'\\x00'", PyRepr.repr("\u0000"));
        assertEquals("'\\x1f'", PyRepr.repr("\u001f"));
        assertEquals("'\\x7f'", PyRepr.repr("\u007f"));

        assertEquals("'caf\u00e9'", PyRepr.repr("caf\u00e9"),
                "printable non-ASCII is emitted as itself, not as an escape");
        assertEquals("'\u00e7a va?'", PyRepr.repr("\u00e7a va?"));
    }

    @Test
    void strRendersValuesLikePythonStr() {
        assertEquals("a", PyRepr.str("a"), "str() of a string is that same string, unquoted");
        assertEquals("", PyRepr.str(""));
        assertEquals("new york mets", PyRepr.str("new york mets"));
        assertEquals("None", PyRepr.str(null));
        assertEquals("True", PyRepr.str(true));
        assertEquals("3", PyRepr.str(3));
        assertEquals("0.5", PyRepr.str(0.5));
        assertEquals("['a']", PyRepr.str(List.of("a")));
        assertEquals("[1, 2]", PyRepr.str(List.of(1, 2)));
        assertEquals("{'a': 1}", PyRepr.str(ordered("a", 1)));
    }

    @Test
    void caseMappingReproducesPythonExpansions() {
        assertEquals("SS", PyStr.upper("\u00df"), "one code point becomes two");
        assertEquals("FI", PyStr.upper("\ufb01"));
        assertEquals("FFL", PyStr.upper("\ufb04"), "one code point becomes three");
        assertEquals("\u02bcN", PyStr.upper("\u0149"));
        assertEquals("\u0399\u0308\u0301", PyStr.upper("\u0390"));
        assertEquals("\u01c4", PyStr.upper("\u01c5"), "title case maps up to upper case");
        assertEquals("\u0130", PyStr.upper("\u0130"), "dotted capital I is already upper case");
        assertEquals("\u1e9e", PyStr.upper("\u1e9e"));
        assertEquals("\u0178", PyStr.upper("\u00ff"));

        assertEquals("\u0069\u0307", PyStr.lower("\u0130"), "and back down it expands to two");
        assertEquals("\u00df", PyStr.lower("\u1e9e"));
        assertEquals("\u01c6", PyStr.lower("\u01c5"));
        assertEquals("\u00df", PyStr.lower("\u00df"), "sharp s has no lower-case mapping");
        assertEquals("\ufb01", PyStr.lower("\ufb01"));
        assertEquals("\u0149", PyStr.lower("\u0149"));

        String reserved = noncharacters();
        assertEquals(32, reserved.length());
        assertEquals(reserved, PyStr.lower(reserved), "noncharacters are uncased in every revision");
        assertEquals(reserved, PyStr.upper(reserved));
        assertEquals("\u03b1\u03c2" + reserved, PyStr.lower("\u0391\u03a3" + reserved),
                "an uncased tail still leaves sigma final");
        assertEquals(reserved + "\u03b1\u03c2", PyStr.lower(reserved + "\u0391\u03a3"));
    }

    @Test
    void findLongestMatchAgreesWithDifflib() {
        DifflibSequenceMatcher matcher = new DifflibSequenceMatcher("qabxcd", "abycdf");
        assertEquals(new MatchingBlock(1, 0, 2), matcher.findLongestMatch(0, 6, 0, 6));
        assertEquals(0.6666666666666666, matcher.ratio(), 1e-12);
        assertEquals(List.of(
                new MatchingBlock(1, 0, 2),
                new MatchingBlock(4, 3, 2),
                new MatchingBlock(6, 6, 0)), matcher.getMatchingBlocks());
        assertEquals(List.of(
                new Opcode(EditType.DELETE, 0, 1, 0, 0),
                new Opcode(EditType.EQUAL, 1, 3, 0, 2),
                new Opcode(EditType.REPLACE, 3, 4, 2, 3),
                new Opcode(EditType.EQUAL, 4, 6, 3, 5),
                new Opcode(EditType.INSERT, 6, 6, 5, 6)), matcher.getOpcodes());

        DifflibSequenceMatcher swap = new DifflibSequenceMatcher("abcd", "abed");
        assertEquals(new MatchingBlock(0, 0, 2), swap.findLongestMatch(0, 4, 0, 4));
        assertEquals(List.of(
                new MatchingBlock(0, 0, 2),
                new MatchingBlock(3, 3, 1),
                new MatchingBlock(4, 4, 0)), swap.getMatchingBlocks());
        assertEquals(List.of(
                new Opcode(EditType.EQUAL, 0, 2, 0, 2),
                new Opcode(EditType.REPLACE, 2, 3, 2, 3),
                new Opcode(EditType.EQUAL, 3, 4, 3, 4)), swap.getOpcodes());

        DifflibSequenceMatcher disjoint = new DifflibSequenceMatcher("abcd", "xyz");
        assertEquals(new MatchingBlock(0, 0, 0), disjoint.findLongestMatch(0, 4, 0, 3));
        assertEquals(0.0, disjoint.ratio(), 1e-12);
        assertEquals(0.0, disjoint.quickRatio(), 1e-12);
        assertEquals(0.8571428571428571, disjoint.realQuickRatio(), 1e-12,
                "real_quick_ratio only compares lengths, so it stays optimistic");

        DifflibSequenceMatcher empty = new DifflibSequenceMatcher("", "");
        assertEquals(new MatchingBlock(0, 0, 0), empty.findLongestMatch(0, 0, 0, 0));
        assertEquals(1.0, empty.ratio(), 1e-12, "two empty sequences are a perfect match");

        DifflibSequenceMatcher junked = new DifflibSequenceMatcher(
                cp -> cp == ' ', "new york mets", "new york city mets", true);
        assertEquals(new MatchingBlock(3, 3, 6), junked.findLongestMatch(0, 13, 0, 18),
                "the junk space at index 3 still anchors the run around it");

        DifflibSequenceMatcher stepwise = new DifflibSequenceMatcher("abcd", "abed");
        assertEquals(0.75, stepwise.ratio(), 1e-12);
        stepwise.setSeq2("abcd");
        assertEquals(1.0, stepwise.ratio(), 1e-12, "set_seq2 invalidates the cached ratio");
        stepwise.setSeq1("zzzz");
        assertEquals(0.0, stepwise.ratio(), 1e-12);
        stepwise.setSeqs("abcd", "abed");
        assertEquals(0.75, stepwise.ratio(), 1e-12);
    }

    @Test
    void isjunkTruthinessFollowsPython() {
        assertEquals("", warningFor(null), "None is falsy");
        assertEquals("", warningFor(Boolean.FALSE));
        assertEquals("", warningFor(""));
        assertEquals("", warningFor(List.of()));
        assertEquals("", warningFor(Map.of()));
        assertEquals("", warningFor(Set.of()));
        assertEquals("", warningFor(0));
        assertEquals("", warningFor(0.0), "0.0 is falsy just like 0");

        String warning = "UserWarning: isjunk not NOT implemented, it will be ignored\n";
        assertEquals(warning, warningFor(Boolean.TRUE));
        assertEquals(warning, warningFor("x"));
        assertEquals(warning, warningFor(List.of(1)));
        assertEquals(warning, warningFor(Map.of("a", 1)));
        assertEquals(warning, warningFor(Set.of("a")));
        assertEquals(warning, warningFor(1));
        assertEquals(warning, warningFor(1.5));
        assertEquals(warning, warningFor(new StringBuilder("junk")),
                "any other object is truthy, which is Python's default");
    }

    private static String warningFor(Object isjunk) {
        return captureWarnings(() -> new LevenshteinStringMatcher(isjunk, "abcd", "abed"));
    }

    @Test
    void emptyQueryWarningFollowsPythonLen() {
        assertTrue(emptyQueryWarning("").contains("[Query: '']"),
                "an empty string query is reported before any comparison runs");
        assertTrue(emptyQueryWarning(List.of()).contains("[Query: '[]']"),
                "the warning formats the query with str(), so a list keeps its brackets");
        assertTrue(emptyQueryWarning(Map.of()).contains("[Query: '{}']"));
        assertTrue(emptyQueryWarning(new Object[0]).contains("[Query: '[]']"));
        assertEquals("WARNING:root:Applied processor reduces input query to empty string, "
                + "all comparisons will have score 0. [Query: '']\n", emptyQueryWarning(""));

        assertEquals("", emptyQueryWarning("abc"), "a non-empty query is silent");
        assertEquals("", emptyQueryWarning(List.of("a")));
        assertEquals("", emptyQueryWarning(Map.of("a", 1)));
        assertEquals("", emptyQueryWarning(new Object[] {"a"}));

        IllegalArgumentException unsized =
                assertThrows(IllegalArgumentException.class, () -> emptyQueryWarning(5));
        assertTrue(unsized.getMessage().endsWith("has no len()"),
                "Python raises TypeError: object of type 'int' has no len()");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> emptyQueryWarning(null))
                .getMessage().contains("NoneType"));
    }

    private static String emptyQueryWarning(Object query) {
        return captureWarnings(() -> Process.extractBests(
                query, List.of("abc"), null, BuiltinScorer.RATIO, 0, null));
    }
}
