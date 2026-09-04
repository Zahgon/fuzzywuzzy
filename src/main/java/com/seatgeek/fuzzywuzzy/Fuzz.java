package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.matcher.DifflibSequenceMatcher;
import com.seatgeek.fuzzywuzzy.matcher.LevenshteinStringMatcher;
import com.seatgeek.fuzzywuzzy.matcher.SequenceMatcher;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * The scoring functions, a port of {@code fuzzywuzzy/fuzz.py}.
 *
 * <p>Every method here returns an integer between 0 and 100 inclusive and is a bit-for-bit match
 * for its Python counterpart, including the behaviours that look like bugs. See
 * {@code PORTING_NOTES.md}.
 */
public final class Fuzz {

    /**
     * fuzz.py binds the name {@code SequenceMatcher} once, at import time, to
     * {@code StringMatcher.StringMatcher} when python-Levenshtein is installed and to
     * {@code difflib.SequenceMatcher} otherwise. The two backends produce genuinely different
     * scores, so the choice is part of the observable behaviour rather than an optimisation.
     * python-Levenshtein is installed in the reference environment, so that is the default.
     */
    private static volatile BiFunction<String, String, SequenceMatcher> matcherFactory =
            LevenshteinStringMatcher::new;

    private Fuzz() {
    }

    /**
     * Switches every subsequent comparison to the python-Levenshtein backend. This is the default.
     */
    public static void useLevenshteinBackend() {
        matcherFactory = LevenshteinStringMatcher::new;
    }

    /**
     * Switches every subsequent comparison to the pure-Python {@code difflib} backend, reproducing
     * what fuzzywuzzy does when python-Levenshtein is not installed. Scores will differ from the
     * default backend for roughly one input pair in eight.
     */
    public static void useDifflibBackend() {
        matcherFactory = DifflibSequenceMatcher::new;
    }

    private static SequenceMatcher newMatcher(String s1, String s2) {
        return matcherFactory.apply(s1, s2);
    }

    /**
     * The similarity of two whole strings.
     *
     * <p>Ported from {@code fuzz.py:24}, wrapped in the same three decorators in the same order,
     * which is why {@code ratio("", "")} is 100 but {@code ratio("x", "")} is 0.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int ratio(String s1, String s2) {
        return RATIO.score(s1, s2);
    }

    private static final Scorer RATIO =
            Utils.checkForNone(Utils.checkForEquivalence(Utils.checkEmptyString(Fuzz::ratioImpl)));

    private static int ratioImpl(String s1, String s2) {
        String[] consistent = Utils.makeTypeConsistent(s1, s2);
        SequenceMatcher m = newMatcher(consistent[0], consistent[1]);
        return (int) Utils.intr(100 * m.ratio());
    }

    /**
     * The similarity of the most similar substring, as a number between 0 and 100.
     *
     * <p>Ported from {@code fuzz.py:34}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int partialRatio(String s1, String s2) {
        return PARTIAL_RATIO.score(s1, s2);
    }

    private static final Scorer PARTIAL_RATIO =
            Utils.checkForNone(Utils.checkForEquivalence(Utils.checkEmptyString(Fuzz::partialRatioImpl)));

    private static int partialRatioImpl(String s1, String s2) {
        String[] consistent = Utils.makeTypeConsistent(s1, s2);
        String a = consistent[0];
        String b = consistent[1];

        String shorter;
        String longer;
        if (Utils.len(a) <= Utils.len(b)) {
            shorter = a;
            longer = b;
        } else {
            shorter = b;
            longer = a;
        }

        List<MatchingBlock> blocks = newMatcher(shorter, longer).getMatchingBlocks();

        int[] longerPoints = PyStr.toCodePoints(longer);
        int shorterLength = Utils.len(shorter);

        // Each block is a run of matching characters (idx_1, idx_2, len). The best partial match
        // aligns with at least one of them, so it is enough to score the window of the longer
        // string that each block implies.
        List<Double> scores = new ArrayList<>(blocks.size());
        for (MatchingBlock block : blocks) {
            int delta = block.destPos() - block.srcPos();
            int longStart = delta > 0 ? delta : 0;
            int longEnd = longStart + shorterLength;
            // Python slicing clamps out-of-range bounds instead of throwing.
            String longSubstr = PyStr.slice(longerPoints, longStart, longEnd);

            double r = newMatcher(shorter, longSubstr).ratio();
            if (r > .995) {
                return 100;
            }
            scores.add(r);
        }

        double best = scores.get(0);
        for (double score : scores) {
            best = Math.max(best, score);
        }
        return (int) Utils.intr(100 * best);
    }

    /**
     * Cleans a string and sorts its tokens alphabetically.
     *
     * <p>Ported from {@code fuzz.py:75}.
     *
     * @param s           the string to clean
     * @param forceAscii  whether to strip the Latin-1 supplement first
     * @param fullProcess whether to run {@link Utils#fullProcess(String, boolean)} at all
     * @return the cleaned, token-sorted string
     */
    static String processAndSort(String s, boolean forceAscii, boolean fullProcess) {
        String ts = fullProcess ? Utils.fullProcess(s, forceAscii) : s;
        List<String> tokens = PyStr.split(ts);
        tokens.sort(PyStr.CODE_POINT_ORDER);
        return PyStr.strip(PyStr.join(" ", tokens));
    }

