package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.matcher.DifflibSequenceMatcher;
import com.seatgeek.fuzzywuzzy.matcher.LevenshteinCore;
import com.seatgeek.fuzzywuzzy.matcher.LevenshteinStringMatcher;
import com.seatgeek.fuzzywuzzy.model.EditType;
import com.seatgeek.fuzzywuzzy.model.Editop;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential tests pinning both matcher backends to output captured from the installed
 * {@code Levenshtein} extension and from CPython's {@code difflib}.
 *
 * <p>These are the gate for the whole port: {@code fuzz.partial_ratio} reads matching blocks
 * directly, so any divergence here silently changes scores everywhere downstream.
 */
class MatcherGoldenTest {

    private static final String FIXTURE = "/golden/matcher.tsv.gz";

    private static List<String[]> rows;

    @BeforeAll
    static void load() {
        rows = GoldenFixture.readRows(FIXTURE);
        assertTrue(rows.size() > 10_000, "fixture looks truncated: " + rows.size());
    }

    @Test
    void levenshteinEditopsMatchPython() {
        int checked = 0;
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            String actual = formatEditops(
                    LevenshteinCore.editops(PyStr.toCodePoints(a), PyStr.toCodePoints(b)));
            assertEquals(row[2], actual, () -> "editops(" + describe(a) + ", " + describe(b) + ")");
            checked++;
        }
        assertEquals(rows.size(), checked);
    }

    @Test
    void levenshteinOpcodesMatchPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            String actual = formatOpcodes(
                    LevenshteinCore.opcodes(PyStr.toCodePoints(a), PyStr.toCodePoints(b)));
            assertEquals(row[3], actual, () -> "opcodes(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void levenshteinMatchingBlocksMatchPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            String actual = formatBlocks(new LevenshteinStringMatcher(a, b).getMatchingBlocks());
            assertEquals(row[4], actual, () -> "matching_blocks(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void levenshteinRatioMatchesPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            double expected = Double.parseDouble(row[5]);
            double actual = new LevenshteinStringMatcher(a, b).ratio();
            assertEquals(expected, actual, 1e-15,
                    () -> "ratio(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void levenshteinDistanceMatchesPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            int expected = Integer.parseInt(row[6]);
            assertEquals(expected, new LevenshteinStringMatcher(a, b).distance(),
                    () -> "distance(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void difflibRatioMatchesPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            double expected = Double.parseDouble(row[7]);
            double actual = new DifflibSequenceMatcher(a, b).ratio();
            assertEquals(expected, actual, 1e-15,
                    () -> "difflib ratio(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void difflibMatchingBlocksMatchPython() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            String actual = formatBlocks(new DifflibSequenceMatcher(a, b).getMatchingBlocks());
            assertEquals(row[8], actual,
                    () -> "difflib matching_blocks(" + describe(a) + ", " + describe(b) + ")");
        }
    }

    @Test
    void opcodesRoundTripThroughEditops() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            int[] cpA = PyStr.toCodePoints(a);
            int[] cpB = PyStr.toCodePoints(b);
            List<Opcode> opcodes = LevenshteinCore.opcodes(cpA, cpB);
            List<Editop> back = LevenshteinCore.editopsFromOpcodes(opcodes);
            assertEquals(row[2], formatEditops(back),
                    () -> "editops(opcodes(" + describe(a) + ", " + describe(b) + "))");
        }
    }

    @Test
    void difflibOpcodesCoverBothSequencesContiguously() {
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            List<Opcode> ops = new DifflibSequenceMatcher(a, b).getOpcodes();
            int i = 0;
            int j = 0;
            for (Opcode op : ops) {
                assertEquals(i, op.srcBegin());
                assertEquals(j, op.destBegin());
                i = op.srcEnd();
                j = op.destEnd();
            }
            if (!ops.isEmpty()) {
                assertEquals(PyStr.len(a), i);
                assertEquals(PyStr.len(b), j);
            }
        }
    }

    @Test
    void backendsDisagreeOftenEnoughToMatter() {
        int ratioDiff = 0;
        int blockDiff = 0;
        for (String[] row : rows) {
            String a = GoldenFixture.decode(row[0]);
            String b = GoldenFixture.decode(row[1]);
            if (Math.abs(Double.parseDouble(row[5]) - Double.parseDouble(row[7])) > 1e-12) {
                ratioDiff++;
            }
            if (!row[4].equals(row[8])) {
                blockDiff++;
            }
        }
        assertTrue(ratioDiff > 0, "backends should not agree everywhere");
        assertTrue(blockDiff > 0, "backends should not agree everywhere");
        assertNotEquals(0, ratioDiff + blockDiff);
    }

    @Test
    void documentedDoctestExamplesHold() {
        assertEquals("[('replace', 0, 1, 0, 1), ('equal', 1, 4, 1, 4)]",
                new LevenshteinStringMatcher("abcd", "xbcd").getOpcodes().toString());
        assertEquals("[(1, 1, 3), (4, 4, 0)]",
                new LevenshteinStringMatcher("abcd", "xbcd").getMatchingBlocks().toString());

        assertEquals("[(0, 0, 2), (3, 2, 2), (5, 4, 0)]",
                new DifflibSequenceMatcher("abxcd", "abcd").getMatchingBlocks().toString());
        assertEquals("[('delete', 0, 1, 0, 0), ('equal', 1, 3, 0, 2), ('replace', 3, 4, 2, 3),"
                        + " ('equal', 4, 6, 3, 5), ('insert', 6, 6, 5, 6)]",
                new DifflibSequenceMatcher("qabxcd", "abycdf").getOpcodes().toString());

        assertEquals(new MatchingBlock(0, 4, 5),
                new DifflibSequenceMatcher(" abcd", "abcd abcd").findLongestMatch(0, 5, 0, 9));
        assertEquals(new MatchingBlock(1, 0, 4),
                new DifflibSequenceMatcher(cp -> cp == ' ', " abcd", "abcd abcd", true)
                        .findLongestMatch(0, 5, 0, 9));
        assertEquals(new MatchingBlock(0, 0, 0),
                new DifflibSequenceMatcher("ab", "c").findLongestMatch(0, 2, 0, 1));

        DifflibSequenceMatcher quick = new DifflibSequenceMatcher("abcd", "bcde");
        assertEquals(0.75, quick.ratio(), 0.0);
        assertEquals(0.75, quick.quickRatio(), 0.0);
        assertEquals(1.0, quick.realQuickRatio(), 0.0);
    }

    @Test
    void realQuickRatioDividesByZeroOnEmptyInputs() {
        assertThrows(ArithmeticException.class, () -> new LevenshteinStringMatcher("", "").realQuickRatio());
        assertEquals(1.0, new DifflibSequenceMatcher("", "").realQuickRatio(), 0.0);
        assertEquals(1.0, new DifflibSequenceMatcher("", "").ratio(), 0.0);
        assertEquals(1.0, new LevenshteinStringMatcher("", "").ratio(), 0.0);
    }

    @Test
    void codePointsAreTheUnitOfComparison() {
        String astral = "\uD83D\uDE00";
        assertEquals(1, PyStr.len(astral));
        assertEquals(1.0, new LevenshteinStringMatcher(astral, astral).ratio(), 0.0);
        assertEquals(0.0, new LevenshteinStringMatcher(astral, "\uD83D\uDE01").ratio(), 0.0);
        assertEquals(1, new LevenshteinStringMatcher(astral, "\uD83D\uDE01").distance());
    }

    private static String formatEditops(List<Editop> ops) {
        StringBuilder sb = new StringBuilder();
        for (Editop op : ops) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(tag(op.type())).append(':').append(op.srcPos()).append(':').append(op.destPos());
        }
        return sb.toString();
    }

    private static String formatOpcodes(List<Opcode> ops) {
        StringBuilder sb = new StringBuilder();
        for (Opcode op : ops) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(tag(op.type())).append(':').append(op.srcBegin()).append(':').append(op.srcEnd())
                    .append(':').append(op.destBegin()).append(':').append(op.destEnd());
        }
        return sb.toString();
    }

    private static String formatBlocks(List<MatchingBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (MatchingBlock block : blocks) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(block.srcPos()).append(':').append(block.destPos()).append(':').append(block.length());
        }
        return sb.toString();
    }

    private static char tag(EditType type) {
        return switch (type) {
            case DELETE -> 'd';
            case INSERT -> 'i';
            case REPLACE -> 'r';
            case EQUAL -> 'e';
        };
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder("'");
        s.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(String.format("\\u{%04X}", cp));
            }
        });
        return sb.append('\'').toString();
    }
}
