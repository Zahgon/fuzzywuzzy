package com.seatgeek.fuzzywuzzy.model;

/**
 * One range-based edit, equivalent to a 5-tuple {@code (tag, i1, i2, j1, j2)} from
 * {@code Levenshtein.opcodes} and from difflib's {@code SequenceMatcher.get_opcodes}.
 *
 * @param type      the kind of edit
 * @param srcBegin  inclusive start in the source sequence
 * @param srcEnd    exclusive end in the source sequence
 * @param destBegin inclusive start in the destination sequence
 * @param destEnd   exclusive end in the destination sequence
 */
public record Opcode(EditType type, int srcBegin, int srcEnd, int destBegin, int destEnd) {

    @Override
    public String toString() {
        return "('" + type.pythonName() + "', " + srcBegin + ", " + srcEnd
                + ", " + destBegin + ", " + destEnd + ")";
    }
}
