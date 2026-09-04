package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.model.ExtractedResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential test of {@link Process} against a fixture recorded from {@code fuzzywuzzy/process.py}
 * running under the source repository's own interpreter.
 *
 * <p>Regenerate the fixture with the {@code generate_golden_process.py} capture script recorded
 * in {@code tools/README.md}; it has to run under CPython against the original library.
 */
class ProcessGoldenTest {

    private static final String FIXTURE = "/golden/process.tsv.gz";

    private static final Map<String, List<String>> POOLS = new LinkedHashMap<>();
    private static final List<String[]> ROWS = new ArrayList<>();

    private static final Map<String, BuiltinScorer> SCORERS = Map.of(
            "ratio", BuiltinScorer.RATIO,
            "partial_ratio", BuiltinScorer.PARTIAL_RATIO,
            "token_sort_ratio", BuiltinScorer.TOKEN_SORT_RATIO,
            "partial_token_sort_ratio", BuiltinScorer.PARTIAL_TOKEN_SORT_RATIO,
            "token_set_ratio", BuiltinScorer.TOKEN_SET_RATIO,
            "partial_token_set_ratio", BuiltinScorer.PARTIAL_TOKEN_SET_RATIO,
            "QRatio", BuiltinScorer.QUICK_RATIO,
            "UQRatio", BuiltinScorer.UNICODE_QUICK_RATIO,
            "WRatio", BuiltinScorer.WEIGHTED_RATIO,
            "UWRatio", BuiltinScorer.UNICODE_WEIGHTED_RATIO);

    private static final PrintStream DISCARD =
            new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

    /**
     * Many fixture rows use a query that {@code full_process} empties, and each one legitimately
     * logs a warning. Discard them so the build log stays readable;
     * {@link #emptyQueryWarningIsByteIdentical()} asserts the text that is being suppressed.
     */
    @BeforeAll
    static void silenceWarnings() {
        PyWarnings.setSink(DISCARD);
    }

    @AfterAll
    static void restoreWarnings() {
        PyWarnings.setSink(null);
    }

