package com.seatgeek.fuzzywuzzy;

/**
 * A similarity function comparing two strings and returning a score in {@code 0..100}.
 *
 * <p>This is the Java stand-in for the Python callables that {@code fuzzywuzzy/process.py} accepts
 * as its {@code scorer} argument, such as {@code fuzz.WRatio} or {@code fuzz.token_set_ratio}.
 */
@FunctionalInterface
public interface Scorer {

    /**
     * Scores a pair of strings.
     *
     * @param s1 first string
     * @param s2 second string
     * @return similarity in {@code 0..100}
     */
    int score(String s1, String s2);
}
