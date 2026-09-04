package com.seatgeek.fuzzywuzzy.model;

import com.seatgeek.fuzzywuzzy.internal.PyRepr;

/**
 * One scored match produced by {@code com.seatgeek.fuzzywuzzy.Process}.
 *
 * <p>Python's {@code extractWithoutOrder} yields a 2-tuple {@code (choice, score)} when the choices
 * are a sequence and a 3-tuple {@code (choice, score, key)} when they are a mapping. Java has no
 * variable-arity tuple, so both shapes share this record and {@link #fromMapping()} says which one
 * it is. The flag is needed because a mapping may legitimately hold a {@code null} value or key,
 * so {@code key == null} alone cannot distinguish the two shapes.
 *
 * <p>{@code choice} is the original object taken from the input collection, never a processed copy;
 * {@code ProcessTest.testWithCutoff2} asserts reference identity against the input list.
 *
 * @param <T>         type of the choice
 * @param choice      the matched element, as supplied by the caller
 * @param score       similarity in {@code 0..100}
 * @param key         the mapping key, or {@code null} when the choices were a sequence
 * @param fromMapping whether the choices were a mapping
 */
public record ExtractedResult<T>(T choice, int score, Object key, boolean fromMapping) {

    /**
     * Builds the sequence-shaped result.
     *
     * @param choice the matched element
     * @param score  similarity in {@code 0..100}
     * @param <T>    type of the choice
     * @return a result with no key
     */
    public static <T> ExtractedResult<T> of(T choice, int score) {
        return new ExtractedResult<>(choice, score, null, false);
    }

    /**
     * Builds the mapping-shaped result.
     *
     * @param choice the matched value
     * @param score  similarity in {@code 0..100}
     * @param key    the key the value was stored under
     * @param <T>    type of the choice
     * @return a result carrying its key
     */
    public static <T> ExtractedResult<T> ofEntry(T choice, int score, Object key) {
        return new ExtractedResult<>(choice, score, key, true);
    }

    @Override
    public String toString() {
        String head = "(" + PyRepr.repr(choice) + ", " + score;
        return fromMapping ? head + ", " + PyRepr.repr(key) + ")" : head + ")";
    }
}
