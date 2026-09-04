package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyMath;
import com.seatgeek.fuzzywuzzy.internal.PyRepr;
import com.seatgeek.fuzzywuzzy.internal.PyStr;

/**
 * Port of {@code fuzzywuzzy/utils.py}.
 */
public final class Utils {

    private Utils() {
    }

    /**
     * Port of {@code utils.py:11}.
     *
     * <p>Check input has length and that length &gt; 0.
     *
     * <p>Python catches {@code TypeError} for inputs that have no length, such as {@code None} or an
     * {@code int}; the Java equivalent is a {@code null} or an object that is not a string.
     *
     * @param s value to check
     * @return {@code true} if {@code len(s) > 0}, else {@code false}
     */
    public static boolean validateString(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * Port of {@code utils.py:23}, the {@code check_for_equivalence} decorator.
     *
     * <p>Short-circuits to 100 when both arguments are equal, before the wrapped function ever runs.
     *
     * @param func function to wrap
     * @return the wrapped function
     */
    public static Scorer checkForEquivalence(Scorer func) {
        return (s1, s2) -> {
            if (s1 == null ? s2 == null : s1.equals(s2)) {
                return 100;
            }
            return func.score(s1, s2);
        };
    }

    /**
     * Port of {@code utils.py:32}, the {@code check_for_none} decorator.
     *
     * @param func function to wrap
     * @return the wrapped function, returning 0 when either argument is {@code null}
     */
    public static Scorer checkForNone(Scorer func) {
        return (s1, s2) -> {
            if (s1 == null || s2 == null) {
                return 0;
            }
            return func.score(s1, s2);
        };
    }

    /**
     * Port of {@code utils.py:41}, the {@code check_empty_string} decorator.
     *
     * <p>Mirrors Python's {@code len(args[0]) == 0}, which raises {@code TypeError} on {@code None};
     * this implementation throws {@link NullPointerException} for the same inputs.
     *
     * @param func function to wrap
     * @return the wrapped function, returning 0 when either argument is empty
     */
    public static Scorer checkEmptyString(Scorer func) {
        return (s1, s2) -> {
            if (s1.isEmpty() || s2.isEmpty()) {
                return 0;
            }
            return func.score(s1, s2);
        };
    }

    /**
     * Port of {@code utils.py:56}.
     *
     * <p>Python builds {@code bad_chars} from {@code range(128, 256)} and deletes exactly those code
     * points. The name and the comment ("ascii dammit!") suggest it strips everything non-ASCII, but
     * it does not: every code point from U+0100 upwards passes through untouched, so
     * {@code asciionly('a\xac\u1234\u20ac')} returns {@code 'a\u1234\u20ac'}.
     *
     * <p>This is a bug in the reference implementation. It is reproduced here deliberately, because
     * scores computed with {@code force_ascii=True} depend on it.
     *
     * @param s string to filter
     * @return the string with U+0080..U+00FF removed
     */
    public static String asciiOnly(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            if (cp < 0x80 || cp > 0xFF) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * Port of {@code utils.py:63}.
     *
     * <p>Under Python 3 the {@code unicode} branch is dead code, since {@code unicode} is aliased to
     * {@code str} at {@code utils.py:53}, so the function reduces to: strings are filtered directly,
     * everything else is stringified first and then filtered.
     *
     * @param s value to filter
     * @return the ASCII-damned string
     */
    public static String asciiDammit(Object s) {
        if (s instanceof String str) {
            return asciiOnly(str);
        }
        return asciiOnly(PyRepr.str(s));
    }

    /**
     * Port of {@code utils.py:79}.
     *
     * <p>If both objects aren't either both string or unicode instances force them to unicode.
     *
     * @param s1 first value
     * @param s2 second value
     * @return the pair, both rendered as strings
     */
    public static String[] makeTypeConsistent(Object s1, Object s2) {
        if (s1 instanceof String a && s2 instanceof String b) {
            return new String[]{a, b};
        }
        return new String[]{PyRepr.str(s1), PyRepr.str(s2)};
    }

    /**
     * Port of {@code utils.py:90} with Python's default {@code force_ascii=False}.
     *
     * @param s string to process
     * @return the processed string
     */
    public static String fullProcess(String s) {
        return fullProcess(s, false);
    }

    /**
     * Port of {@code utils.py:90}.
     *
     * <p>Process string by removing all but letters and numbers, trimming whitespace and forcing to
     * lower case; if {@code force_ascii == True}, force convert to ascii.
     *
     * <p>The order of the three steps is significant and is preserved exactly: replace, then
     * lowercase, then strip.
     *
     * @param s           string to process
     * @param forceAscii  whether to strip U+0080..U+00FF first
     * @return the processed string
     */
    public static String fullProcess(String s, boolean forceAscii) {
        return fullProcess((Object) s, forceAscii);
    }

    /**
     * Port of {@code utils.py:90} for values that are not strings.
     *
     * <p>{@code asciidammit} stringifies whatever it is handed, so with {@code force_ascii == true}
     * any object can be processed; {@code process.py} relies on this to turn {@code None} choices
     * into the string {@code "none"}. With {@code force_ascii == false} Python's {@code re.sub}
     * rejects the non-string, which is mirrored here as a {@link NullPointerException} or
     * {@link ClassCastException}.
     *
     * @param s           value to process
     * @param forceAscii  whether to strip U+0080..U+00FF first
     * @return the processed string
     */
    public static String fullProcess(Object s, boolean forceAscii) {
        String input = forceAscii ? asciiDammit(s) : (String) s;
        String stringOut = StringProcessor.replaceNonLettersNonNumbersWithWhitespace(input);
        stringOut = StringProcessor.toLowerCase(stringOut);
        return StringProcessor.strip(stringOut);
    }

    /**
     * Port of {@code utils.py:97}. Returns a correctly rounded integer.
     *
     * @param n value to round
     * @return {@code n} rounded half-to-even, as Python's {@code round()} does
     */
    public static long intr(double n) {
        return PyMath.intr(n);
    }

    /**
     * Python's {@code len(s)} in code points, exposed because the length comparisons in
     * {@code fuzz.WRatio} and {@code fuzz.partial_ratio} must agree with Python for astral input.
     *
     * @param s string to measure
     * @return number of code points
     */
    public static int len(String s) {
        return PyStr.len(s);
    }
}
