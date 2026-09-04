package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.model.ExtractedResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code ProcessTest} from test_fuzzywuzzy.py:312 and of test_fuzzywuzzy_pytest.py.
 */
class ProcessTest {

    private final List<String> baseballStrings = List.of(
            "new york mets vs chicago cubs",
            "chicago cubs vs chicago white sox",
            "philladelphia phillies vs atlanta braves",
            "braves vs mets");

    @Test
    void testGetBestChoice1() {
        ExtractedResult<String> best = Process.extractOne("new york mets at atlanta braves", baseballStrings);
        assertEquals("braves vs mets", best.choice());
    }

    @Test
    void testGetBestChoice2() {
        ExtractedResult<String> best =
                Process.extractOne("philadelphia phillies at atlanta braves", baseballStrings);
        assertEquals(baseballStrings.get(2), best.choice());
    }

    @Test
    void testGetBestChoice3() {
        ExtractedResult<String> best =
                Process.extractOne("atlanta braves at philadelphia phillies", baseballStrings);
        assertEquals(baseballStrings.get(2), best.choice());
    }

    @Test
    void testGetBestChoice4() {
        ExtractedResult<String> best = Process.extractOne("chicago cubs vs new york mets", baseballStrings);
        assertEquals(baseballStrings.get(0), best.choice());
    }

    @Test
    void testWithProcessor() {
        List<List<String>> events = List.of(
                List.of("chicago cubs vs new york mets", "CitiField", "2011-05-11", "8pm"),
                List.of("new york yankees vs boston red sox", "Fenway Park", "2011-05-11", "8pm"),
                List.of("atlanta braves vs pittsburgh pirates", "PNC Park", "2011-05-11", "8pm"));

        // The Python source writes a trailing comma, making the query a tuple wrapping one list.
        List<List<String>> query = List.of(
                List.of("new york mets vs chicago cubs", "CitiField", "2017-03-19", "8pm"));

        ExtractedResult<List<String>> best = Process.extractOne(
                query, events, event -> ((List<?>) event).get(0), Process.DEFAULT_SCORER, 0);

        assertSame(events.get(0), best.choice());
    }

    @Test
    void testWithScorer() {
        List<String> choices = List.of(
                "new york mets vs chicago cubs",
                "chicago cubs at new york mets",
                "atlanta braves vs pittsbugh pirates",
                "new york yankees vs boston red sox");

        Map<Integer, String> choicesDict = new LinkedHashMap<>();
        choicesDict.put(1, "new york mets vs chicago cubs");
        choicesDict.put(2, "chicago cubs vs chicago white sox");
        choicesDict.put(3, "philladelphia phillies vs atlanta braves");
        choicesDict.put(4, "braves vs mets");

        String query = "new york mets at chicago cubs";

        assertEquals(choices.get(1), Process.extractOne(query, choices).choice());
        assertEquals(choices.get(0), Process.extractOne(query, choices, BuiltinScorer.QUICK_RATIO).choice());
        assertEquals(choicesDict.get(1), Process.extractOne(query, choicesDict).choice());
    }

    @Test
    void testWithCutoff() {
        List<String> choices = List.of(
                "new york mets vs chicago cubs",
                "chicago cubs at new york mets",
                "atlanta braves vs pittsbugh pirates",
                "new york yankees vs boston red sox");

        ExtractedResult<String> best = Process.extractOne(
                "los angeles dodgers vs san francisco giants",
                choices, Process.DEFAULT_PROCESSOR, Process.DEFAULT_SCORER, 50);

        assertNull(best);
    }

    @Test
    void testWithCutoff2() {
        List<String> choices = List.of(
                "new york mets vs chicago cubs",
                "chicago cubs at new york mets",
                "atlanta braves vs pittsbugh pirates",
                "new york yankees vs boston red sox");

        ExtractedResult<String> res = Process.extractOne(
                "new york mets vs chicago cubs",
                choices, Process.DEFAULT_PROCESSOR, Process.DEFAULT_SCORER, 100);

        assertNotNull(res);
        assertSame(choices.get(0), res.choice());
    }

    @Test
    void testEmptyStrings() {
        List<String> choices = List.of(
                "",
                "new york mets vs chicago cubs",
                "new york yankees vs boston red sox",
                "",
                "");

        ExtractedResult<String> best = Process.extractOne("new york mets at chicago cubs", choices);
        assertEquals(choices.get(1), best.choice());
    }

    @Test
    void testNullStrings() {
        List<String> choices = Arrays.asList(
                null,
                "new york mets vs chicago cubs",
                "new york yankees vs boston red sox",
                null,
                null);

        ExtractedResult<String> best = Process.extractOne("new york mets at chicago cubs", choices);
        assertEquals(choices.get(1), best.choice());
    }

    @Test
    void testListLikeExtract() {
        Iterable<String> generated = () -> new Iterator<>() {
            private final String[] items = {"a", "Bb", "CcC"};
            private int index;

            @Override
            public boolean hasNext() {
                return index < items.length;
            }

            @Override
            public String next() {
                return items[index++];
            }
        };

        List<ExtractedResult<String>> result = Process.extract("aaa", generated);
        assertTrue(result.size() > 0);
    }

    @Test
    void testDictLikeExtract() {
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("aa", "bb");
        choices.put("a1", null);

        List<ExtractedResult<String>> result = Process.extract("aaa", choices);
        assertTrue(result.size() > 0);
        for (ExtractedResult<String> r : result) {
            assertTrue(choices.containsValue(r.choice()));
            assertTrue(r.fromMapping());
        }
    }

    @Test
    void testDedupe() {
        List<String> containsDupes = List.of(
                "Frodo Baggins", "Tom Sawyer", "Bilbo Baggin", "Samuel L. Jackson",
                "F. Baggins", "Frody Baggins", "Bilbo Baggins");

        assertTrue(Process.dedupe(containsDupes).size() < containsDupes.size());

        List<String> noDupes = new ArrayList<>(List.of("Tom", "Dick", "Harry"));
        assertEquals(List.of("Tom", "Dick", "Harry"), Process.dedupe(noDupes));
    }

    @Test
    void testSimplematch() {
        String basicString = "a, b";
        List<String> matchStrings = List.of("a, b");

        ExtractedResult<String> result =
                Process.extractOne(basicString, matchStrings, BuiltinScorer.RATIO);
        ExtractedResult<String> partResult =
                Process.extractOne(basicString, matchStrings, BuiltinScorer.PARTIAL_RATIO);

        assertEquals("a, b", result.choice());
        assertEquals(100, result.score());
        assertEquals("a, b", partResult.choice());
        assertEquals(100, partResult.score());
    }

    /**
     * Port of {@code test_process_warning} from test_fuzzywuzzy_pytest.py:4.
     */
    @Test
    void testProcessWarning() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PyWarnings.setSink(sink);
        try {
            Process.extractOne(":::::::", List.of(":::::::"));
        } finally {
            PyWarnings.setSink(null);
        }
        sink.flush();

        String expected = "WARNING:root:Applied processor reduces "
                + "input query to empty string, "
                + "all comparisons will have score 0. "
                + "[Query: ':::::::']\n";
        assertEquals(expected, captured.toString(StandardCharsets.UTF_8));
    }
}
