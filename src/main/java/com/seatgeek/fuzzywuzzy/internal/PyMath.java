package com.seatgeek.fuzzywuzzy.internal;

/**
 * Numeric primitives that reproduce CPython semantics exactly.
 *
 * <p>Port of the numeric helpers used by {@code fuzzywuzzy/utils.py}.
 */
public final class PyMath {

    private PyMath() {
    }

    /**
     * Port of {@code fuzzywuzzy/utils.py:97} {@code def intr(n): return int(round(n))}.
     *
     * <p>CPython's built-in {@code round()} on a {@code float} performs <em>round-half-to-even</em>
     * ("banker's rounding") against the exact binary value of the double. This differs from
     * {@link Math#round(double)}, which rounds halves away from zero (upwards).
     *
     * <pre>
     * Python : round(66.5) == 66   round(67.5) == 68   round(-2.5) == -2   round(-3.5) == -4
     * Java   : Math.round(66.5) == 67  Math.round(67.5) == 68  Math.round(-2.5) == -2
     * </pre>
     *
     * <p>The implementation below is exact for every {@code |n| < 2^52}, which covers every value
     * fuzzywuzzy ever rounds (similarity ratios scaled to 0..100). {@code Math.floor} is exact, and
     * for an integral {@code fl} the subtraction {@code n - fl} is exact by Sterbenz' lemma, so the
     * {@code == 0.5} test is a true equality test on the binary value, mirroring CPython.
     *
     * <p>The result is {@code long}, not {@code int}: Python's {@code int()} is arbitrary precision
     * and never wraps, so narrowing here would silently turn {@code intr(1e15 + 0.5)} into a
     * negative number instead of {@code 1000000000000000}.
     *
     * @param n value to round
     * @return {@code n} rounded half-to-even, as Python's {@code int(round(n))} does
     */
    public static long intr(double n) {
        double fl = Math.floor(n);
        double frac = n - fl;
        if (frac > 0.5) {
            return (long) (fl + 1.0);
        }
        if (frac < 0.5) {
            return (long) fl;
        }
        long f = (long) fl;
        return (f % 2 == 0) ? f : f + 1;
    }
}
