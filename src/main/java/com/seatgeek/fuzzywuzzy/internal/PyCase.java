package com.seatgeek.fuzzywuzzy.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Case mapping that reproduces CPython's {@code str.lower()} and {@code str.upper()} exactly.
 *
 * <h2>Why this is not just {@code String.toLowerCase(Locale.ROOT)}</h2>
 *
 * <p>Two independent problems have to be solved at once.
 *
 * <p><strong>1. Case mapping is context sensitive.</strong> Both CPython and Java implement the
 * Unicode {@code Final_Sigma} rule, so {@code "ΟΔΟΣ".lower()} is {@code "οδος"} with a final sigma
 * {@code ς}, not {@code "οδοσ"}. A naive per-code-point mapping table gets this wrong. We therefore
 * have to let Java's string-level algorithm run.
 *
 * <p><strong>2. The JDK and CPython ship different Unicode revisions.</strong> OpenJDK 26 knows case
 * mappings for code points that CPython 3.14 (Unicode 16.0.0) still considers unassigned, for
 * example U+A7CE, U+A7D2, U+A7D4 and the Vithkuqi block U+16EA0..U+16EB8. Left alone, Java would
 * lowercase them and Python would not.
 *
 * <p>The fix reconciles both: code points where the JDK disagrees with the frozen CPython tables are
 * temporarily replaced by a Unicode <em>noncharacter</em> before Java's case algorithm runs.
 * A noncharacter is permanently unassigned in every Unicode revision, so Java treats it as uncased
 * and case-ignorable, which is precisely how CPython treats the original code point. Sigma context
 * around the substitution is therefore resolved the same way CPython resolves it, and afterwards the
 * placeholders are swapped back for the mapping CPython would have produced.
 */
final class PyCase {

    private PyCase() {
    }

    /** CPython 3.14 / Unicode 16.0.0 mappings, keyed by source code point. */
    private static final Map<Integer, String> ORACLE_LOWER = load("lower_map.txt");
    private static final Map<Integer, String> ORACLE_UPPER = load("upper_map.txt");

    /** Code points where this JDK's mapping differs from the frozen CPython mapping. */
    private static final BitSet DIVERGENT_LOWER = divergent(ORACLE_LOWER, true);
    private static final BitSet DIVERGENT_UPPER = divergent(ORACLE_UPPER, false);

    /**
     * Noncharacters: permanently unassigned in every Unicode revision, so no case algorithm will
     * ever touch them and no case-context rule will ever count them as a cased letter.
     */
    private static final int NONCHAR_START = 0xFDD0;
    private static final int NONCHAR_END = 0xFDEF;

    private static Map<Integer, String> load(String resource) {
        InputStream in = PyCase.class.getResourceAsStream("/com/seatgeek/fuzzywuzzy/" + resource);
        if (in == null) {
            throw new IllegalStateException("missing bundled Unicode table: " + resource);
        }
        Map<Integer, String> map = new HashMap<>(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int sp = line.indexOf(' ');
                int cp = Integer.parseInt(line.substring(0, sp), 16);
                StringBuilder sb = new StringBuilder(2);
                for (String h : line.substring(sp + 1).split(" ")) {
                    sb.appendCodePoint(Integer.parseInt(h, 16));
                }
                map.put(cp, sb.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read bundled Unicode table: " + resource, e);
        }
        return map;
    }

    private static BitSet divergent(Map<Integer, String> oracle, boolean lower) {
        BitSet set = new BitSet();
        // Code points the oracle maps: compare against the JDK's full (possibly expanding) mapping.
        for (Map.Entry<Integer, String> e : oracle.entrySet()) {
            int cp = e.getKey();
            if (!e.getValue().equals(jdkMap(cp, lower))) {
                set.set(cp);
            }
        }
        // Code points the oracle leaves alone: the JDK must leave them alone too.
        for (int cp = 0; cp < 0x110000; cp++) {
            if (oracle.containsKey(cp)) {
                continue;
            }
            int simple = lower ? Character.toLowerCase(cp) : Character.toUpperCase(cp);
            if (simple != cp || !jdkMap(cp, lower).equals(new String(Character.toChars(cp)))) {
                set.set(cp);
            }
        }
        return set;
    }

    private static String jdkMap(int cp, boolean lower) {
        String s = new String(Character.toChars(cp));
        return lower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
    }

    /**
     * Reproduces CPython's {@code str.lower()}.
     *
     * @param s input string
     * @return lowercased string, identical to what CPython 3.14 would produce
     */
    static String lower(String s) {
        return map(s, true);
    }

    /**
     * Reproduces CPython's {@code str.upper()}.
     *
     * @param s input string
     * @return uppercased string, identical to what CPython 3.14 would produce
     */
    static String upper(String s) {
        return map(s, false);
    }

    private static String map(String s, boolean lower) {
        BitSet divergent = lower ? DIVERGENT_LOWER : DIVERGENT_UPPER;
        List<Integer> shielded = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (divergent.get(cp)) {
                if (shielded == null) {
                    shielded = new ArrayList<>();
                }
                shielded.add(cp);
            }
            i += Character.charCount(cp);
        }
        if (shielded == null) {
            return lower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
        }

        int placeholder = choosePlaceholder(s);
        if (placeholder < 0) {
            // The input already contains every available noncharacter. Fall back to a per-code-point
            // mapping; the only thing lost is sigma context, and reaching this branch requires a
            // deliberately hostile 32-noncharacter string.
            StringBuilder sb = new StringBuilder(s.length());
            Map<Integer, String> oracle = lower ? ORACLE_LOWER : ORACLE_UPPER;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                String m = oracle.get(cp);
                if (m != null) {
                    sb.append(m);
                } else {
                    sb.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
            return sb.toString();
        }

        StringBuilder masked = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            masked.appendCodePoint(divergent.get(cp) ? placeholder : cp);
            i += Character.charCount(cp);
        }
        String mapped = lower
                ? masked.toString().toLowerCase(Locale.ROOT)
                : masked.toString().toUpperCase(Locale.ROOT);

        Map<Integer, String> oracle = lower ? ORACLE_LOWER : ORACLE_UPPER;
        StringBuilder out = new StringBuilder(mapped.length());
        int next = 0;
        for (int i = 0; i < mapped.length(); ) {
            int cp = mapped.codePointAt(i);
            if (cp == placeholder) {
                int original = shielded.get(next++);
                String m = oracle.get(original);
                if (m != null) {
                    out.append(m);
                } else {
                    out.appendCodePoint(original);
                }
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static int choosePlaceholder(String s) {
        for (int cp = NONCHAR_START; cp <= NONCHAR_END; cp++) {
            if (s.indexOf(cp) < 0) {
                return cp;
            }
        }
        return -1;
    }
}
