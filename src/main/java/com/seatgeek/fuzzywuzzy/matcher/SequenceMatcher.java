package com.seatgeek.fuzzywuzzy.matcher;

import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;

import java.util.List;

/**
 * The slice of difflib's {@code SequenceMatcher} protocol that {@code fuzzywuzzy.fuzz} relies on.
 *
 * <p>{@code fuzz.py} opens with
 *
 * <pre>{@code
 * try:
 *     from .StringMatcher import StringMatcher as SequenceMatcher
 * except ImportError:
 *     from difflib import SequenceMatcher
 * }</pre>
 *
 * so the module-level name {@code SequenceMatcher} is bound to whichever backend is available. This
 * interface is that binding point, and {@link LevenshteinStringMatcher} / {@link DifflibSequenceMatcher}
 * are the two implementations. They do <em>not</em> agree: measured over 20,000 random pairs against
 * the installed Python packages, {@code ratio()} differs on 12% of them and
 * {@code get_matching_blocks()} on 46%.
 *
 * <p>Only {@code SequenceMatcher(None, s1, s2)} construction plus {@link #ratio()} and
 * {@link #getMatchingBlocks()} are exercised by {@code fuzz.py}; the remaining members exist because
 * they are public API of the Python classes and are covered by the ported test suite.
 */
public interface SequenceMatcher {

    /**
     * Replaces both sequences and invalidates cached results.
     *
     * @param seq1 the new source sequence
     * @param seq2 the new destination sequence
     */
    void setSeqs(String seq1, String seq2);

    /**
     * Replaces the source sequence and invalidates cached results.
     *
     * @param seq1 the new source sequence
     */
    void setSeq1(String seq1);

    /**
     * Replaces the destination sequence and invalidates cached results.
     *
     * @param seq2 the new destination sequence
     */
    void setSeq2(String seq2);

    /**
     * @return the edit script as ranges covering both sequences
     */
    List<Opcode> getOpcodes();

    /**
     * @return the matching runs, always terminated by a zero-length sentinel
     */
    List<MatchingBlock> getMatchingBlocks();

    /**
     * @return a similarity in {@code [0.0, 1.0]}
     */
    double ratio();

    /**
     * @return an upper bound on {@link #ratio()} that is cheaper to compute
     */
    double quickRatio();

    /**
     * @return an even cheaper upper bound on {@link #ratio()}
     */
    double realQuickRatio();
}
