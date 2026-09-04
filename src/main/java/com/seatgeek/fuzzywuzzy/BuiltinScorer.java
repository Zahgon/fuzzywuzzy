package com.seatgeek.fuzzywuzzy;

/**
 * The {@link Scorer} implementations that {@code fuzzywuzzy/process.py} recognises by identity.
 *
 * <p>{@code extractWithoutOrder} inspects its {@code scorer} argument with two membership tests:
 *
 * <pre>
 * if scorer in [WRatio, QRatio, token_set_ratio, token_sort_ratio,
 *               partial_token_set_ratio, partial_token_sort_ratio, UWRatio, UQRatio]
 *         and processor == utils.full_process:
 *     processor = no_process
 *
 * if scorer in [UWRatio, UQRatio]:      pre_processor = partial(full_process, force_ascii=False)
 * elif scorer in [WRatio, QRatio, ...]: pre_processor = partial(full_process, force_ascii=True)
 * else:                                 pre_processor = no_process
 * </pre>
 *
 * <p>Both tests use {@code ==} on function objects, so they are identity checks. This enum is the
 * set of objects that pass them; {@link #kind()} records which branch each constant takes. A
 * caller-supplied lambda is not a member and therefore takes the {@code else} branch, matching
 * Python's behaviour for any function that is not literally one of the eight listed.
 *
 * <p>Note that {@link #RATIO} and {@link #PARTIAL_RATIO} are absent from both Python lists, which
 * is why {@code ProcessTest.test_simplematch} still sees {@code full_process} applied per choice.
 */
public enum BuiltinScorer implements Scorer {

    /** {@code fuzz.ratio}; in neither dispatch list. */
    RATIO(Kind.PLAIN, (s1, s2, fullProcess) -> Fuzz.ratio(s1, s2)),

    /** {@code fuzz.partial_ratio}; in neither dispatch list. */
    PARTIAL_RATIO(Kind.PLAIN, (s1, s2, fullProcess) -> Fuzz.partialRatio(s1, s2)),

    /** {@code fuzz.token_sort_ratio}. */
    TOKEN_SORT_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.tokenSortRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.partial_token_sort_ratio}. */
    PARTIAL_TOKEN_SORT_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.partialTokenSortRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.token_set_ratio}. */
    TOKEN_SET_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.tokenSetRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.partial_token_set_ratio}. */
    PARTIAL_TOKEN_SET_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.partialTokenSetRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.QRatio}. */
    QUICK_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.quickRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.UQRatio}. */
    UNICODE_QUICK_RATIO(Kind.UNICODE, (s1, s2, fullProcess) -> Fuzz.unicodeQuickRatio(s1, s2, fullProcess)),

    /** {@code fuzz.WRatio}; the default scorer of every {@code process.py} entry point. */
    WEIGHTED_RATIO(Kind.ASCII, (s1, s2, fullProcess) -> Fuzz.weightedRatio(s1, s2, true, fullProcess)),

    /** {@code fuzz.UWRatio}. */
    UNICODE_WEIGHTED_RATIO(Kind.UNICODE, (s1, s2, fullProcess) -> Fuzz.unicodeWeightedRatio(s1, s2, fullProcess));

    /** Which {@code extractWithoutOrder} pre-processing branch a scorer selects. */
    public enum Kind {

        /** Absent from both lists: the query and every choice keep going through the processor. */
        PLAIN,

        /** Pre-processed once with {@code full_process(force_ascii=True)}. */
        ASCII,

        /** Pre-processed once with {@code full_process(force_ascii=False)}. */
        UNICODE
    }

    @FunctionalInterface
    private interface ScoreFunction {
        int apply(String s1, String s2, boolean fullProcess);
    }

    private final Kind kind;
    private final ScoreFunction function;

    BuiltinScorer(Kind kind, ScoreFunction function) {
        this.kind = kind;
        this.function = function;
    }

    /**
     * The pre-processing branch this scorer selects.
     *
     * @return the branch
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Whether {@code process.py} would replace the processor with {@code no_process} for this
     * scorer, i.e. whether it appears in the eight-element "don't run full_process twice" list.
     *
     * @return {@code true} for every constant except {@link #RATIO} and {@link #PARTIAL_RATIO}
     */
    public boolean avoidsDoubleProcessing() {
        return kind != Kind.PLAIN;
    }

    @Override
    public int score(String s1, String s2) {
        return function.apply(s1, s2, true);
    }

    /**
     * Scores with {@code full_process=False}, the binding {@code process.py} creates via
     * {@code partial(scorer, full_process=False)} once it has pre-processed the inputs itself.
     *
     * @param s1 first string
     * @param s2 second string
     * @return similarity in {@code 0..100}
     */
    public int scoreWithoutFullProcess(String s1, String s2) {
        return function.apply(s1, s2, false);
    }

    /**
     * This scorer as a plain {@link Scorer} bound to {@code full_process=False}.
     *
     * @return the bound scorer
     */
    public Scorer withoutFullProcess() {
        return this::scoreWithoutFullProcess;
    }
}
