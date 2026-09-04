package com.seatgeek.fuzzywuzzy;

/**
 * Transforms a query or a choice before it is scored.
 *
 * <p>Java stand-in for the {@code processor} callable of {@code fuzzywuzzy/process.py}, described
 * there as "function of the form f(a) -&gt; b, where a is the query or individual choice and b is
 * the choice to be used in matching".
 *
 * <p>The parameter and return types are {@code Object} rather than {@code String} because
 * {@code process.py} explicitly supports processors that pick a field out of a structured choice,
 * such as {@code lambda x: x[0]}.
 */
@FunctionalInterface
public interface Processor {

    /**
     * Maps a query or choice to the value that should be matched.
     *
     * @param input the raw query or choice
     * @return the value to match on
     */
    Object process(Object input);
}
