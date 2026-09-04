package com.seatgeek.fuzzywuzzy;

/**
 * Package metadata, the Java counterpart of {@code fuzzywuzzy/__init__.py}.
 *
 * <p>The functionality lives in {@link Fuzz} (scoring) and {@link Process} (searching a collection
 * of choices), mirroring {@code fuzzywuzzy.fuzz} and {@code fuzzywuzzy.process}.
 */
public final class FuzzyWuzzy {

    /** Mirrors {@code fuzzywuzzy.__version__}, the upstream Python release this port tracks. */
    public static final String VERSION = "0.18.0";

    private FuzzyWuzzy() {
    }
}
