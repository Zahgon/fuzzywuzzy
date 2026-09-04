package com.seatgeek.fuzzywuzzy.matcher;

import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.internal.PyWarnings;
import com.seatgeek.fuzzywuzzy.model.Editop;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;

import java.util.List;

/**
 * Port of {@code fuzzywuzzy/StringMatcher.py}: "A SequenceMatcher-like class built on the top of
 * Levenshtein".
 *
 * <p>This is the backend {@code fuzz.py} actually uses whenever the {@code python-Levenshtein}
 * extension is importable, which it is in the source checkout, so it — and not
 * {@link DifflibSequenceMatcher} — defines the reference scores.
 *
 * <p>The memoisation faithfully reproduces an upstream bug. Every cache in the Python class is
 * guarded by {@code if not self._x:}, which is true for {@code None} but equally true for {@code 0},
 * {@code 0.0} and {@code []}. A pair of completely dissimilar strings therefore recomputes its ratio
 * on every call, and identical strings recompute their (empty) edit script on every call. Replacing
 * these with null checks would be a behaviour change in timing only, but the class is public API of
 * the package and the port keeps it observably identical.
 */
public final class LevenshteinStringMatcher implements SequenceMatcher {

    private String str1;
    private String str2;
    private int[] cp1;
    private int[] cp2;

    private Double ratio;
    private Integer distance;
    private List<Opcode> opcodes;
    private List<Editop> editops;
    private List<MatchingBlock> matchingBlocks;

    /**
     * Equivalent of {@code StringMatcher()} with both sequences empty.
     */
    public LevenshteinStringMatcher() {
        this(null, "", "");
    }

    /**
     * Equivalent of {@code StringMatcher(None, seq1, seq2)}, the only form {@code fuzz.py} uses.
     *
     * @param seq1 the source sequence
     * @param seq2 the destination sequence
     */
    public LevenshteinStringMatcher(String seq1, String seq2) {
        this(null, seq1, seq2);
    }

    /**
     * Equivalent of {@code StringMatcher(isjunk, seq1, seq2)}.
     *
     * @param isjunk accepted and ignored, exactly as upstream does; a non-{@code null} value emits the
     *               upstream warning, whose doubled negative is a typo in the original source
     * @param seq1   the source sequence
     * @param seq2   the destination sequence
     */
    public LevenshteinStringMatcher(Object isjunk, String seq1, String seq2) {
        if (isTruthy(isjunk)) {
            PyWarnings.warn("isjunk not NOT implemented, it will be ignored");
        }
        this.str1 = seq1;
        this.str2 = seq2;
        this.cp1 = PyStr.toCodePoints(seq1);
        this.cp2 = PyStr.toCodePoints(seq2);
        resetCache();
    }

    private void resetCache() {
        ratio = null;
        distance = null;
        opcodes = null;
        editops = null;
        matchingBlocks = null;
    }

    @Override
    public void setSeqs(String seq1, String seq2) {
        this.str1 = seq1;
        this.str2 = seq2;
        this.cp1 = PyStr.toCodePoints(seq1);
        this.cp2 = PyStr.toCodePoints(seq2);
        resetCache();
    }

    @Override
    public void setSeq1(String seq1) {
        this.str1 = seq1;
        this.cp1 = PyStr.toCodePoints(seq1);
        resetCache();
    }

    @Override
    public void setSeq2(String seq2) {
        this.str2 = seq2;
        this.cp2 = PyStr.toCodePoints(seq2);
        resetCache();
    }

    @Override
    public List<Opcode> getOpcodes() {
        if (opcodes == null || opcodes.isEmpty()) {
            if (editops != null && !editops.isEmpty()) {
                opcodes = LevenshteinCore.opcodesFromEditops(editops, cp1.length, cp2.length);
            } else {
                opcodes = LevenshteinCore.opcodes(cp1, cp2);
            }
        }
        return opcodes;
    }

    /**
     * @return the elementary edit script, mirroring {@code get_editops()}
     */
    public List<Editop> getEditops() {
        if (editops == null || editops.isEmpty()) {
            if (opcodes != null && !opcodes.isEmpty()) {
                editops = LevenshteinCore.editopsFromOpcodes(opcodes);
            } else {
                editops = LevenshteinCore.editops(cp1, cp2);
            }
        }
        return editops;
    }

    @Override
    public List<MatchingBlock> getMatchingBlocks() {
        if (matchingBlocks == null || matchingBlocks.isEmpty()) {
            matchingBlocks = LevenshteinCore.matchingBlocks(getOpcodes(), cp1.length, cp2.length);
        }
        return matchingBlocks;
    }

    @Override
    public double ratio() {
        if (ratio == null || ratio == 0.0) {
            ratio = LevenshteinCore.ratio(cp1, cp2);
        }
        return ratio;
    }

    /**
     * Identical to {@link #ratio()}. Upstream's comment reads "This is usually quick enough :o)".
     *
     * @return the same value as {@link #ratio()}
     */
    @Override
    public double quickRatio() {
        if (ratio == null || ratio == 0.0) {
            ratio = LevenshteinCore.ratio(cp1, cp2);
        }
        return ratio;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ArithmeticException when both sequences are empty, matching the {@code ZeroDivisionError}
     *                             CPython raises here
     */
    @Override
    public double realQuickRatio() {
        int len1 = cp1.length;
        int len2 = cp2.length;
        if (len1 + len2 == 0) {
            throw new ArithmeticException("division by zero");
        }
        return 2.0 * Math.min(len1, len2) / (len1 + len2);
    }

    /**
     * @return the Levenshtein distance between the sequences, mirroring {@code distance()}
     */
    public int distance() {
        if (distance == null || distance == 0) {
            distance = LevenshteinCore.distance(cp1, cp2);
        }
        return distance;
    }

    /**
     * @return the current source sequence
     */
    public String getSeq1() {
        return str1;
    }

    /**
     * @return the current destination sequence
     */
    public String getSeq2() {
        return str2;
    }

    /**
     * Python's notion of truth for the values {@code isjunk} realistically receives: {@code None},
     * {@code False} and empty containers are falsy, a callable is truthy.
     */
    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof CharSequence cs) {
            return !cs.isEmpty();
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof java.util.Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return true;
    }
}
