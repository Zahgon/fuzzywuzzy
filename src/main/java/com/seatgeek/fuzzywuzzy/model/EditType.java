package com.seatgeek.fuzzywuzzy.model;

import java.util.Locale;

/**
 * The kinds of edit operation reported by {@code Levenshtein.editops} and {@code Levenshtein.opcodes}.
 */
public enum EditType {

    /** A code point present in the source but not the destination. */
    DELETE,

    /** A code point present in the destination but not the source. */
    INSERT,

    /** A code point substituted for another. */
    REPLACE,

    /** A run common to both sequences. Only ever produced by {@code opcodes}, never by {@code editops}. */
    EQUAL;

    /**
     * The spelling Python uses for this operation, e.g. {@code "replace"}.
     *
     * @return the lowercase Python tag
     */
    public String pythonName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
