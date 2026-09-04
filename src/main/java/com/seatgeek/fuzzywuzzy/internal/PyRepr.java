package com.seatgeek.fuzzywuzzy.internal;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reproduces CPython's {@code str(obj)} and {@code repr(obj)} for the object shapes fuzzywuzzy can
 * actually encounter.
 *
 * <p>{@code fuzzywuzzy/utils.py:70} falls back to {@code asciidammit(unicode(s))} whenever it is
 * handed something that is not a string, and {@code make_type_consistent} does the same. The
 * reference test suite exercises this: {@code ProcessTest.testWithProcessor} feeds
 * {@code process.extract} a list of events and lets the default processor stringify one of them, so
 * the score depends on Python's list formatting, right down to the spaces after commas and the
 * single quotes around elements.
 *
 * <p>Supported: strings, {@link List}, {@link Set}, {@link Map}, arrays, boxed numbers, booleans and
 * {@code null}. Anything else falls back to {@link Object#toString()}, which is the closest Java
 * analogue of a user-defined {@code __str__}.
 */
public final class PyRepr {

    private PyRepr() {
    }

    /**
     * Equivalent of Python's {@code str(obj)}.
     *
     * <p>Differs from {@link #repr(Object)} only at the top level for strings: {@code str("a")} is
     * {@code a} while {@code repr("a")} is {@code 'a'}.
     *
     * @param obj object to stringify
     * @return Python's {@code str()} rendering
     */
    public static String str(Object obj) {
        if (obj instanceof String s) {
            return s;
        }
        return repr(obj);
    }

    /**
     * Equivalent of Python's {@code repr(obj)}.
     *
     * @param obj object to render
     * @return Python's {@code repr()} rendering
     */
    public static String repr(Object obj) {
        if (obj == null) {
            return "None";
        }
        if (obj instanceof String s) {
            return quote(s);
        }
        if (obj instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (obj instanceof Double || obj instanceof Float) {
            return floatRepr(((Number) obj).doubleValue());
        }
        if (obj instanceof Number || obj instanceof Character) {
            return obj.toString();
        }
        if (obj instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
            return sb.append('}').toString();
        }
        if (obj instanceof Set<?> s) {
            if (s.isEmpty()) {
                return "set()";
            }
            return joined(s, "{", "}");
        }
        if (obj instanceof Collection<?> c) {
            return joined(c, "[", "]");
        }
        if (obj instanceof Object[] a) {
            return joined(List.of(a), "[", "]");
        }
        return obj.toString();
    }

    private static String joined(Collection<?> items, String open, String close) {
        StringBuilder sb = new StringBuilder(open);
        Iterator<?> it = items.iterator();
        while (it.hasNext()) {
            sb.append(repr(it.next()));
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.append(close).toString();
    }

    /**
     * Renders a string the way Python's {@code repr()} does: single quotes by default, double quotes
     * when the value contains a single quote but no double quote, with backslash escapes for control
     * characters.
     */
    private static String quote(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char q = (hasSingle && !hasDouble) ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2).append(q);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (cp == q) {
                        sb.append('\\').appendCodePoint(cp);
                    } else if (cp < 0x20 || cp == 0x7F) {
                        sb.append(String.format("\\x%02x", cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        return sb.append(q).toString();
    }

    /**
     * Python renders whole floats with a trailing {@code .0}, unlike Java's {@code Double.toString}
     * for values such as {@code 1.0E10}.
     */
    private static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == Math.floor(d) && Math.abs(d) < 1e16) {
            return (long) d + ".0";
        }
        return Double.toString(d);
    }
}
