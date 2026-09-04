package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyRepr;
import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.matcher.DifflibSequenceMatcher;
import com.seatgeek.fuzzywuzzy.matcher.LevenshteinStringMatcher;
import com.seatgeek.fuzzywuzzy.model.EditType;
import com.seatgeek.fuzzywuzzy.model.Editop;
import com.seatgeek.fuzzywuzzy.model.ExtractedResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the helper and matcher surface that the ported reference suite reaches only indirectly.
 *
 * <p>Expectations come from CPython 3.12: {@code str.upper}, {@code repr(float)},
 * {@code difflib.SequenceMatcher} and {@code python-Levenshtein}, all run against the same inputs
 * used here.
 */
class InternalApiSurfaceTest {

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

    @Test
    void upperCaseFollowsPythonStrUpper() {
        assertEquals("NEW YORK METS", StringProcessor.toUpperCase("new york mets"));
        assertEquals("\u00c7A VA?", StringProcessor.toUpperCase("\u00e7a va?"));
        assertEquals("STRASSE", StringProcessor.toUpperCase("stra\u00dfe"));
        assertEquals("\u00c1BC", StringProcessor.toUpperCase("\u00e1bc"));
        assertEquals("", StringProcessor.toUpperCase(""));
        assertEquals("A_B", StringProcessor.toUpperCase("a_b"));
        assertEquals("I", StringProcessor.toUpperCase("\u0131"));
        assertEquals("\uff21\uff22", StringProcessor.toUpperCase("\uff41\uff42"));
    }

    @Test
    void reprOfDoublesFollowsPythonFloatRepr() {
        assertEquals("0.0", PyRepr.repr(0.0));
        assertEquals("1.0", PyRepr.repr(1.0));
        assertEquals("-1.0", PyRepr.repr(-1.0));
        assertEquals("3.0", PyRepr.repr(3.0));
        assertEquals("100.0", PyRepr.repr(100.0));
        assertEquals("2.5", PyRepr.repr(2.5));
        assertEquals("-2.5", PyRepr.repr(-2.5));
        assertEquals("0.1", PyRepr.repr(0.1));
        assertEquals("0.3333333333333333", PyRepr.repr(1.0 / 3.0));
        assertEquals("1000000000000000.0", PyRepr.repr(1e15));
        assertEquals("nan", PyRepr.repr(Double.NaN));
        assertEquals("inf", PyRepr.repr(Double.POSITIVE_INFINITY));
        assertEquals("-inf", PyRepr.repr(Double.NEGATIVE_INFINITY));
    }

    @Test
    void fromCodePointsRebuildsTheEntireArray() {
        int[] ascii = PyStr.toCodePoints("new york mets");
        assertEquals("new york mets", PyStr.fromCodePoints(ascii));
        assertEquals(PyStr.fromCodePoints(ascii, 0, ascii.length), PyStr.fromCodePoints(ascii));

        int[] astral = PyStr.toCodePoints("a\uD83D\uDE00b\u00e7");
        assertEquals(4, astral.length, "astral pairs count as one code point");
        assertEquals("a\uD83D\uDE00b\u00e7", PyStr.fromCodePoints(astral));
        assertEquals("", PyStr.fromCodePoints(new int[0]));
        assertEquals("\uD83D\uDE00", PyStr.fromCodePoints(new int[] {0x1F600}));
    }

    @Test
    void warnEmitsPythonUserWarningFormatting() {
        assertEquals("UserWarning: something is off\n",
                captureWarnings(() -> PyWarnings.warn("something is off")));

        String constructed = captureWarnings(
                () -> new LevenshteinStringMatcher(Boolean.TRUE, "abcd", "abed"));
        assertEquals("UserWarning: isjunk not NOT implemented, it will be ignored\n", constructed,
                "the doubled negative is upstream's own typo and is reproduced verbatim");

        assertEquals("", captureWarnings(() -> new LevenshteinStringMatcher(null, "abcd", "abed")),
                "a falsy isjunk stays silent, exactly as `if isjunk:` does");
        assertEquals("", captureWarnings(() -> new LevenshteinStringMatcher("abcd", "abed")));
    }

