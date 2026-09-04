package com.seatgeek.fuzzywuzzy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential tests of every scorer in {@link Fuzz} against scores captured from the reference
 * Python implementation running with the python-Levenshtein backend.
 */
class FuzzGoldenTest {

    private static final String FIXTURE = "/golden/fuzz.tsv.gz";

    private static final Map<String, ToIntBiFunction<String, String>> SCORERS = scorers();

    private static Map<String, ToIntBiFunction<String, String>> scorers() {
        Map<String, ToIntBiFunction<String, String>> map = new LinkedHashMap<>();
        map.put("ratio", Fuzz::ratio);
        map.put("partial_ratio", Fuzz::partialRatio);
        map.put("token_sort_ratio:T:T", (a, b) -> Fuzz.tokenSortRatio(a, b, true, true));
        map.put("token_sort_ratio:F:T", (a, b) -> Fuzz.tokenSortRatio(a, b, false, true));
        map.put("token_sort_ratio:T:F", (a, b) -> Fuzz.tokenSortRatio(a, b, true, false));
        map.put("partial_token_sort_ratio:T:T", (a, b) -> Fuzz.partialTokenSortRatio(a, b, true, true));
        map.put("partial_token_sort_ratio:F:T", (a, b) -> Fuzz.partialTokenSortRatio(a, b, false, true));
        map.put("partial_token_sort_ratio:T:F", (a, b) -> Fuzz.partialTokenSortRatio(a, b, true, false));
        map.put("token_set_ratio:T:T", (a, b) -> Fuzz.tokenSetRatio(a, b, true, true));
        map.put("token_set_ratio:F:T", (a, b) -> Fuzz.tokenSetRatio(a, b, false, true));
        map.put("token_set_ratio:T:F", (a, b) -> Fuzz.tokenSetRatio(a, b, true, false));
        map.put("partial_token_set_ratio:T:T", (a, b) -> Fuzz.partialTokenSetRatio(a, b, true, true));
        map.put("partial_token_set_ratio:F:T", (a, b) -> Fuzz.partialTokenSetRatio(a, b, false, true));
        map.put("partial_token_set_ratio:T:F", (a, b) -> Fuzz.partialTokenSetRatio(a, b, true, false));
        map.put("QRatio:T:T", (a, b) -> Fuzz.quickRatio(a, b, true, true));
        map.put("QRatio:F:T", (a, b) -> Fuzz.quickRatio(a, b, false, true));
        map.put("QRatio:T:F", (a, b) -> Fuzz.quickRatio(a, b, true, false));
        map.put("UQRatio:T", (a, b) -> Fuzz.unicodeQuickRatio(a, b, true));
        map.put("UQRatio:F", (a, b) -> Fuzz.unicodeQuickRatio(a, b, false));
        map.put("WRatio:T:T", (a, b) -> Fuzz.weightedRatio(a, b, true, true));
        map.put("WRatio:F:T", (a, b) -> Fuzz.weightedRatio(a, b, false, true));
        map.put("WRatio:T:F", (a, b) -> Fuzz.weightedRatio(a, b, true, false));
        map.put("UWRatio:T", (a, b) -> Fuzz.unicodeWeightedRatio(a, b, true));
        map.put("UWRatio:F", (a, b) -> Fuzz.unicodeWeightedRatio(a, b, false));
        return map;
    }

