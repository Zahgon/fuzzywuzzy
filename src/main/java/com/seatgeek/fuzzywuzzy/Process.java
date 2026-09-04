package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyRepr;
import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.model.ExtractedResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Port of {@code fuzzywuzzy/process.py}: searching a collection of choices for the best matches to
 * a query.
 *
 * <p>Every method comes in a sequence-shaped overload taking an {@link Iterable} and a
 * mapping-shaped overload taking a {@link Map}. Python distinguishes the two at runtime by trying
 * {@code choices.items()} and catching {@code AttributeError}; Java resolves it at compile time.
 * The mapping overloads produce results carrying their key, matching Python's 3-tuples.
 */
public final class Process {

    /** {@code process.py}'s {@code default_scorer = fuzz.WRatio}. */
    public static final Scorer DEFAULT_SCORER = BuiltinScorer.WEIGHTED_RATIO;

    /** {@code process.py}'s {@code default_processor = utils.full_process}. */
    public static final Processor DEFAULT_PROCESSOR = Processors.FULL_PROCESS;

    private static final Processor ASCII_PRE_PROCESSOR = input -> Utils.fullProcess(input, true);
    private static final Processor UNICODE_PRE_PROCESSOR = input -> Utils.fullProcess(input, false);

    private Process() {
    }

    private record Pipeline(Processor processor, Processor preProcessor, Scorer scorer, Object query) {

        int scoreOf(Object choice) {
            Object processed = preProcessor.process(processor.process(choice));
            return scorer.score(asString(query), asString(processed));
        }
    }

    /**
     * Reproduces the two identity-based dispatch blocks at the top of {@code extractWithoutOrder}.
     */
    private static Pipeline pipeline(Object query, Processor processor, Scorer scorer) {
        Processor effectiveProcessor = processor == null ? Processors.NO_PROCESS : processor;

        Object processedQuery = effectiveProcessor.process(query);
        if (pyLen(processedQuery) == 0) {
            PyWarnings.logWarning("Applied processor reduces input query to empty string, "
                    + "all comparisons will have score 0. "
                    + "[Query: '" + PyRepr.str(query) + "']");
        }

        BuiltinScorer builtin = scorer instanceof BuiltinScorer candidate ? candidate : null;
        if (builtin != null && builtin.avoidsDoubleProcessing() && effectiveProcessor == Processors.FULL_PROCESS) {
            effectiveProcessor = Processors.NO_PROCESS;
        }

        Processor preProcessor;
        Scorer effectiveScorer;
        if (builtin != null && builtin.kind() == BuiltinScorer.Kind.UNICODE) {
            preProcessor = UNICODE_PRE_PROCESSOR;
            effectiveScorer = builtin.withoutFullProcess();
        } else if (builtin != null && builtin.kind() == BuiltinScorer.Kind.ASCII) {
            preProcessor = ASCII_PRE_PROCESSOR;
            effectiveScorer = builtin.withoutFullProcess();
        } else {
            preProcessor = Processors.NO_PROCESS;
            effectiveScorer = scorer;
        }

        return new Pipeline(effectiveProcessor, preProcessor, effectiveScorer,
                preProcessor.process(processedQuery));
    }

