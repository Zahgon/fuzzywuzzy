package com.seatgeek.fuzzywuzzy.matcher;

import com.seatgeek.fuzzywuzzy.internal.PyStr;
import com.seatgeek.fuzzywuzzy.model.EditType;
import com.seatgeek.fuzzywuzzy.model.MatchingBlock;
import com.seatgeek.fuzzywuzzy.model.Opcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Port of CPython's {@code difflib.SequenceMatcher}, specialised to sequences of code points.
 *
 * <p>{@code fuzz.py} falls back to this class when {@code python-Levenshtein} cannot be imported. It
 * is a genuinely different algorithm from {@link LevenshteinStringMatcher} — a recursive
 * longest-matching-block decomposition rather than an edit-distance traceback — and it produces
 * different numbers, so which backend is installed is observable in {@code fuzz} scores. The port
 * exists so that a consumer who wants the pure-Python-fuzzywuzzy behaviour can ask for it explicitly.
 *
 * <p>Ratchets carried over verbatim from CPython:
 *
 * <ul>
 *   <li>{@code autojunk}: once the second sequence has at least 200 elements, any element occurring
 *       more than {@code len(b) // 100 + 1} times is dropped from the index. This is a heuristic that
 *       changes results, not just speed.
 *   <li>{@code set_seq1}/{@code set_seq2} short-circuit on <em>identity</em> ({@code a is self.a}),
 *       not equality.
 *   <li>Ratio is {@code 2.0 * matches / total}, defined as {@code 1.0} when both sequences are empty.
 * </ul>
 */
public final class DifflibSequenceMatcher implements SequenceMatcher {

    private static final Comparator<MatchingBlock> BLOCK_ORDER =
            Comparator.comparingInt(MatchingBlock::srcPos)
                    .thenComparingInt(MatchingBlock::destPos)
                    .thenComparingInt(MatchingBlock::length);

    private final IntPredicate isjunk;
    private final boolean autojunk;

    private String a;
    private String b;
    private int[] cpA;
    private int[] cpB;

    private Map<Integer, int[]> b2j;
    private Set<Integer> bjunk;
    private Set<Integer> bpopular;
    private Map<Integer, Integer> fullbcount;
    private List<MatchingBlock> matchingBlocks;
    private List<Opcode> opcodes;

    /**
     * Equivalent of {@code SequenceMatcher(None, a, b)}, the only form {@code fuzz.py} uses.
     *
     * @param a the source sequence
     * @param b the destination sequence
     */
    public DifflibSequenceMatcher(String a, String b) {
        this(null, a, b, true);
    }

    /**
     * Equivalent of {@code SequenceMatcher(isjunk, a, b, autojunk)}.
     *
     * @param isjunk   tests whether a code point of {@code b} should be ignored when seeding matches;
     *                 {@code null} means no element is junk
     * @param a        the source sequence
     * @param b        the destination sequence
     * @param autojunk whether to also treat over-frequent elements of a long {@code b} as junk
     */
    public DifflibSequenceMatcher(IntPredicate isjunk, String a, String b, boolean autojunk) {
        this.isjunk = isjunk;
        this.autojunk = autojunk;
        this.a = null;
        this.b = null;
        this.cpA = new int[0];
        this.cpB = new int[0];
        this.fullbcount = null;
        chainB();
        setSeq1(a);
        setSeq2(b);
    }

    @Override
    public void setSeqs(String seq1, String seq2) {
        setSeq1(seq1);
        setSeq2(seq2);
    }

    @Override
    public void setSeq1(String seq1) {
        if (seq1 == a) {
            return;
        }
        a = seq1;
        cpA = PyStr.toCodePoints(seq1);
        matchingBlocks = null;
        opcodes = null;
    }

    @Override
    public void setSeq2(String seq2) {
        if (seq2 == b) {
            return;
        }
        b = seq2;
        cpB = PyStr.toCodePoints(seq2);
        matchingBlocks = null;
        opcodes = null;
        fullbcount = null;
        chainB();
    }

    private void chainB() {
        Map<Integer, List<Integer>> indices = new HashMap<>();
        for (int i = 0; i < cpB.length; i++) {
            indices.computeIfAbsent(cpB[i], k -> new ArrayList<>()).add(i);
        }

        bjunk = new HashSet<>();
        if (isjunk != null) {
            for (Integer elt : indices.keySet()) {
                if (isjunk.test(elt)) {
                    bjunk.add(elt);
                }
            }
            indices.keySet().removeAll(bjunk);
        }

        bpopular = new HashSet<>();
        int n = cpB.length;
        if (autojunk && n >= 200) {
            int ntest = n / 100 + 1;
            for (Map.Entry<Integer, List<Integer>> entry : indices.entrySet()) {
                if (entry.getValue().size() > ntest) {
                    bpopular.add(entry.getKey());
                }
            }
            indices.keySet().removeAll(bpopular);
        }

        b2j = new HashMap<>(indices.size() * 2);
        for (Map.Entry<Integer, List<Integer>> entry : indices.entrySet()) {
            List<Integer> list = entry.getValue();
            int[] packed = new int[list.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = list.get(i);
            }
            b2j.put(entry.getKey(), packed);
        }
    }

    /**
     * The longest block of {@code a[alo:ahi]} matching {@code b[blo:bhi]}, earliest in {@code a} and
     * then earliest in {@code b}. Returns a zero-length block at {@code (alo, blo)} when nothing
     * matches.
     *
     * @param alo inclusive start in the source
     * @param ahi exclusive end in the source
     * @param blo inclusive start in the destination
     * @param bhi exclusive end in the destination
     * @return the winning block
     */
    public MatchingBlock findLongestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;

        int lb = cpB.length;
        int[] prevLen = new int[lb + 1];
        int[] prevStamp = new int[lb + 1];
        int[] curLen = new int[lb + 1];
        int[] curStamp = new int[lb + 1];
        java.util.Arrays.fill(prevStamp, Integer.MIN_VALUE);
        java.util.Arrays.fill(curStamp, Integer.MIN_VALUE);

        for (int i = alo; i < ahi; i++) {
            int[] js = b2j.get(cpA[i]);
            if (js != null) {
                for (int j : js) {
                    if (j < blo) {
                        continue;
                    }
                    if (j >= bhi) {
                        break;
                    }
                    int prior = j > 0 && prevStamp[j - 1] == i - 1 ? prevLen[j - 1] : 0;
                    int k = prior + 1;
                    curLen[j] = k;
                    curStamp[j] = i;
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            int[] swapLen = prevLen;
            prevLen = curLen;
            curLen = swapLen;
            int[] swapStamp = prevStamp;
            prevStamp = curStamp;
            curStamp = swapStamp;
        }

        while (besti > alo && bestj > blo
                && !bjunk.contains(cpB[bestj - 1])
                && cpA[besti - 1] == cpB[bestj - 1]) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && !bjunk.contains(cpB[bestj + bestsize])
                && cpA[besti + bestsize] == cpB[bestj + bestsize]) {
            bestsize++;
        }

        while (besti > alo && bestj > blo
                && bjunk.contains(cpB[bestj - 1])
                && cpA[besti - 1] == cpB[bestj - 1]) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && bjunk.contains(cpB[bestj + bestsize])
                && cpA[besti + bestsize] == cpB[bestj + bestsize]) {
            bestsize++;
        }

        return new MatchingBlock(besti, bestj, bestsize);
    }

    @Override
    public List<MatchingBlock> getMatchingBlocks() {
        if (matchingBlocks != null) {
            return matchingBlocks;
        }
        int la = cpA.length;
        int lb = cpB.length;

        ArrayList<int[]> queue = new ArrayList<>();
        queue.add(new int[]{0, la, 0, lb});
        List<MatchingBlock> found = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] range = queue.remove(queue.size() - 1);
            int alo = range[0];
            int ahi = range[1];
            int blo = range[2];
            int bhi = range[3];
            MatchingBlock x = findLongestMatch(alo, ahi, blo, bhi);
            int i = x.srcPos();
            int j = x.destPos();
            int k = x.length();
            if (k != 0) {
                found.add(x);
                if (alo < i && blo < j) {
                    queue.add(new int[]{alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.add(new int[]{i + k, ahi, j + k, bhi});
                }
            }
        }
        found.sort(BLOCK_ORDER);

        int i1 = 0;
        int j1 = 0;
        int k1 = 0;
        List<MatchingBlock> nonAdjacent = new ArrayList<>();
        for (MatchingBlock block : found) {
            int i2 = block.srcPos();
            int j2 = block.destPos();
            int k2 = block.length();
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2;
            } else {
                if (k1 != 0) {
                    nonAdjacent.add(new MatchingBlock(i1, j1, k1));
                }
                i1 = i2;
                j1 = j2;
                k1 = k2;
            }
        }
        if (k1 != 0) {
            nonAdjacent.add(new MatchingBlock(i1, j1, k1));
        }
        nonAdjacent.add(new MatchingBlock(la, lb, 0));

        matchingBlocks = Collections.unmodifiableList(nonAdjacent);
        return matchingBlocks;
    }

    @Override
    public List<Opcode> getOpcodes() {
        if (opcodes != null) {
            return opcodes;
        }
        int i = 0;
        int j = 0;
        List<Opcode> answer = new ArrayList<>();
        for (MatchingBlock block : getMatchingBlocks()) {
            int ai = block.srcPos();
            int bj = block.destPos();
            int size = block.length();
            EditType tag = null;
            if (i < ai && j < bj) {
                tag = EditType.REPLACE;
            } else if (i < ai) {
                tag = EditType.DELETE;
            } else if (j < bj) {
                tag = EditType.INSERT;
            }
            if (tag != null) {
                answer.add(new Opcode(tag, i, ai, j, bj));
            }
            i = ai + size;
            j = bj + size;
            if (size != 0) {
                answer.add(new Opcode(EditType.EQUAL, ai, i, bj, j));
            }
        }
        opcodes = Collections.unmodifiableList(answer);
        return opcodes;
    }

    @Override
    public double ratio() {
        int matches = 0;
        for (MatchingBlock block : getMatchingBlocks()) {
            matches += block.length();
        }
        return calculateRatio(matches, cpA.length + cpB.length);
    }

    @Override
    public double quickRatio() {
        if (fullbcount == null) {
            fullbcount = new HashMap<>();
            for (int elt : cpB) {
                fullbcount.merge(elt, 1, Integer::sum);
            }
        }
        Map<Integer, Integer> avail = new HashMap<>();
        int matches = 0;
        for (int elt : cpA) {
            Integer seen = avail.get(elt);
            int numb = seen != null ? seen : fullbcount.getOrDefault(elt, 0);
            avail.put(elt, numb - 1);
            if (numb > 0) {
                matches++;
            }
        }
        return calculateRatio(matches, cpA.length + cpB.length);
    }

    @Override
    public double realQuickRatio() {
        int la = cpA.length;
        int lb = cpB.length;
        return calculateRatio(Math.min(la, lb), la + lb);
    }

    /**
     * @return the code points of {@code b} that {@code isjunk} rejected
     */
    public Set<Integer> getBjunk() {
        return Collections.unmodifiableSet(bjunk);
    }

    /**
     * @return the code points of {@code b} dropped by the {@code autojunk} heuristic
     */
    public Set<Integer> getBpopular() {
        return Collections.unmodifiableSet(bpopular);
    }

    private static double calculateRatio(int matches, int length) {
        if (length != 0) {
            return 2.0 * matches / length;
        }
        return 1.0;
    }
}
