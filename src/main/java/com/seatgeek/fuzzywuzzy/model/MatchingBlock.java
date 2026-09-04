package com.seatgeek.fuzzywuzzy.model;

/**
 * A run of identical code points, equivalent to a 3-tuple {@code (a, b, size)} from
 * {@code get_matching_blocks()}.
 *
 * <p>Both backends always terminate the list with a zero-length sentinel
 * {@code (len(s1), len(s2), 0)}, which is why {@code fuzz.partial_ratio} can assume the list is
 * never empty.
 *
 * @param srcPos  start index in the source sequence
 * @param destPos start index in the destination sequence
 * @param length  number of matching code points
 */
public record MatchingBlock(int srcPos, int destPos, int length) {

    @Override
    public String toString() {
        return "(" + srcPos + ", " + destPos + ", " + length + ")";
    }
}
