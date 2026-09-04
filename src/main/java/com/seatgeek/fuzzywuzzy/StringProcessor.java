package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.internal.PyStr;

/**
 * Port of {@code fuzzywuzzy/string_processing.py}.
 *
 * <p>This class defines method to process strings in the most efficient way. Ideally all the methods
 * below use unicode strings for both input and output.
 */
public final class StringProcessor {

    private StringProcessor() {
    }

    /**
     * Port of {@code string_processing.py:19}.
     *
     * <p>The Python implementation is {@code re.compile(r"(?ui)\W").sub(" ", a_string)}.
     *
     * <p>Two details of that one-liner are load-bearing and are easy to get wrong:
     *
     * <ul>
     *   <li>Despite the docstring's claim that it replaces "any sequence of non letters and non
     *       numbers with a single white space", {@code re.sub} replaces <em>each</em> match
     *       individually. {@code "a--b"} becomes {@code "a  b"} with two spaces, not one. The
     *       reference test {@code test_dont_condense_whitespace} depends on this.
     *   <li>{@code \W} is the complement of {@code \w}, and {@code \w} includes {@code U+005F LOW
     *       LINE}. Underscores therefore survive processing, again contradicting the docstring.
     * </ul>
     *
     * @param aString string to process
     * @return the string with every non-word code point replaced by one space
     */
    public static String replaceNonLettersNonNumbersWithWhitespace(String aString) {
        int len = aString.length();
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; ) {
            int cp = aString.codePointAt(i);
            if (PyStr.isWord(cp)) {
                out.appendCodePoint(cp);
            } else {
                out.append(' ');
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * Port of {@code string_processing.py:28}, {@code staticmethod(str.strip)}.
     *
     * @param aString string to strip
     * @return the string without leading or trailing Python whitespace
     */
    public static String strip(String aString) {
        return PyStr.strip(aString);
    }

    /**
     * Port of {@code string_processing.py:29}, {@code staticmethod(str.lower)}.
     *
     * @param aString string to lowercase
     * @return the lowercased string
     */
    public static String toLowerCase(String aString) {
        return PyStr.lower(aString);
    }

    /**
     * Port of {@code string_processing.py:30}, {@code staticmethod(str.upper)}.
     *
     * @param aString string to uppercase
     * @return the uppercased string
     */
    public static String toUpperCase(String aString) {
        return PyStr.upper(aString);
    }
}
