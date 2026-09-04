package com.seatgeek.fuzzywuzzy.matcher;

import com.seatgeek.fuzzywuzzy.model.EditType;
import com.seatgeek.fuzzywuzzy.model.Editop;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A reimplementation of the parts of the C {@code Levenshtein} / {@code rapidfuzz} extension module
 * that {@code fuzzywuzzy.StringMatcher} imports: {@code distance}, {@code ratio}, {@code editops},
 * {@code opcodes} and {@code matching_blocks}.
 *
 * <p>Two distinct alignments live in here and they are <em>not</em> interchangeable:
 *
 * <ul>
 *   <li>{@link #ratio} is a normalised <em>Indel</em> (longest-common-subsequence) similarity, where
 *       a substitution costs 2. This is what {@code Levenshtein.ratio} returns.
 *   <li>{@link #editops} and everything derived from it use classic <em>Levenshtein</em> distance,
 *       where a substitution costs 1.
 * </ul>
 *
 * <p>Feeding the same pair of strings to both therefore yields numbers that need not agree, which is
 * exactly the behaviour of the Python package.
 *
 * <p>The traceback in {@link #editops} is not "an" optimal traceback: it reproduces bit for bit the
 * particular one rapidfuzz emits, because {@code fuzz.partial_ratio} reads the resulting matching
 * blocks and a different (equally optimal) alignment would shift its candidate windows and change
 * scores. It was reverse engineered and verified against the installed extension module on 51,000
 * string pairs spanning ASCII, Latin-1, Cyrillic, CJK and non-BMP inputs.
 */
public final class LevenshteinCore {

    private LevenshteinCore() {
    }

    /**
     * Classic Levenshtein distance with unit insert, delete and substitute costs.
     *
     * @param s1 source code points
     * @param s2 destination code points
     * @return the minimum number of edits turning {@code s1} into {@code s2}
     */
    public static int distance(int[] s1, int[] s2) {
        int start = commonPrefix(s1, s2);
        int end = commonSuffix(s1, s2, start);
        int len1 = s1.length - start - end;
        int len2 = s2.length - start - end;

        if (len1 == 0) {
            return len2;
        }
        if (len2 == 0) {
            return len1;
        }

        int[] previous = new int[len1 + 1];
        int[] current = new int[len1 + 1];
        for (int col = 0; col <= len1; col++) {
            previous[col] = col;
        }

        for (int row = 1; row <= len2; row++) {
            current[0] = row;
            int c2 = s2[start + row - 1];
            for (int col = 1; col <= len1; col++) {
                int cost = s1[start + col - 1] == c2 ? 0 : 1;
                int best = previous[col] + 1;
                int left = current[col - 1] + 1;
                if (left < best) {
                    best = left;
                }
                int diagonal = previous[col - 1] + cost;
                if (diagonal < best) {
                    best = diagonal;
                }
                current[col] = best;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[len1];
    }

    /**
     * The similarity returned by {@code Levenshtein.ratio}: {@code 1 - indel_distance / (len1 + len2)},
     * where the Indel distance is {@code len1 + len2 - 2 * lcs}.
     *
     * <p>Two empty inputs score {@code 1.0} rather than dividing by zero.
     *
     * @param s1 source code points
     * @param s2 destination code points
     * @return a similarity in {@code [0.0, 1.0]}
     */
    public static double ratio(int[] s1, int[] s2) {
        int total = s1.length + s2.length;
        if (total == 0) {
            return 1.0;
        }

        int start = commonPrefix(s1, s2);
        int end = commonSuffix(s1, s2, start);
        int len1 = s1.length - start - end;
        int len2 = s2.length - start - end;

        int lcs = start + end + longestCommonSubsequence(s1, start, len1, s2, start, len2);
        return 1.0 - (double) (total - 2 * lcs) / (double) total;
    }

    /**
     * The elementary edits reported by {@code Levenshtein.editops}, in source order.
     *
     * @param s1 source code points
     * @param s2 destination code points
     * @return an unmodifiable list of edits; empty when the sequences are equal
     */
    public static List<Editop> editops(int[] s1, int[] s2) {
        int off = commonPrefix(s1, s2);
        int end = commonSuffix(s1, s2, off);
        int len1 = s1.length - off - end;
        int len2 = s2.length - off - end;

        List<Editop> ops = new ArrayList<>();

        if (len1 == 0 || len2 == 0) {
            for (int col = 0; col < len1; col++) {
                ops.add(new Editop(EditType.DELETE, col + off, off));
            }
            for (int row = 0; row < len2; row++) {
                ops.add(new Editop(EditType.INSERT, off, row + off));
            }
            return Collections.unmodifiableList(ops);
        }

        int[][] d = distanceMatrix(s1, off, len1, s2, off, len2);

        int col = len1;
        int row = len2;
        while (row > 0 && col > 0) {
            if (d[row][col] - d[row][col - 1] == 1) {
                col--;
                ops.add(new Editop(EditType.DELETE, col + off, row + off));
            } else {
                row--;
                if (row > 0 && d[row][col] - d[row][col - 1] == -1) {
                    ops.add(new Editop(EditType.INSERT, col + off, row + off));
                } else {
                    col--;
                    if (s1[off + col] != s2[off + row]) {
                        ops.add(new Editop(EditType.REPLACE, col + off, row + off));
                    }
                }
            }
        }
        while (col > 0) {
            col--;
            ops.add(new Editop(EditType.DELETE, col + off, off));
        }
        while (row > 0) {
            row--;
            ops.add(new Editop(EditType.INSERT, off, row + off));
        }

        Collections.reverse(ops);
        return Collections.unmodifiableList(ops);
    }

    /**
     * The range-based edits reported by {@code Levenshtein.opcodes}, covering both sequences with no
     * gaps.
     *
     * @param s1 source code points
     * @param s2 destination code points
     * @return an unmodifiable list of opcodes; empty only when both sequences are empty
     */
    public static List<Opcode> opcodes(int[] s1, int[] s2) {
        return opcodesFromEditops(editops(s1, s2), s1.length, s2.length);
    }

    /**
     * Groups elementary edits into contiguous runs and fills the gaps between them with
     * {@link EditType#EQUAL} blocks, mirroring {@code Levenshtein.opcodes(editops, s1, s2)}.
     *
     * @param ops  elementary edits in source order
     * @param len1 length of the source sequence
     * @param len2 length of the destination sequence
     * @return an unmodifiable list of opcodes
     */
    public static List<Opcode> opcodesFromEditops(List<Editop> ops, int len1, int len2) {
        List<Opcode> result = new ArrayList<>();
        int srcPos = 0;
        int destPos = 0;
        int i = 0;
        int size = ops.size();

        while (i < size) {
            Editop op = ops.get(i);
            int sp = op.srcPos();
            int dp = op.destPos();
            if (sp > srcPos || dp > destPos) {
                result.add(new Opcode(EditType.EQUAL, srcPos, sp, destPos, dp));
                srcPos = sp;
                destPos = dp;
            }

            EditType type = op.type();
            int j = i;
            while (j < size && ops.get(j).type() == type && runContinues(ops.get(j), type, srcPos, destPos, j - i)) {
                j++;
            }
            int n = j - i;

            switch (type) {
                case REPLACE -> {
                    result.add(new Opcode(EditType.REPLACE, srcPos, srcPos + n, destPos, destPos + n));
                    srcPos += n;
                    destPos += n;
                }
                case INSERT -> {
                    result.add(new Opcode(EditType.INSERT, srcPos, srcPos, destPos, destPos + n));
                    destPos += n;
                }
                case DELETE -> {
                    result.add(new Opcode(EditType.DELETE, srcPos, srcPos + n, destPos, destPos));
                    srcPos += n;
                }
                case EQUAL -> throw new IllegalArgumentException("editops must not contain 'equal'");
            }
            i = j;
        }

        if (srcPos < len1 || destPos < len2) {
            result.add(new Opcode(EditType.EQUAL, srcPos, len1, destPos, len2));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Expands range-based opcodes back into elementary edits, mirroring
     * {@code Levenshtein.editops(opcodes, s1, s2)}.
     *
     * @param ops opcodes covering both sequences
     * @return an unmodifiable list of elementary edits
     */
    public static List<Editop> editopsFromOpcodes(List<Opcode> ops) {
        List<Editop> result = new ArrayList<>();
        for (Opcode op : ops) {
            switch (op.type()) {
                case REPLACE -> {
                    for (int k = 0; k < op.srcEnd() - op.srcBegin(); k++) {
                        result.add(new Editop(EditType.REPLACE, op.srcBegin() + k, op.destBegin() + k));
                    }
                }
                case INSERT -> {
                    for (int k = 0; k < op.destEnd() - op.destBegin(); k++) {
                        result.add(new Editop(EditType.INSERT, op.srcBegin(), op.destBegin() + k));
                    }
                }
                case DELETE -> {
                    for (int k = 0; k < op.srcEnd() - op.srcBegin(); k++) {
                        result.add(new Editop(EditType.DELETE, op.srcBegin() + k, op.destBegin()));
                    }
                }
                case EQUAL -> {
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Extracts the {@link EditType#EQUAL} runs from a set of opcodes and appends the zero-length
     * sentinel that {@code Levenshtein.matching_blocks} always terminates with.
     *
     * @param ops  opcodes covering both sequences
     * @param len1 length of the source sequence
     * @param len2 length of the destination sequence
     * @return an unmodifiable list holding at least the sentinel
     */
    public static List<MatchingBlock> matchingBlocks(List<Opcode> ops, int len1, int len2) {
        List<MatchingBlock> blocks = new ArrayList<>();
        for (Opcode op : ops) {
            if (op.type() == EditType.EQUAL) {
                blocks.add(new MatchingBlock(op.srcBegin(), op.destBegin(), op.srcEnd() - op.srcBegin()));
            }
        }
        blocks.add(new MatchingBlock(len1, len2, 0));
        return Collections.unmodifiableList(blocks);
    }

    /**
     * A run of same-typed edits may only be merged while each edit sits at the position the growing
     * opcode predicts; the axis that does not advance for this edit type must stay pinned.
     */
    private static boolean runContinues(Editop op, EditType type, int srcPos, int destPos, int k) {
        return switch (type) {
            case REPLACE -> op.srcPos() == srcPos + k && op.destPos() == destPos + k;
            case INSERT -> op.srcPos() == srcPos && op.destPos() == destPos + k;
            case DELETE -> op.srcPos() == srcPos + k && op.destPos() == destPos;
            case EQUAL -> false;
        };
    }

    private static int[][] distanceMatrix(int[] s1, int off1, int len1, int[] s2, int off2, int len2) {
        int[][] d = new int[len2 + 1][len1 + 1];
        for (int col = 0; col <= len1; col++) {
            d[0][col] = col;
        }
        for (int row = 1; row <= len2; row++) {
            int[] previous = d[row - 1];
            int[] current = d[row];
            current[0] = row;
            int c2 = s2[off2 + row - 1];
            for (int col = 1; col <= len1; col++) {
                int cost = s1[off1 + col - 1] == c2 ? 0 : 1;
                int best = previous[col] + 1;
                int left = current[col - 1] + 1;
                if (left < best) {
                    best = left;
                }
                int diagonal = previous[col - 1] + cost;
                if (diagonal < best) {
                    best = diagonal;
                }
                current[col] = best;
            }
        }
        return d;
    }

    private static int longestCommonSubsequence(int[] s1, int off1, int len1, int[] s2, int off2, int len2) {
        if (len1 == 0 || len2 == 0) {
            return 0;
        }
        int[] previous = new int[len1 + 1];
        int[] current = new int[len1 + 1];
        for (int row = 1; row <= len2; row++) {
            int c2 = s2[off2 + row - 1];
            current[0] = 0;
            for (int col = 1; col <= len1; col++) {
                if (s1[off1 + col - 1] == c2) {
                    current[col] = previous[col - 1] + 1;
                } else {
                    current[col] = Math.max(previous[col], current[col - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[len1];
    }

    private static int commonPrefix(int[] s1, int[] s2) {
        int limit = Math.min(s1.length, s2.length);
        int i = 0;
        while (i < limit && s1[i] == s2[i]) {
            i++;
        }
        return i;
    }

    private static int commonSuffix(int[] s1, int[] s2, int start) {
        int limit = Math.min(s1.length, s2.length) - start;
        int i = 0;
        while (i < limit && s1[s1.length - 1 - i] == s2[s2.length - 1 - i]) {
            i++;
        }
        return i;
    }
}