    /**
     * Python's {@code len()}, used both for the empty-query warning and the empty-choices check.
     *
     * @throws IllegalArgumentException where Python would raise {@code TypeError}
     */
    private static int pyLen(Object value) {
        if (value instanceof String s) {
            return PyStr.len(s);
        }
        if (value instanceof CharSequence s) {
            return PyStr.len(s.toString());
        }
        if (value instanceof Collection<?> c) {
            return c.size();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        if (value instanceof Object[] a) {
            return a.length;
        }
        throw new IllegalArgumentException("object of type '"
                + (value == null ? "NoneType" : value.getClass().getName()) + "' has no len()");
    }

    /**
     * The scorer boundary. Builtin scorers immediately call {@code utils.make_type_consistent},
     * which applies {@code str()} to both arguments unless both are already strings; since
     * {@code str()} of a string is that same string, converting each side independently is
     * equivalent.
     */
    private static String asString(Object value) {
        return value instanceof String s ? s : PyRepr.str(value);
    }

    /**
     * Python's guard {@code try: if choices is None or len(choices) == 0: return / except TypeError: pass}.
     * Unsized iterables such as generators fall through, exactly as the {@code TypeError} branch does.
     */
    private static boolean isDefinitelyEmpty(Object choices) {
        if (choices == null) {
            return true;
        }
        if (choices instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (choices instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    /**
     * Defers all work to the terminal operation, so that merely calling
     * {@code extractWithoutOrder} has no side effects. Python's version is a generator function,
     * whose body does not start executing until the first {@code next()}.
     */
    private static <R> Stream<R> defer(Supplier<Stream<R>> supplier) {
        return Stream.of((Object) null).flatMap(ignored -> supplier.get());
    }

    // ---------------------------------------------------------------- extractWithoutOrder

    /**
     * Scores every choice against the query, lazily and in encounter order.
     *
     * @param query       the thing to find
     * @param choices     the candidates
     * @param processor   maps query and choices to the values actually matched; {@code null} means no processing
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param <T>         type of the choices
     * @return a lazy stream of results, without a key
     */
    public static <T> Stream<ExtractedResult<T>> extractWithoutOrder(
            Object query, Iterable<T> choices, Processor processor, Scorer scorer, int scoreCutoff) {
        return defer(() -> {
            if (isDefinitelyEmpty(choices)) {
                return Stream.empty();
            }
            Pipeline pipeline = pipeline(query, processor, scorer);
            return StreamSupport.stream(choices.spliterator(), false)
                    .map(choice -> ExtractedResult.of(choice, pipeline.scoreOf(choice)))
                    .filter(result -> result.score() >= scoreCutoff);
        });
    }

    /**
     * Scores every mapping value against the query, lazily and in encounter order.
     *
     * @param query       the thing to find
     * @param choices     the candidates, keyed
     * @param processor   maps query and choices to the values actually matched; {@code null} means no processing
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param <K>         key type
     * @param <V>         value type
     * @return a lazy stream of results, each carrying its key
     */
    public static <K, V> Stream<ExtractedResult<V>> extractWithoutOrder(
            Object query, Map<K, V> choices, Processor processor, Scorer scorer, int scoreCutoff) {
        return defer(() -> {
            if (isDefinitelyEmpty(choices)) {
                return Stream.empty();
            }
            Pipeline pipeline = pipeline(query, processor, scorer);
            return choices.entrySet().stream()
                    .map(entry -> ExtractedResult.ofEntry(
                            entry.getValue(), pipeline.scoreOf(entry.getValue()), entry.getKey()))
                    .filter(result -> result.score() >= scoreCutoff);
        });
    }

    /**
     * Scores every choice using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates
     * @param <T>     type of the choices
     * @return a lazy stream of results
     */
    public static <T> Stream<ExtractedResult<T>> extractWithoutOrder(Object query, Iterable<T> choices) {
        return extractWithoutOrder(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 0);
    }

    /**
     * Scores every mapping value using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates, keyed
     * @param <K>     key type
     * @param <V>     value type
     * @return a lazy stream of results, each carrying its key
     */
    public static <K, V> Stream<ExtractedResult<V>> extractWithoutOrder(Object query, Map<K, V> choices) {
        return extractWithoutOrder(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 0);
    }

    // ---------------------------------------------------------------- ordering

    /**
     * Python's {@code heapq.nlargest(limit, sl, key=...)} and its {@code sorted(..., reverse=True)}
     * fallback. {@code nlargest} decorates each item with a decreasing counter before pushing it on
     * the heap, so equal scores come back in encounter order; that is the same result as a stable
     * descending sort, which is what both branches therefore reduce to.
     */
    private static <T> List<ExtractedResult<T>> rank(Stream<ExtractedResult<T>> results, Integer limit) {
        List<ExtractedResult<T>> all = results.collect(Collectors.toCollection(ArrayList::new));
        all.sort(Comparator.comparingInt((ExtractedResult<T> result) -> result.score()).reversed());
        if (limit == null) {
            return all;
        }
        if (limit <= 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    // ---------------------------------------------------------------- extract

    /**
     * Returns the best matches, highest score first.
     *
     * <p>Faithful to Python, this method has no {@code score_cutoff} parameter and does not forward
     * one to {@code extractWithoutOrder}; use {@link #extractBests} for that.
     *
     * @param query     the thing to find
     * @param choices   the candidates
     * @param processor maps query and choices to the values actually matched
     * @param scorer    the similarity function
     * @param limit     maximum number of results, or {@code null} for all of them
     * @param <T>       type of the choices
     * @return the ranked results
     */
    public static <T> List<ExtractedResult<T>> extract(
            Object query, Iterable<T> choices, Processor processor, Scorer scorer, Integer limit) {
        return rank(extractWithoutOrder(query, choices, processor, scorer, 0), limit);
    }

    /**
     * Returns the best matching mapping values, highest score first.
     *
     * @param query     the thing to find
     * @param choices   the candidates, keyed
     * @param processor maps query and choices to the values actually matched
     * @param scorer    the similarity function
     * @param limit     maximum number of results, or {@code null} for all of them
     * @param <K>       key type
     * @param <V>       value type
     * @return the ranked results, each carrying its key
     */
    public static <K, V> List<ExtractedResult<V>> extract(
            Object query, Map<K, V> choices, Processor processor, Scorer scorer, Integer limit) {
        return rank(extractWithoutOrder(query, choices, processor, scorer, 0), limit);
    }

    /**
     * Returns up to five best matches using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates
     * @param <T>     type of the choices
     * @return the ranked results
     */
    public static <T> List<ExtractedResult<T>> extract(Object query, Iterable<T> choices) {
        return extract(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 5);
    }

    /**
     * Returns up to five best matches using the default processor and the given scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates
     * @param scorer  the similarity function
     * @param <T>     type of the choices
     * @return the ranked results
     */
    public static <T> List<ExtractedResult<T>> extract(Object query, Iterable<T> choices, Scorer scorer) {
        return extract(query, choices, DEFAULT_PROCESSOR, scorer, 5);
    }

    /**
     * Returns up to five best matching mapping values using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates, keyed
     * @param <K>     key type
     * @param <V>     value type
     * @return the ranked results, each carrying its key
     */
    public static <K, V> List<ExtractedResult<V>> extract(Object query, Map<K, V> choices) {
        return extract(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 5);
    }

    /**
     * Returns up to five best matching mapping values using the default processor and the given scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates, keyed
     * @param scorer  the similarity function
     * @param <K>     key type
     * @param <V>     value type
     * @return the ranked results, each carrying its key
     */
    public static <K, V> List<ExtractedResult<V>> extract(Object query, Map<K, V> choices, Scorer scorer) {
        return extract(query, choices, DEFAULT_PROCESSOR, scorer, 5);
    }

    // ---------------------------------------------------------------- extractBests

    /**
     * Returns the best matches scoring at least {@code scoreCutoff}, highest score first.
     *
     * @param query       the thing to find
     * @param choices     the candidates
     * @param processor   maps query and choices to the values actually matched
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param limit       maximum number of results, or {@code null} for all of them
     * @param <T>         type of the choices
     * @return the ranked results
     */
    public static <T> List<ExtractedResult<T>> extractBests(
            Object query, Iterable<T> choices, Processor processor, Scorer scorer, int scoreCutoff, Integer limit) {
        return rank(extractWithoutOrder(query, choices, processor, scorer, scoreCutoff), limit);
    }

    /**
     * Returns the best matching mapping values scoring at least {@code scoreCutoff}.
     *
     * @param query       the thing to find
     * @param choices     the candidates, keyed
     * @param processor   maps query and choices to the values actually matched
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param limit       maximum number of results, or {@code null} for all of them
     * @param <K>         key type
     * @param <V>         value type
     * @return the ranked results, each carrying its key
     */
    public static <K, V> List<ExtractedResult<V>> extractBests(
            Object query, Map<K, V> choices, Processor processor, Scorer scorer, int scoreCutoff, Integer limit) {
        return rank(extractWithoutOrder(query, choices, processor, scorer, scoreCutoff), limit);
    }

    /**
     * Returns up to five best matches scoring at least {@code scoreCutoff}.
     *
     * @param query       the thing to find
     * @param choices     the candidates
     * @param scoreCutoff results scoring below this are omitted
     * @param <T>         type of the choices
     * @return the ranked results
     */
    public static <T> List<ExtractedResult<T>> extractBests(Object query, Iterable<T> choices, int scoreCutoff) {
        return extractBests(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, scoreCutoff, 5);
    }

    /**
     * Returns up to five best matching mapping values scoring at least {@code scoreCutoff}.
     *
     * @param query       the thing to find
     * @param choices     the candidates, keyed
     * @param scoreCutoff results scoring below this are omitted
     * @param <K>         key type
     * @param <V>         value type
     * @return the ranked results, each carrying its key
     */
    public static <K, V> List<ExtractedResult<V>> extractBests(Object query, Map<K, V> choices, int scoreCutoff) {
        return extractBests(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, scoreCutoff, 5);
    }

    // ---------------------------------------------------------------- extractOne

    /**
     * Returns the single best match, or {@code null} if there is none.
     *
     * <p>Python uses {@code max(best_list, key=...)}, which keeps the <em>first</em> element of a
     * tie, and turns the {@code ValueError} of an empty sequence into {@code None}.
     *
     * @param query       the thing to find
     * @param choices     the candidates
     * @param processor   maps query and choices to the values actually matched
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param <T>         type of the choices
     * @return the best result, or {@code null}
     */
    public static <T> ExtractedResult<T> extractOne(
            Object query, Iterable<T> choices, Processor processor, Scorer scorer, int scoreCutoff) {
        return firstMaximum(extractWithoutOrder(query, choices, processor, scorer, scoreCutoff));
    }

    /**
     * Returns the single best matching mapping value, or {@code null} if there is none.
     *
     * @param query       the thing to find
     * @param choices     the candidates, keyed
     * @param processor   maps query and choices to the values actually matched
     * @param scorer      the similarity function
     * @param scoreCutoff results scoring below this are omitted
     * @param <K>         key type
     * @param <V>         value type
     * @return the best result carrying its key, or {@code null}
     */
    public static <K, V> ExtractedResult<V> extractOne(
            Object query, Map<K, V> choices, Processor processor, Scorer scorer, int scoreCutoff) {
        return firstMaximum(extractWithoutOrder(query, choices, processor, scorer, scoreCutoff));
    }

    /**
     * Returns the single best match using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates
     * @param <T>     type of the choices
     * @return the best result, or {@code null}
     */
    public static <T> ExtractedResult<T> extractOne(Object query, Iterable<T> choices) {
        return extractOne(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 0);
    }

    /**
     * Returns the single best match using the default processor and the given scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates
     * @param scorer  the similarity function
     * @param <T>     type of the choices
     * @return the best result, or {@code null}
     */
    public static <T> ExtractedResult<T> extractOne(Object query, Iterable<T> choices, Scorer scorer) {
        return extractOne(query, choices, DEFAULT_PROCESSOR, scorer, 0);
    }

    /**
     * Returns the single best matching mapping value using the default processor and scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates, keyed
     * @param <K>     key type
     * @param <V>     value type
     * @return the best result carrying its key, or {@code null}
     */
    public static <K, V> ExtractedResult<V> extractOne(Object query, Map<K, V> choices) {
        return extractOne(query, choices, DEFAULT_PROCESSOR, DEFAULT_SCORER, 0);
    }

    /**
     * Returns the single best matching mapping value using the default processor and the given scorer.
     *
     * @param query   the thing to find
     * @param choices the candidates, keyed
     * @param scorer  the similarity function
     * @param <K>     key type
     * @param <V>     value type
     * @return the best result carrying its key, or {@code null}
     */
    public static <K, V> ExtractedResult<V> extractOne(Object query, Map<K, V> choices, Scorer scorer) {
        return extractOne(query, choices, DEFAULT_PROCESSOR, scorer, 0);
    }

    private static <T> ExtractedResult<T> firstMaximum(Stream<ExtractedResult<T>> results) {
        ExtractedResult<T> best = null;
        for (Iterator<ExtractedResult<T>> it = results.iterator(); it.hasNext(); ) {
            ExtractedResult<T> candidate = it.next();
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- dedupe

    /**
     * Removes fuzzy duplicates, keeping the longest spelling of each cluster.
     *
     * @param containsDupes the strings to deduplicate
     * @return the deduplicated list, or {@code containsDupes} itself when nothing was merged
     */
    public static List<String> dedupe(List<String> containsDupes) {
        return dedupe(containsDupes, 70, BuiltinScorer.TOKEN_SET_RATIO);
    }

    /**
     * Removes fuzzy duplicates at the given threshold.
     *
     * @param containsDupes the strings to deduplicate
     * @param threshold     score above which two strings count as duplicates
     * @return the deduplicated list, or {@code containsDupes} itself when nothing was merged
     */
    public static List<String> dedupe(List<String> containsDupes, int threshold) {
        return dedupe(containsDupes, threshold, BuiltinScorer.TOKEN_SET_RATIO);
    }

    /**
     * Removes fuzzy duplicates at the given threshold using the given scorer.
     *
     * <p>Two details of the Python original are preserved: the threshold comparison is strict
     * ({@code x[1] > threshold}), and the canonical spelling is chosen by an alphabetical sort
     * followed by a <em>stable</em> descending sort on length, so ties in length keep alphabetical
     * order. As in Python, when deduplication removes nothing the original list instance is
     * returned unchanged.
     *
     * @param containsDupes the strings to deduplicate
     * @param threshold     score above which two strings count as duplicates
     * @param scorer        the similarity function
     * @return the deduplicated list, or {@code containsDupes} itself when nothing was merged
     */
    public static List<String> dedupe(List<String> containsDupes, int threshold, Scorer scorer) {
        List<String> extractor = new ArrayList<>();

        for (String item : containsDupes) {
            List<ExtractedResult<String>> matches =
                    extract(item, containsDupes, DEFAULT_PROCESSOR, scorer, null);
            List<ExtractedResult<String>> filtered = matches.stream()
                    .filter(match -> match.score() > threshold)
                    .collect(Collectors.toCollection(ArrayList::new));

            if (filtered.size() == 1) {
                extractor.add(filtered.get(0).choice());
            } else {
                filtered.sort(Comparator.comparing(ExtractedResult::choice, PyStr.CODE_POINT_ORDER));
                filtered.sort(Comparator.comparingInt(
                        (ExtractedResult<String> match) -> PyStr.len(match.choice())).reversed());
                extractor.add(filtered.get(0).choice());
            }
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>(extractor);
        if (keys.size() == containsDupes.size()) {
            return containsDupes;
        }
        return new ArrayList<>(keys);
    }
}
