package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.model.ExtractedResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code process}/{@code fuzz} entry points that the ported reference suite never calls.
 *
 * <p>Python reaches every overload through keyword defaults, so one call site exercises them all.
 * Java has to spell each default out as a separate method, and the ported tests only happen to use
 * some of them. Every expectation below was taken from CPython running the original source package
 * with {@code python-Levenshtein} installed, not from the Java implementation. That extra install
 * matters: without it {@code fuzz.py} silently falls back to {@code difflib} and the same query
 * scores 60 rather than 63, which is the backend {@link Fuzz#useDifflibBackend()} selects.
 */
class ProcessApiSurfaceTest {

    private static final List<String> BASEBALL = List.of(
            "new york mets vs chicago cubs",
            "chicago cubs vs chicago white sox",
            "philladelphia phillies vs atlanta braves",
            "braves vs mets");

    private static final String QUERY = "new york mets at atlanta braves";

    private static final List<String> CONTAINS_DUPES = List.of(
            "Frodo Baggins", "Tom Sawyer", "Bilbo Baggin", "Samuel L. Jackson",
            "F. Baggins", "Frody Baggins", "Bilbo Baggins");

    private static Map<String, String> baseballByKey() {
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("a", "new york mets vs chicago cubs");
        choices.put("b", "chicago cubs vs chicago white sox");
        choices.put("c", "philladelphia phillies vs atlanta braves");
        choices.put("d", "braves vs mets");
        return choices;
    }

    private static List<String> choicesOf(List<? extends ExtractedResult<String>> results) {
        return results.stream().map(ExtractedResult::choice).toList();
    }

    private static List<Integer> scoresOf(List<? extends ExtractedResult<String>> results) {
        return results.stream().map(ExtractedResult::score).toList();
    }

    private static List<Object> keysOf(List<? extends ExtractedResult<String>> results) {
        return results.stream().map(ExtractedResult::key).toList();
    }

    @Test
    void unicodeScorerShortcutsSkipAsciiFolding() {
        String accented = "ABCD\u00c1";
        String plain = "ABCD";

        assertEquals(89, Fuzz.unicodeQuickRatio(accented, plain));
        assertEquals(89, Fuzz.unicodeWeightedRatio(accented, plain));
        assertEquals(100, Fuzz.quickRatio(accented, plain, true, true));
        assertEquals(100, Fuzz.weightedRatio(accented, plain, true, true));

        assertEquals(Fuzz.quickRatio(accented, plain, false, true),
                Fuzz.unicodeQuickRatio(accented, plain));
        assertEquals(Fuzz.weightedRatio(accented, plain, false, true),
                Fuzz.unicodeWeightedRatio(accented, plain));

        assertEquals(84, Fuzz.unicodeQuickRatio("new york mets", "new york city mets"));
        assertEquals(95, Fuzz.unicodeWeightedRatio("new york mets", "new york city mets"));
        assertEquals(80, Fuzz.unicodeQuickRatio("\u00e7a va?", "ca va?"));
        assertEquals(80, Fuzz.unicodeWeightedRatio("\u00e7a va?", "ca va?"));
        assertEquals(100, Fuzz.unicodeQuickRatio("mets", "METS"));
        assertEquals(100, Fuzz.unicodeWeightedRatio("mets", "METS"));
    }

    @Test
    void extractOverloadsDefaultToWeightedRatioAndLimitFive() {
        List<ExtractedResult<String>> defaults = Process.extract(QUERY, BASEBALL);
        assertEquals(List.of("braves vs mets", "new york mets vs chicago cubs",
                "philladelphia phillies vs atlanta braves",
                "chicago cubs vs chicago white sox"), choicesOf(defaults));
        assertEquals(List.of(86, 63, 59, 29), scoresOf(defaults));
        assertTrue(defaults.size() <= 5, "the sequence overload defaults to limit=5");

        List<ExtractedResult<String>> byRatio = Process.extract(QUERY, BASEBALL, BuiltinScorer.RATIO);
        assertEquals(List.of("new york mets vs chicago cubs",
                "philladelphia phillies vs atlanta braves", "braves vs mets",
                "chicago cubs vs chicago white sox"), choicesOf(byRatio));
        assertEquals(List.of(63, 56, 31, 28), scoresOf(byRatio));

        assertEquals(choicesOf(defaults), choicesOf(Process.extract(
                QUERY, BASEBALL, Process.DEFAULT_PROCESSOR, Process.DEFAULT_SCORER, 5)));
        assertEquals(choicesOf(byRatio), choicesOf(Process.extract(
                QUERY, BASEBALL, Process.DEFAULT_PROCESSOR, BuiltinScorer.RATIO, 5)));
    }

    @Test
    void extractOverloadsOnMappingsCarryTheirKeys() {
        List<ExtractedResult<String>> defaults = Process.extract(QUERY, baseballByKey());
        assertEquals(List.of("braves vs mets", "new york mets vs chicago cubs",
                "philladelphia phillies vs atlanta braves",
                "chicago cubs vs chicago white sox"), choicesOf(defaults));
        assertEquals(List.of(86, 63, 59, 29), scoresOf(defaults));
        assertEquals(List.of("d", "a", "c", "b"), keysOf(defaults));
        assertTrue(defaults.stream().allMatch(ExtractedResult::fromMapping));

        List<ExtractedResult<String>> byRatio =
                Process.extract(QUERY, baseballByKey(), BuiltinScorer.RATIO);
        assertEquals(List.of("new york mets vs chicago cubs",
                "philladelphia phillies vs atlanta braves", "braves vs mets",
                "chicago cubs vs chicago white sox"), choicesOf(byRatio));
        assertEquals(List.of(63, 56, 31, 28), scoresOf(byRatio));
        assertEquals(List.of("a", "c", "d", "b"), keysOf(byRatio));

        assertTrue(Process.extract(QUERY, BASEBALL).stream().noneMatch(ExtractedResult::fromMapping));
        assertNull(Process.extract(QUERY, BASEBALL).get(0).key());
    }

    @Test
    void extractBestsOverloadsDropEverythingUnderTheCutoff() {
        List<ExtractedResult<String>> fromList = Process.extractBests(QUERY, BASEBALL, 60);
        assertEquals(List.of("braves vs mets", "new york mets vs chicago cubs"),
                choicesOf(fromList));
        assertEquals(List.of(86, 63), scoresOf(fromList));
        assertTrue(fromList.stream().allMatch(r -> r.score() >= 60));

        List<ExtractedResult<String>> fromMap = Process.extractBests(QUERY, baseballByKey(), 60);
        assertEquals(choicesOf(fromList), choicesOf(fromMap));
        assertEquals(List.of(86, 63), scoresOf(fromMap));
        assertEquals(List.of("d", "a"), keysOf(fromMap));

        List<ExtractedResult<String>> limited = Process.extractBests(
                QUERY, baseballByKey(), Processors.FULL_PROCESS, BuiltinScorer.RATIO, 30, 2);
        assertEquals(List.of("new york mets vs chicago cubs",
                "philladelphia phillies vs atlanta braves"), choicesOf(limited));
        assertEquals(List.of(63, 56), scoresOf(limited));
        assertEquals(List.of("a", "c"), keysOf(limited));
        assertEquals(2, limited.size(), "an explicit limit truncates after sorting");
    }

    @Test
    void extractOneOverloadsReturnTheSingleBestMatch() {
        ExtractedResult<String> fromMap = Process.extractOne(QUERY, baseballByKey());
        assertEquals("braves vs mets", fromMap.choice());
        assertEquals(86, fromMap.score());
        assertEquals("d", fromMap.key());

        ExtractedResult<String> byRatio =
                Process.extractOne(QUERY, baseballByKey(), BuiltinScorer.RATIO);
        assertEquals("new york mets vs chicago cubs", byRatio.choice());
        assertEquals(63, byRatio.score());
        assertEquals("a", byRatio.key());

        ExtractedResult<String> fromList = Process.extractOne(QUERY, BASEBALL, BuiltinScorer.RATIO);
        assertEquals(byRatio.choice(), fromList.choice());
        assertEquals(byRatio.score(), fromList.score());
        assertNull(fromList.key());
    }

    @Test
    void dedupeHonoursAnExplicitThreshold() {
        assertEquals(List.of("Frodo Baggins", "Tom Sawyer", "Bilbo Baggins", "Samuel L. Jackson"),
                Process.dedupe(CONTAINS_DUPES, 70));
        assertEquals(Process.dedupe(CONTAINS_DUPES), Process.dedupe(CONTAINS_DUPES, 70));

        assertEquals(List.of("Bilbo Baggins", "Tom Sawyer", "Samuel L. Jackson"),
                Process.dedupe(CONTAINS_DUPES, 50));

        assertSame(CONTAINS_DUPES, Process.dedupe(CONTAINS_DUPES, 99),
                "when nothing merges Python returns the caller's own list");
        assertEquals(CONTAINS_DUPES.size(), Process.dedupe(CONTAINS_DUPES, 99).size());
        assertTrue(Process.dedupe(CONTAINS_DUPES, 50).size()
                < Process.dedupe(CONTAINS_DUPES, 70).size(),
                "a lower threshold merges more aggressively");
    }
}
