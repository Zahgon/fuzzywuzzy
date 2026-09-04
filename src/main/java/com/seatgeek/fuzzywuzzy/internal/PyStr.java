package com.seatgeek.fuzzywuzzy.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * String primitives that reproduce CPython {@code str} semantics exactly.
 *
 * <p>A Python {@code str} is a sequence of Unicode <em>code points</em>; a Java {@code String} is a
 * sequence of UTF-16 <em>code units</em>. Every fuzzywuzzy operation that indexes, slices, measures
 * length, sorts, or feeds a sequence matcher must therefore work on code points, otherwise any input
 * containing a supplementary character (emoji, rare CJK, musical symbols) silently diverges.
 *
 * <p>This class is the single place where that translation happens.
 */
public final class PyStr {

    private PyStr() {
    }

    /**
     * Code points for which CPython's {@code str.isspace()} returns {@code True}.
     *
     * <p>Verified by exhaustive sweep of all 1,114,112 code points against CPython 3.14 / Unicode
     * 16.0.0. Note that this set is <em>not</em> {@link Character#isWhitespace(int)}: Python includes
     * U+001C..U+001F (the file/group/record/unit separators), U+0085 (NEL) and U+00A0 (NBSP), while
     * Java's {@code isWhitespace} excludes U+00A0, U+2007 and U+202F. Java's
     * {@link Character#isSpaceChar(int)} is different again. Neither is usable.
     *
     * <p>See {@code src/test/resources/golden/space_ranges.txt} for the generated oracle.
     */
    private static final int[] SPACE_CODE_POINTS = {
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
            0x001C, 0x001D, 0x001E, 0x001F,
            0x0020, 0x0085, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
            0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    };

    /** Bitmap for the Latin-1 range, which covers the overwhelming majority of lookups. */
    private static final boolean[] SPACE_LATIN1 = new boolean[0x100];

    static {
        for (int cp : SPACE_CODE_POINTS) {
            if (cp < 0x100) {
                SPACE_LATIN1[cp] = true;
            }
        }
    }

    /**
     * Reproduces CPython's {@code str.isspace()} for a single code point.
     *
     * @param cp Unicode code point
     * @return {@code true} if Python considers this code point whitespace
     */
    public static boolean isSpace(int cp) {
        if (cp < 0x100) {
            return SPACE_LATIN1[cp];
        }
        for (int i = 12; i < SPACE_CODE_POINTS.length; i++) {
            int c = SPACE_CODE_POINTS[i];
            if (c == cp) {
                return true;
            }
            if (c > cp) {
                return false;
            }
        }
        return false;
    }

