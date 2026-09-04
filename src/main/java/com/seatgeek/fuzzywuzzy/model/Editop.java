package com.seatgeek.fuzzywuzzy.model;

/**
 * One elementary edit, equivalent to a 3-tuple {@code (tag, spos, dpos)} from
 * {@code Levenshtein.editops}.
 *
 * @param type    the kind of edit; never {@link EditType#EQUAL}
 * @param srcPos  index into the source sequence
 * @param destPos index into the destination sequence
 */
public record Editop(EditType type, int srcPos, int destPos) {

    @Override
    public String toString() {
        return "('" + type.pythonName() + "', " + srcPos + ", " + destPos + ")";
    }
}