    @Test
    void difflibMatcherExposesJunkAndPopularElements() {
        DifflibSequenceMatcher junked = new DifflibSequenceMatcher(
                cp -> cp == ' ', "new york mets", "new york city mets", true);
        assertEquals(0.8387096774193549, junked.ratio(), 1e-12);
        assertEquals(Set.of((int) ' '), junked.getBjunk());
        assertEquals(Set.of(), junked.getBpopular(), "b is far shorter than the 200 autojunk cut-off");

        String repeated = "ab".repeat(150);
        DifflibSequenceMatcher popular = new DifflibSequenceMatcher(null, "abc", repeated, true);
        popular.ratio();
        assertEquals(300, repeated.length());
        assertEquals(Set.of(), popular.getBjunk());
        assertEquals(Set.of((int) 'a', (int) 'b'), popular.getBpopular());

        DifflibSequenceMatcher noAutojunk = new DifflibSequenceMatcher(null, "abc", repeated, false);
        noAutojunk.ratio();
        assertEquals(Set.of(), noAutojunk.getBpopular(), "autojunk=False keeps every element usable");
    }

    @Test
    void difflibSetSeqsReplacesBothSequencesAtOnce() {
        DifflibSequenceMatcher matcher = new DifflibSequenceMatcher("abc", "abc");
        assertEquals(1.0, matcher.ratio(), 1e-12);

        matcher.setSeqs("abcd", "abed");
        assertEquals(0.75, matcher.ratio(), 1e-12);
        assertEquals(0.75, matcher.quickRatio(), 1e-12);
        assertEquals(1.0, matcher.realQuickRatio(), 1e-12);

        matcher.setSeqs("new york mets", "new york city mets");
        assertEquals(0.8387096774193549, matcher.ratio(), 1e-12);
        assertEquals(0.8387096774193549, matcher.quickRatio(), 1e-12);
    }

    @Test
    void levenshteinMatcherAccessorsMirrorPythonStringMatcher() {
        LevenshteinStringMatcher matcher = new LevenshteinStringMatcher();
        assertEquals("", matcher.getSeq1());
        assertEquals("", matcher.getSeq2());
        assertThrows(ArithmeticException.class, matcher::realQuickRatio,
                "Python divides by len(a)+len(b) and raises on two empty sequences");

        matcher.setSeqs("new york mets", "new york city mets");
        assertEquals("new york mets", matcher.getSeq1());
        assertEquals("new york city mets", matcher.getSeq2());
        assertEquals(0.8387096774193549, matcher.ratio(), 1e-12);
        assertEquals(matcher.ratio(), matcher.quickRatio(), 1e-12,
                "upstream aliases quick_ratio to the exact ratio");
        assertEquals(5, matcher.distance());
        assertEquals(List.of(
                new Editop(EditType.INSERT, 9, 9),
                new Editop(EditType.INSERT, 9, 10),
                new Editop(EditType.INSERT, 9, 11),
                new Editop(EditType.INSERT, 9, 12),
                new Editop(EditType.INSERT, 9, 13)), matcher.getEditops());

        matcher.setSeq1("abcd");
        matcher.setSeq2("abed");
        assertEquals("abcd", matcher.getSeq1());
        assertEquals("abed", matcher.getSeq2());
        assertEquals(List.of(new Editop(EditType.REPLACE, 2, 2)), matcher.getEditops());
        assertEquals(0.75, matcher.quickRatio(), 1e-12);
        assertFalse(matcher.getEditops().stream().anyMatch(op -> op.type() == EditType.EQUAL),
                "editops never reports equal runs; only opcodes does");
    }

    @Test
    void toStringRendersPythonTupleRepr() {
        assertEquals("('replace', 1, 2)", new Editop(EditType.REPLACE, 1, 2).toString());
        assertEquals("('delete', 0, 0)", new Editop(EditType.DELETE, 0, 0).toString());
        assertEquals("('insert', 3, 4)", new Editop(EditType.INSERT, 3, 4).toString());
        assertEquals("('equal', 5, 6)", new Editop(EditType.EQUAL, 5, 6).toString());

        assertEquals("('mets', 100)", ExtractedResult.of("mets", 100).toString());
        assertEquals("('mets', 100, 'k')", ExtractedResult.ofEntry("mets", 100, "k").toString());
        assertEquals("(None, 0)", ExtractedResult.of(null, 0).toString());
        assertEquals("('a', 7, None)", ExtractedResult.ofEntry("a", 7, null).toString());
        assertEquals("(1, 50)", ExtractedResult.of(1, 50).toString());
        assertTrue(ExtractedResult.ofEntry("a", 7, null).fromMapping());
    }
}