    /**
     * Sorts the tokens of both strings before comparing, which controls for word order.
     *
     * <p>Ported from {@code fuzz.py:91}. Carries {@code @utils.check_for_none} only.
     */
    private static int tokenSort(String s1, String s2, boolean partial, boolean forceAscii,
                                 boolean fullProcess) {
        if (s1 == null || s2 == null) {
            return 0;
        }

        String sorted1 = processAndSort(s1, forceAscii, fullProcess);
        String sorted2 = processAndSort(s2, forceAscii, fullProcess);

        return partial ? partialRatio(sorted1, sorted2) : ratio(sorted1, sorted2);
    }

    /**
     * A similarity between 0 and 100, sorting the tokens before comparing.
     *
     * <p>Ported from {@code fuzz.py:101}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int tokenSortRatio(String s1, String s2) {
        return tokenSortRatio(s1, s2, true, true);
    }

    /**
     * A similarity between 0 and 100, sorting the tokens before comparing.
     *
     * <p>Ported from {@code fuzz.py:101}.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to strip the Latin-1 supplement first
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int tokenSortRatio(String s1, String s2, boolean forceAscii, boolean fullProcess) {
        return tokenSort(s1, s2, false, forceAscii, fullProcess);
    }

    /**
     * The similarity of the most similar substring, sorting the tokens before comparing.
     *
     * <p>Ported from {@code fuzz.py:108}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int partialTokenSortRatio(String s1, String s2) {
        return partialTokenSortRatio(s1, s2, true, true);
    }

    /**
     * The similarity of the most similar substring, sorting the tokens before comparing.
     *
     * <p>Ported from {@code fuzz.py:108}.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to strip the Latin-1 supplement first
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int partialTokenSortRatio(String s1, String s2, boolean forceAscii,
                                            boolean fullProcess) {
        return tokenSort(s1, s2, true, forceAscii, fullProcess);
    }

    /**
     * Treats the tokens of each string as a set and compares
     * {@code <sorted_intersection><sorted_remainder>} constructions, which controls for unordered
     * partial matches.
     *
     * <p>Ported from {@code fuzz.py:116}. Carries {@code @utils.check_for_none} only.
     */
    private static int tokenSet(String s1, String s2, boolean partial, boolean forceAscii,
                                boolean fullProcess) {
        if (s1 == null || s2 == null) {
            return 0;
        }

        if (!fullProcess && Objects.equals(s1, s2)) {
            return 100;
        }

        String p1 = fullProcess ? Utils.fullProcess(s1, forceAscii) : s1;
        String p2 = fullProcess ? Utils.fullProcess(s2, forceAscii) : s2;

        if (!Utils.validateString(p1)) {
            return 0;
        }
        if (!Utils.validateString(p2)) {
            return 0;
        }

        Set<String> tokens1 = new HashSet<>(PyStr.split(p1));
        Set<String> tokens2 = new HashSet<>(PyStr.split(p2));

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);
        Set<String> diff1to2 = new HashSet<>(tokens1);
        diff1to2.removeAll(tokens2);
        Set<String> diff2to1 = new HashSet<>(tokens2);
        diff2to1.removeAll(tokens1);