    @Test
    @DisplayName("every scorer matches Python on every pair in the fixture")
    void allScorersMatchPython() {
        List<String[]> rows = GoldenFixture.readRows(FIXTURE);
        String[] header = rows.get(0);

        List<ToIntBiFunction<String, String>> ordered = new ArrayList<>(header.length);
        for (String name : header) {
            ToIntBiFunction<String, String> scorer = SCORERS.get(name);
            assertTrue(scorer != null, "fixture references an unknown scorer: " + name);
            ordered.add(scorer);
        }

        List<String> failures = new ArrayList<>();
        int comparisons = 0;

        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String s1 = GoldenFixture.decode(row[0]);
            String s2 = GoldenFixture.decode(row[1]);

            for (int c = 0; c < ordered.size(); c++) {
                int expected = Integer.parseInt(row[c + 2]);
                int actual = ordered.get(c).applyAsInt(s1, s2);
                comparisons++;
                if (expected != actual) {
                    if (failures.size() < 25) {
                        failures.add(String.format(
                                "line %d %s(%s, %s): python=%d java=%d",
                                r + 1, header[c], quote(s1), quote(s2), expected, actual));
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            fail(failures.size() + "+ mismatches out of " + comparisons + " comparisons:\n"
                    + String.join("\n", failures));
        }
        assertTrue(comparisons > 300_000, "fixture unexpectedly small: " + comparisons);
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                out.appendCodePoint(cp);
            } else {
                out.append(String.format("\\u{%04X}", cp));
            }
        });
        return out.append('"').toString();
    }

    @Test
    @DisplayName("the decorator stack makes ratio('', '') 100 but ratio('x', '') 0")
    void decoratorStackOrderIsObservable() {
        assertEquals(100, Fuzz.ratio("", ""));
        assertEquals(100, Fuzz.partialRatio("", ""));
        assertEquals(0, Fuzz.ratio("x", ""));
        assertEquals(0, Fuzz.ratio("", "x"));
        assertEquals(0, Fuzz.partialRatio("x", ""));
        assertEquals(0, Fuzz.ratio(null, "x"));
        assertEquals(0, Fuzz.ratio("x", null));
        assertEquals(0, Fuzz.ratio(null, null));
        assertEquals(100, Fuzz.ratio("identical", "identical"));
    }

    @Test
    @DisplayName("documented examples from the README hold")
    void readmeExamplesHold() {
        assertEquals(97, Fuzz.ratio("this is a test", "this is a test!"));
        assertEquals(100, Fuzz.partialRatio("this is a test", "this is a test!"));
        assertEquals(91, Fuzz.ratio("fuzzy wuzzy was a bear", "wuzzy fuzzy was a bear"));
        assertEquals(100, Fuzz.tokenSortRatio("fuzzy wuzzy was a bear", "wuzzy fuzzy was a bear"));
        assertEquals(84, Fuzz.tokenSortRatio("fuzzy was a bear", "fuzzy fuzzy was a bear"));
        assertEquals(100, Fuzz.tokenSetRatio("fuzzy was a bear", "fuzzy fuzzy was a bear"));
    }

    @Test
    @DisplayName("scores are computed over code points, not UTF-16 units")
    void astralInputIsMeasuredInCodePoints() {
        String astral = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02";
        assertEquals(3, Utils.len(astral));
        // Six UTF-16 units against four would score 80 either way, but the surrogate halves would
        // also match each other; only a code point view gives Python's 80 for every astral input.
        assertEquals(80, Fuzz.ratio(astral, "\uD83D\uDE00\uD83D\uDE01"));
        assertEquals(0, Fuzz.ratio("\uD83D\uDE00", "\uD83D\uDE10"));
    }

    @Test
    @DisplayName("the difflib fallback backend disagrees with Levenshtein, as in Python")
    void difflibBackendIsSelectable() {
        String[][] pairs = {
                {"beae", "abaebcdbe"},
                {"dddabd", "ecbdecdcd"},
                {"caacdcbecd", "cababdbc"},
                {"dcadd", "abbbabedb"},
                {"cedbacde", "ebebeeadb"},
        };
        int[] expectedLevenshtein = {46, 40, 67, 29, 47};
        int[] expectedDifflib = {31, 27, 44, 14, 35};

        try {
            for (int i = 0; i < pairs.length; i++) {
                Fuzz.useLevenshteinBackend();
                assertEquals(expectedLevenshtein[i], Fuzz.ratio(pairs[i][0], pairs[i][1]),
                        "Levenshtein backend on " + pairs[i][0] + "/" + pairs[i][1]);
                Fuzz.useDifflibBackend();
                assertEquals(expectedDifflib[i], Fuzz.ratio(pairs[i][0], pairs[i][1]),
                        "difflib backend on " + pairs[i][0] + "/" + pairs[i][1]);
            }
        } finally {
            Fuzz.useLevenshteinBackend();
        }
    }
}
