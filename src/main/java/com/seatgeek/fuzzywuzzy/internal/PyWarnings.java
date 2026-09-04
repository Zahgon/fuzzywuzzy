package com.seatgeek.fuzzywuzzy.internal;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Stand-in for the handful of {@code warnings.warn} and {@code logging.warning} calls in the Python
 * package.
 *
 * <p>Both CPython facilities write to standard error by default, and two of the ported call sites are
 * observable from the original test suite, so the messages are emitted verbatim in CPython's own
 * formatting rather than routed through a Java logging framework that a downstream project might
 * silence or reformat.
 */
public final class PyWarnings {

    private static volatile PrintStream sink = System.err;

    private PyWarnings() {
    }

    /**
     * Redirects subsequent warnings, so tests can assert on the exact bytes CPython would have
     * produced.
     *
     * @param stream where to write; {@code null} restores {@link System#err}
     */
    public static void setSink(PrintStream stream) {
        sink = stream == null ? System.err : stream;
    }

    /**
     * Equivalent of {@code warnings.warn(message)}, which defaults to {@code UserWarning}.
     *
     * @param message the warning text
     */
    public static void warn(String message) {
        sink.println("UserWarning: " + Objects.requireNonNull(message));
    }

    /**
     * Equivalent of {@code logging.warning(message)} on the root logger with no handler configured,
     * which CPython renders as {@code WARNING:root:<message>}.
     *
     * @param message the log text
     */
    public static void logWarning(String message) {
        sink.println("WARNING:root:" + Objects.requireNonNull(message));
    }
}