        String sortedSect = joinSorted(intersection);
        String sorted1to2 = joinSorted(diff1to2);
        String sorted2to1 = joinSorted(diff2to1);

        String combined1to2 = sortedSect + " " + sorted1to2;
        String combined2to1 = sortedSect + " " + sorted2to1;

        // The concatenation happens before the strip, so an empty intersection leaves a leading
        // space that is only removed here.
        sortedSect = PyStr.strip(sortedSect);
        combined1to2 = PyStr.strip(combined1to2);
        combined2to1 = PyStr.strip(combined2to1);

        int first = partial ? partialRatio(sortedSect, combined1to2) : ratio(sortedSect, combined1to2);
        int second = partial ? partialRatio(sortedSect, combined2to1) : ratio(sortedSect, combined2to1);
        int third = partial
                ? partialRatio(combined1to2, combined2to1)
                : ratio(combined1to2, combined2to1);

        return Math.max(first, Math.max(second, third));
    }

    private static String joinSorted(Set<String> tokens) {
        Set<String> ordered = new TreeSet<>(PyStr.CODE_POINT_ORDER);
        ordered.addAll(tokens);
        return PyStr.join(" ", new ArrayList<>(ordered));
    }

    /**
     * A similarity between 0 and 100 based on the token sets of both strings.
     *
     * <p>Ported from {@code fuzz.py:168}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int tokenSetRatio(String s1, String s2) {
        return tokenSetRatio(s1, s2, true, true);
    }

    /**
     * A similarity between 0 and 100 based on the token sets of both strings.
     *
     * <p>Ported from {@code fuzz.py:168}.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to strip the Latin-1 supplement first
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int tokenSetRatio(String s1, String s2, boolean forceAscii, boolean fullProcess) {
        return tokenSet(s1, s2, false, forceAscii, fullProcess);
    }

    /**
     * The partial-substring similarity based on the token sets of both strings.
     *
     * <p>Ported from {@code fuzz.py:172}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int partialTokenSetRatio(String s1, String s2) {
        return partialTokenSetRatio(s1, s2, true, true);
    }

    /**
     * The partial-substring similarity based on the token sets of both strings.
     *
     * <p>Ported from {@code fuzz.py:172}.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to strip the Latin-1 supplement first
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int partialTokenSetRatio(String s1, String s2, boolean forceAscii,
                                           boolean fullProcess) {
        return tokenSet(s1, s2, true, forceAscii, fullProcess);
    }

    /**
     * A quick similarity comparison that cleans both strings first and short circuits to 0 if
     * either becomes empty.
     *
     * <p>Ported from {@code fuzz.py:181}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int quickRatio(String s1, String s2) {
        return quickRatio(s1, s2, true, true);
    }

    /**
     * A quick similarity comparison that cleans both strings first and short circuits to 0 if
     * either becomes empty.
     *
     * <p>Ported from {@code fuzz.py:181}, known as {@code QRatio} in Python.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to allow only ASCII characters
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int quickRatio(String s1, String s2, boolean forceAscii, boolean fullProcess) {
        String p1 = fullProcess ? Utils.fullProcess(s1, forceAscii) : s1;
        String p2 = fullProcess ? Utils.fullProcess(s2, forceAscii) : s2;

        if (!Utils.validateString(p1)) {
            return 0;
        }
        if (!Utils.validateString(p2)) {
            return 0;
        }

        return ratio(p1, p2);
    }

    /**
     * {@link #quickRatio(String, String, boolean, boolean)} with {@code forceAscii} disabled.
     *
     * <p>Ported from {@code fuzz.py:210}, known as {@code UQRatio} in Python.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int unicodeQuickRatio(String s1, String s2) {
        return unicodeQuickRatio(s1, s2, true);
    }

    /**
     * {@link #quickRatio(String, String, boolean, boolean)} with {@code forceAscii} disabled.
     *
     * <p>Ported from {@code fuzz.py:210}, known as {@code UQRatio} in Python.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int unicodeQuickRatio(String s1, String s2, boolean fullProcess) {
        return quickRatio(s1, s2, false, fullProcess);
    }

    /**
     * A weighted combination of the other scorers, and the default scorer for
     * {@link Process}.
     *
     * <p>Steps in the order they occur:
     * <ol>
     *   <li>Clean both strings, short circuiting to 0 if either becomes empty.</li>
     *   <li>Take {@link #ratio(String, String)} of the two cleaned strings.</li>
     *   <li>Compare their lengths. If one is more than 1.5 times as long as the other, use the
     *       partial scorers and scale their results by 0.9 so that only a full match can reach
     *       100. If one is more than 8 times as long, scale by 0.6 instead.</li>
     *   <li>Run the remaining scorers, scaling every token-based comparison by 0.95 on top of any
     *       partial scalar.</li>
     *   <li>Return the highest value, rounded once at the very end.</li>
     * </ol>
     *
     * <p>Ported from {@code fuzz.py:224}.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int weightedRatio(String s1, String s2) {
        return weightedRatio(s1, s2, true, true);
    }

    /**
     * A weighted combination of the other scorers.
     *
     * <p>Ported from {@code fuzz.py:224}, known as {@code WRatio} in Python.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param forceAscii  whether to allow only ASCII characters
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int weightedRatio(String s1, String s2, boolean forceAscii, boolean fullProcess) {
        String p1 = fullProcess ? Utils.fullProcess(s1, forceAscii) : s1;
        String p2 = fullProcess ? Utils.fullProcess(s2, forceAscii) : s2;

        if (!Utils.validateString(p1)) {
            return 0;
        }
        if (!Utils.validateString(p2)) {
            return 0;
        }

        boolean tryPartial = true;
        double unbaseScale = .95;
        double partialScale = .90;

        double base = ratio(p1, p2);
        int len1 = Utils.len(p1);
        int len2 = Utils.len(p2);
        double lenRatio = (double) Math.max(len1, len2) / Math.min(len1, len2);

        if (lenRatio < 1.5) {
            tryPartial = false;
        }

        if (lenRatio > 8) {
            partialScale = .6;
        }

        if (tryPartial) {
            double partial = partialRatio(p1, p2) * partialScale;
            double ptsor = partialTokenSortRatio(p1, p2, true, false) * unbaseScale * partialScale;
            double ptser = partialTokenSetRatio(p1, p2, true, false) * unbaseScale * partialScale;

            return (int) Utils.intr(Math.max(base, Math.max(partial, Math.max(ptsor, ptser))));
        } else {
            double tsor = tokenSortRatio(p1, p2, true, false) * unbaseScale;
            double tser = tokenSetRatio(p1, p2, true, false) * unbaseScale;

            return (int) Utils.intr(Math.max(base, Math.max(tsor, tser)));
        }
    }

    /**
     * {@link #weightedRatio(String, String, boolean, boolean)} with {@code forceAscii} disabled.
     *
     * <p>Ported from {@code fuzz.py:302}, known as {@code UWRatio} in Python.
     *
     * @param s1 the first string, may be null
     * @param s2 the second string, may be null
     * @return a similarity between 0 and 100
     */
    public static int unicodeWeightedRatio(String s1, String s2) {
        return unicodeWeightedRatio(s1, s2, true);
    }

    /**
     * {@link #weightedRatio(String, String, boolean, boolean)} with {@code forceAscii} disabled.
     *
     * <p>Ported from {@code fuzz.py:302}, known as {@code UWRatio} in Python.
     *
     * @param s1          the first string, may be null
     * @param s2          the second string, may be null
     * @param fullProcess whether to clean the inputs, used to avoid double processing
     * @return a similarity between 0 and 100
     */
    public static int unicodeWeightedRatio(String s1, String s2, boolean fullProcess) {
        return weightedRatio(s1, s2, false, fullProcess);
    }
}