    @BeforeAll
    static void loadFixture() throws Exception {
        for (String[] fields : GoldenFixture.readRows(FIXTURE)) {
            if ("POOL".equals(fields[0])) {
                List<String> items = new ArrayList<>();
                if (!fields[2].isEmpty()) {
                    for (String encoded : fields[2].split(";", -1)) {
                        items.add(GoldenFixture.decode(encoded));
                    }
                }
                POOLS.put(fields[1], items);
            } else {
                ROWS.add(fields);
            }
        }
        assertTrue(POOLS.size() >= 8, "expected the pool definitions");
        assertTrue(ROWS.size() > 14000, "expected the recorded scenarios");
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(List<ExtractedResult<String>> results) {
        if (results.isEmpty()) {
            return "EMPTY";
        }
        return results.stream()
                .map(result -> b64(result.choice()) + ":" + result.score())
                .collect(Collectors.joining(";"));
    }

    private static String encodeKeyed(List<ExtractedResult<String>> results) {
        if (results.isEmpty()) {
            return "EMPTY";
        }
        return results.stream()
                .map(result -> b64(result.choice()) + ":" + result.score()
                        + ":" + b64(String.valueOf(result.key())))
                .collect(Collectors.joining(";"));
    }

    private static Map<String, String> asMapping(List<String> items) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            mapping.put("k" + index, items.get(index));
        }
        return mapping;
    }

    private record Failure(String[] row, String expected, String actual) {
    }

    /**
     * Runs every fixture row whose op is in {@code ops} and reports all mismatches at once.
     */
    private void check(String label, java.util.Set<String> ops,
                       java.util.function.Function<String[], String> evaluate) {
        List<Failure> failures = new ArrayList<>();
        int checked = 0;
        for (String[] row : ROWS) {
            if (!ops.contains(row[0])) {
                continue;
            }
            checked++;
            String expected = row[6];
            String actual;
            try {
                actual = evaluate.apply(row);
            } catch (IndexOutOfBoundsException e) {
                actual = "ERROR:IndexError";
            } catch (RuntimeException e) {
                actual = "ERROR:" + e.getClass().getSimpleName();
            }
            if (!expected.equals(actual)) {
                failures.add(new Failure(row, expected, actual));
            }
        }
        assertTrue(checked > 0, label + ": no rows exercised");
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder(label + ": " + failures.size()
                    + " of " + checked + " scenarios diverged from Python\n");
            for (Failure failure : failures.subList(0, Math.min(15, failures.size()))) {
                message.append("  op=").append(failure.row()[0])
                        .append(" scorer=").append(failure.row()[1])
                        .append(" limit=").append(failure.row()[2])
                        .append(" cutoff=").append(failure.row()[3])
                        .append(" pool=").append(failure.row()[5])
                        .append(" query=").append(describe(failure.row()[4]))
                        .append("\n    python: ").append(abbreviate(failure.expected()))
                        .append("\n    java:   ").append(abbreviate(failure.actual()))
                        .append('\n');
            }
            fail(message.toString());
        }
        System.out.println(label + ": " + checked + " scenarios matched Python");
    }

    private static String describe(String encodedQuery) {
        return "-".equals(encodedQuery) ? "-" : "'" + GoldenFixture.decode(encodedQuery) + "'";
    }

    private static String abbreviate(String value) {
        return value.length() <= 220 ? value : value.substring(0, 220) + "...(" + value.length() + " chars)";
    }

    private static Integer parseLimit(String value) {
        return "None".equals(value) || "-".equals(value) ? null : Integer.valueOf(value);
    }

    @Test
    @DisplayName("extractWithoutOrder yields Python's choices, scores and order")
    void extractWithoutOrderMatchesPython() {
        check("extractWithoutOrder", java.util.Set.of("wo"), row -> {
            List<String> pool = POOLS.get(row[5]);
            String query = GoldenFixture.decode(row[4]);
            return encode(Process.extractWithoutOrder(
                            query, pool, Process.DEFAULT_PROCESSOR, SCORERS.get(row[1]), 0)
                    .collect(Collectors.toList()));
        });
    }

    @Test
    @DisplayName("extract ranks and truncates exactly like heapq.nlargest")
    void extractMatchesPython() {
        check("extract", java.util.Set.of("extract"), row -> {
            List<String> pool = POOLS.get(row[5]);
            String query = GoldenFixture.decode(row[4]);
            return encode(Process.extract(
                    query, pool, Process.DEFAULT_PROCESSOR, SCORERS.get(row[1]), parseLimit(row[2])));
        });
    }

    @Test
    @DisplayName("extractBests applies score_cutoff exactly like Python")
    void extractBestsMatchesPython() {
        check("extractBests", java.util.Set.of("bests"), row -> {
            List<String> pool = POOLS.get(row[5]);
            String query = GoldenFixture.decode(row[4]);
            return encode(Process.extractBests(
                    query, pool, Process.DEFAULT_PROCESSOR, SCORERS.get(row[1]),
                    Integer.parseInt(row[3]), parseLimit(row[2])));
        });
    }

    @Test
    @DisplayName("extractOne picks the first maximum and returns null on no match")
    void extractOneMatchesPython() {
        check("extractOne", java.util.Set.of("one"), row -> {
            List<String> pool = POOLS.get(row[5]);
            String query = GoldenFixture.decode(row[4]);
            ExtractedResult<String> best = Process.extractOne(
                    query, pool, Process.DEFAULT_PROCESSOR, SCORERS.get(row[1]),
                    Integer.parseInt(row[3]));
            return best == null ? "NONE" : encode(List.of(best));
        });
    }

    @Test
    @DisplayName("mapping choices yield keyed results in Python's order")
    void mappingChoicesMatchPython() {
        check("mapping", java.util.Set.of("wo_map", "extract_map", "one_map"), row -> {
            Map<String, String> mapping = asMapping(POOLS.get(row[5]));
            String query = GoldenFixture.decode(row[4]);
            BuiltinScorer scorer = SCORERS.get(row[1]);
            switch (row[0]) {
                case "wo_map" -> {
                    return encodeKeyed(Process.extractWithoutOrder(
                                    query, mapping, Process.DEFAULT_PROCESSOR, scorer, 0)
                            .collect(Collectors.toList()));
                }
                case "extract_map" -> {
                    return encodeKeyed(Process.extract(
                            query, mapping, Process.DEFAULT_PROCESSOR, scorer, null));
                }
                default -> {
                    ExtractedResult<String> best = Process.extractOne(
                            query, mapping, Process.DEFAULT_PROCESSOR, scorer, 0);
                    return best == null ? "NONE" : encodeKeyed(List.of(best));
                }
            }
        });
    }

    @Test
    @DisplayName("dedupe reproduces Python's output, including its IndexError")
    void dedupeMatchesPython() {
        check("dedupe", java.util.Set.of("dedupe"), row -> {
            List<String> pool = POOLS.get(row[5]);
            if (pool == null) {
                pool = extraDedupePool(row[5]);
            }
            List<String> result = Process.dedupe(pool, Integer.parseInt(row[3]), SCORERS.get(row[1]));
            if (result.isEmpty()) {
                return "EMPTY";
            }
            return result.stream().map(ProcessGoldenTest::b64).collect(Collectors.joining(";"));
        });
    }

    private static List<String> extraDedupePool(String name) {
        return switch (name) {
            case "dupes" -> List.of("Frodo Baggin", "Frodo Baggins", "F. Baggins",
                    "Samwise G.", "Gandalf", "Bilbo Baggins");
            case "nodupes" -> List.of("Tom", "Dick", "Harry");
            case "names" -> List.of("Frodo Baggins", "Tom Sawyer", "Bilbo Baggin",
                    "Samuel L. Jackson", "F. Baggins", "Frody Baggins", "Bilbo Baggins");
            default -> throw new IllegalArgumentException("unknown dedupe pool " + name);
        };
    }

    @Test
    @DisplayName("dedupe returns the very same list instance when nothing is merged")
    void dedupeReturnsOriginalListWhenNothingMerged() {
        List<String> input = List.of("Tom", "Dick", "Harry");
        assertTrue(input == Process.dedupe(input), "Python returns contains_dupes itself");
    }

    @Test
    @DisplayName("extractOne returns the caller's own choice object, not a processed copy")
    void extractOnePreservesChoiceIdentity() {
        List<String> choices = new ArrayList<>();
        choices.add(new String("new york mets"));
        choices.add(new String("new york yankees"));
        ExtractedResult<String> best = Process.extractOne("new york mets", choices);
        assertNotNull(best);
        assertTrue(best.choice() == choices.get(0),
                "ProcessTest.testWithCutoff2 asserts 'best_match is choices[0]'");
    }

    @Test
    @DisplayName("unsized iterables such as generators are accepted")
    void unsizedIterablesAreAccepted() {
        Iterable<String> generator = () -> List.of("aa", "bb").iterator();
        List<ExtractedResult<String>> results = Process.extractWithoutOrder(
                        "aa", generator, Process.DEFAULT_PROCESSOR, BuiltinScorer.WEIGHTED_RATIO, 0)
                .collect(Collectors.toList());
        assertEquals(2, results.size());
        assertEquals(100, results.get(0).score());
        assertEquals(0, results.get(1).score());
    }

    @Test
    @DisplayName("extract ignores score_cutoff while extractBests honours it")
    void extractIgnoresScoreCutoff() {
        List<String> choices = List.of("aa", "zz");
        assertEquals(2, Process.extract("aa", choices, Process.DEFAULT_PROCESSOR,
                BuiltinScorer.WEIGHTED_RATIO, null).size());
        assertEquals(1, Process.extractBests("aa", choices, Process.DEFAULT_PROCESSOR,
                BuiltinScorer.WEIGHTED_RATIO, 50, null).size());
    }

    @Test
    @DisplayName("a custom processor selecting a field stringifies the result Python-style")
    void customProcessorSeesStructuredChoices() {
        List<List<String>> events = List.of(
                List.of("chicago cubs vs new york mets", "CitiField", "2011-05-11", "8pm"),
                List.of("new york yankees vs boston red sox", "Fenway Park", "2011-05-11", "8pm"),
                List.of("atlanta braves vs pittsburgh pirates", "PNC Park", "2011-05-11", "8pm"));
        Object query = List.of(events.get(0));

        ExtractedResult<List<String>> best = Process.extractOne(
                query, events, input -> ((List<?>) input).get(0),
                BuiltinScorer.WEIGHTED_RATIO, 0);

        assertNotNull(best);
        assertTrue(best.choice() == events.get(0), "ProcessTest.testWithProcessor expects events[0]");
        assertEquals(90, best.score());
    }

    @Test
    @DisplayName("fuzz.ratio is not in the dispatch list, so choices stay fully processed")
    void plainRatioKeepsTheProcessor() {
        ExtractedResult<String> best = Process.extractOne("a, b", List.of("a, b"), BuiltinScorer.RATIO);
        assertNotNull(best);
        assertEquals("a, b", best.choice());
        assertEquals(100, best.score());

        best = Process.extractOne("a, b", List.of("a, b"), BuiltinScorer.PARTIAL_RATIO);
        assertNotNull(best);
        assertEquals(100, best.score());
    }

    @Test
    @DisplayName("a null processor performs no processing at all")
    void nullProcessorIsANoop() {
        List<ExtractedResult<String>> results = Process.extractWithoutOrder(
                        "AA", List.of("AA", "aa"), null, BuiltinScorer.WEIGHTED_RATIO, 0)
                .collect(Collectors.toList());
        assertEquals(100, results.get(0).score());
        assertEquals(100, results.get(1).score());
    }

    @Test
    @DisplayName("the empty-query warning is byte-identical to Python's logging output")
    void emptyQueryWarningIsByteIdentical() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PyWarnings.setSink(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Process.extractWithoutOrder(":::::::", List.of("a", "b")).count();
        } finally {
            PyWarnings.setSink(DISCARD);
        }
        assertEquals("WARNING:root:Applied processor reduces input query to empty string, "
                        + "all comparisons will have score 0. [Query: ':::::::']\n",
                captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("empty choices are rejected before the query is processed, so nothing is logged")
    void emptyChoicesLogNoWarning() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PyWarnings.setSink(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Process.extractWithoutOrder(":::::::", List.<String>of()).count();
        } finally {
            PyWarnings.setSink(DISCARD);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("empty or null choices short-circuit before the query is ever processed")
    void emptyChoicesShortCircuit() {
        assertEquals(0, Process.extractWithoutOrder("x", List.<String>of()).count());
        assertEquals(0, Process.extractWithoutOrder("x", (Iterable<String>) null).count());
        assertEquals(0, Process.extractWithoutOrder("x", Map.<String, String>of()).count());
        assertEquals(null, Process.extractOne("x", List.<String>of()));
    }
}