    /**
     * Reproduces the character class matched by Python's {@code re.compile(r"(?ui)\w")}, which
     * {@code fuzzywuzzy/string_processing.py:8} uses (negated) to strip punctuation.
     *
     * <p>Under Python's {@code re} module with the UNICODE flag, {@code \w} matches
     * "word characters": any character that {@code str.isalnum()} accepts, plus the underscore.
     * Exhaustively verified over all 1,114,112 code points to be exactly the general categories
     * {@code Lu Ll Lt Lm Lo Nd Nl No} plus {@code U+005F LOW LINE}.
     *
     * <p>Java's own {@code \w} is ASCII-only, and {@code UNICODE_CHARACTER_CLASS} is <em>wider</em>
     * than Python's: it additionally admits {@code Mn Me Mc Pc} and {@code Join_Control}. Both would
     * corrupt the output.
     *
     * <p>{@link Character#getType(int)} is not usable either, because OpenJDK 26 ships a newer
     * Unicode revision than CPython 3.14 and classifies code points such as U+088F, U+0C5C, U+A7CE
     * and U+10940..U+10950 as letters while Python still treats them as unassigned. We therefore
     * consult the frozen table extracted from the reference interpreter.
     *
     * @param cp Unicode code point
     * @return {@code true} if Python's {@code (?ui)\w} matches this code point
     */
    public static boolean isWord(int cp) {
        int[] ranges = UnicodeTables.WORD_RANGES;
        int lo = 0;
        int hi = (ranges.length >>> 1) - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = ranges[mid << 1];
            int end = ranges[(mid << 1) + 1];
            if (cp < start) {
                hi = mid - 1;
            } else if (cp > end) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Explodes a string into its Unicode code points.
     *
     * @param s input string, must not be {@code null}
     * @return newly allocated array of code points
     */
    public static int[] toCodePoints(String s) {
        int n = s.length();
        int[] out = new int[n];
        int count = 0;
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            out[count++] = cp;
            i += Character.charCount(cp);
        }
        if (count == n) {
            return out;
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * Rebuilds a string from a code point range.
     *
     * @param cps  code points
     * @param from inclusive start index
     * @param to   exclusive end index
     * @return the reassembled string
     */
    public static String fromCodePoints(int[] cps, int from, int to) {
        if (from >= to) {
            return "";
        }
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    /**
     * Rebuilds a string from all of {@code cps}.
     *
     * @param cps code points
     * @return the reassembled string
     */
    public static String fromCodePoints(int[] cps) {
        return fromCodePoints(cps, 0, cps.length);
    }

    /**
     * Python's {@code len(s)}: the number of code points, not UTF-16 code units.
     *
     * @param s input string
     * @return code point count
     */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /**
     * Python's slice {@code cps[start:end]} with the usual clamping behaviour for non-negative
     * bounds: out-of-range indices are silently truncated instead of raising.
     *
     * <p>{@code fuzzywuzzy/fuzz.py:60} relies on this when it slices a window out of the longer
     * string that may extend past the end.
     *
     * @param cps   code points to slice
     * @param start inclusive start, clamped to {@code [0, len]}
     * @param end   exclusive end, clamped to {@code [0, len]}
     * @return the slice as a string
     */
    public static String slice(int[] cps, int start, int end) {
        int lo = Math.max(0, Math.min(start, cps.length));
        int hi = Math.max(0, Math.min(end, cps.length));
        if (lo >= hi) {
            return "";
        }
        return fromCodePoints(cps, lo, hi);
    }

    /**
     * Python's {@code str.lower()}.
     *
     * <p>Locale independent (never the Turkish dotless-i rule) and pinned to the reference
     * interpreter's Unicode revision. See {@link PyCase} for why this is not a plain
     * {@code toLowerCase(Locale.ROOT)}.
     *
     * @param s input string
     * @return lowercased string
     */
    public static String lower(String s) {
        return PyCase.lower(s);
    }

    /**
     * Python's {@code str.upper()}.
     *
     * @param s input string
     * @return uppercased string
     */
    public static String upper(String s) {
        return PyCase.upper(s);
    }

    /**
     * Python's {@code str.strip()} with no argument: removes leading and trailing code points for
     * which {@link #isSpace(int)} holds.
     *
     * <p>{@link String#trim()} only strips {@code <= U+0020}; {@link String#strip()} uses Java's
     * whitespace definition, which disagrees with Python's on U+00A0, U+2007, U+202F and
     * U+001C..U+001F. Both are wrong here.
     *
     * @param s input string
     * @return stripped string
     */
    public static String strip(String s) {
        int len = s.length();
        int start = 0;
        while (start < len) {
            int cp = s.codePointAt(start);
            if (!isSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        int end = len;
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    /**
     * Python's {@code str.split()} with no argument: splits on runs of whitespace and discards empty
     * fields, so leading and trailing whitespace never produce empty tokens.
     *
     * <p>This is materially different from {@code String.split("\\s+")}, which emits a leading empty
     * string when the input starts with whitespace.
     *
     * @param s input string
     * @return the tokens, in order
     */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        int len = s.length();
        int i = 0;
        while (i < len) {
            int cp = s.codePointAt(i);
            if (isSpace(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            while (i < len) {
                int c = s.codePointAt(i);
                if (isSpace(c)) {
                    break;
                }
                i += Character.charCount(c);
            }
            out.add(s.substring(start, i));
        }
        return out;
    }

    /**
     * Python's {@code sep.join(parts)}.
     *
     * @param sep   separator
     * @param parts elements to join
     * @return the joined string
     */
    public static String join(String sep, List<String> parts) {
        return String.join(sep, parts);
    }

    /**
     * Orders strings the way Python's {@code sorted()} does: lexicographically by Unicode code point.
     *
     * <p>{@link String#compareTo(String)} compares UTF-16 code units, so it places every
     * supplementary character (which begins with a surrogate in {@code U+D800..U+DFFF}) <em>before</em>
     * the BMP private-use and specials blocks {@code U+E000..U+FFFF}. Python orders them after.
     * {@code fuzzywuzzy/fuzz.py:78} and {@code :139} sort tokens, so this matters for token ratios.
     */
    public static final Comparator<String> CODE_POINT_ORDER = PyStr::compareCodePoints;

    /**
     * Compares two strings by Unicode code point, as Python does.
     *
     * @param a first string
     * @param b second string
     * @return negative, zero or positive per {@link Comparator}
     */
    public static int compareCodePoints(String a, String b) {
        int la = a.length();
        int lb = b.length();
        int i = 0;
        int j = 0;
        while (i < la && j < lb) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(la - i, lb - j);
    }
}
