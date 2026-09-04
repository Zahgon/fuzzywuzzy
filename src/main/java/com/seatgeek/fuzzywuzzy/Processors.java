package com.seatgeek.fuzzywuzzy;

/**
 * The two {@link Processor} instances that {@code fuzzywuzzy/process.py} treats specially.
 *
 * <p>{@code process.py} branches on {@code processor == utils.full_process}, an identity test
 * against one specific function object. Reproducing that in Java requires one canonical
 * {@code full_process} instance, which is {@link #FULL_PROCESS}. A processor written as a lambda
 * that happens to call {@link Utils#fullProcess(String)} is a different object and, exactly as in
 * Python, will not trigger the "don't run full_process twice" shortcut.
 */
public final class Processors {

    /**
     * The canonical {@code utils.full_process} processor and the default for every
     * {@link Process} entry point.
     */
    public static final Processor FULL_PROCESS = input -> Utils.fullProcess(input, false);

    /** The identity processor, {@code process.py}'s inner {@code no_process}. */
    public static final Processor NO_PROCESS = input -> input;

    private Processors() {
    }
}
