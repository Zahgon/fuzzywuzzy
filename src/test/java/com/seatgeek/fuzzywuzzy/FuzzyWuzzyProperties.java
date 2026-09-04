package com.seatgeek.fuzzywuzzy;

import com.seatgeek.fuzzywuzzy.model.ExtractedResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of test_fuzzywuzzy_hypothesis.py, with jqwik standing in for hypothesis.
 *
 * <p>Both Python tests are {@code @pytest.mark.parametrize}d over a (scorer, processor) product, so
 * one Python function becomes many independent cases: {@code test_identical_strings_extracted}
 * yields fourteen and {@code test_only_identical_strings_extracted} seven. Each of those cases is a
 * separate property here, named after the Python case it stands in for, so a regression names the
 * exact scorer/processor combination that broke instead of one blanket failure.
 *
 * <p>None of the processors below is {@link Processors#FULL_PROCESS}, mirroring the Python original
 * where {@code partial(utils.full_process, force_ascii=...)} is a fresh object. That means
 * {@code process}'s identity dispatch does not fire and choices really are processed twice.
 */
class FuzzyWuzzyProperties {

    private static final String ASCII_LETTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    private static final String HYPOTHESIS_ALPHABET = ASCII_LETTERS + DIGITS + PUNCTUATION;

    private static final int TRIES = 60;

    private static final Processor IDENTITY = input -> input;
    private static final Processor FULL_PROCESS_UNICODE = input -> Utils.fullProcess(input, false);
    private static final Processor FULL_PROCESS_ASCII = input -> Utils.fullProcess(input, true);

    @Provide
    Arbitrary<List<String>> stringLists() {
        return Arbitraries.strings()
                .withChars(HYPOTHESIS_ALPHABET.toCharArray())
                .ofMinLength(10)
                .ofMaxLength(100)
                .list()
                .ofMinSize(1)
                .ofMaxSize(10);
    }

    /** One {@code process.extractBests(..., score_cutoff=100, limit=None)} call and its inputs. */
    private record Extraction(String choice, Object processedChoice,
                              List<ExtractedResult<String>> results) {
    }

    private static Extraction extract(
            Scorer scorer, Processor processor, List<String> strings, int choiceIdx) {
        String choice = strings.get(choiceIdx % strings.size());
        Object processed = processor.process(choice);
        Assume.that(!"".equals(processed));
        return new Extraction(choice, processed,
                Process.extractBests(choice, strings, processor, scorer, 100, null));
    }

    private static boolean holdsPerfectSelfMatch(Extraction e) {
        return e.results().stream()
                .anyMatch(r -> e.choice().equals(r.choice()) && r.score() == 100);
    }

    private static int lowestScore(Extraction e) {
        return e.results().stream().mapToInt(ExtractedResult::score).min().orElse(-1);
    }

    private static boolean everyChoiceCameFromTheInput(Extraction e, List<String> strings) {
        return e.results().stream().allMatch(r -> strings.contains(r.choice()));
    }

    /**
     * The Python assertions shared by every {@code test_identical_strings_extracted} case, plus the
     * two invariants {@code score_cutoff=100} implies: nothing below the cutoff survives, and
     * extraction returns choices rather than inventing them.
     */
    private static void assertPerfectSelfMatchIsExtracted(
            Scorer scorer, Processor processor, List<String> strings, int choiceIdx) {
        Extraction e = extract(scorer, processor, strings, choiceIdx);
        assertFalse(e.results().isEmpty(), () -> "no result for choice=" + e.choice());
        assertTrue(holdsPerfectSelfMatch(e),
                () -> "perfect self-match missing for choice=" + e.choice() + " got " + e.results());
        assertEquals(100, lowestScore(e), () -> "score_cutoff=100 admitted " + e.results());
        assertTrue(everyChoiceCameFromTheInput(e, strings),
                () -> "extraction returned a choice that was never offered: " + e.results());
    }

    /**
     * The Python assertions shared by every {@code test_only_identical_strings_extracted} case: a
     * whole-string scorer may only award 100 to a choice that is identical after processing.
     */
    private static void assertOnlyProcessedEqualsScore100(
            Scorer scorer, Processor processor, List<String> strings, int choiceIdx) {
        Extraction e = extract(scorer, processor, strings, choiceIdx);
        assertFalse(e.results().isEmpty(), () -> "no result for choice=" + e.choice());
        assertEquals(100, lowestScore(e), () -> "score_cutoff=100 admitted " + e.results());
        assertTrue(everyChoiceCameFromTheInput(e, strings),
                () -> "extraction returned a choice that was never offered: " + e.results());
        for (ExtractedResult<String> r : e.results()) {
            assertEquals(e.processedChoice(), processor.process(r.choice()),
                    () -> "unequal string scored 100: " + r.choice() + " vs " + e.choice());
        }
    }

    // ---------------------------------------------------------------------------------------
    // test_identical_strings_extracted[scorer-processor]
    // ---------------------------------------------------------------------------------------

    @Property(tries = TRIES)
    void identicalStringsExtractedRatioLambda(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(BuiltinScorer.RATIO, IDENTITY, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedRatioProcessor1(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(BuiltinScorer.RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedRatioProcessor2(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(BuiltinScorer.RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedPartialRatioLambda(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(BuiltinScorer.PARTIAL_RATIO, IDENTITY, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedPartialRatioProcessor4(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.PARTIAL_RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedPartialRatioProcessor5(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.PARTIAL_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedWRatioProcessor6(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.WEIGHTED_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedQRatioProcessor7(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.QUICK_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedUWRatioProcessor8(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.UNICODE_WEIGHTED_RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedUQRatioProcessor9(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.UNICODE_QUICK_RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedTokenSetRatioProcessor10(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.TOKEN_SET_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedTokenSortRatioProcessor11(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.TOKEN_SORT_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedPartialTokenSetRatioProcessor12(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.PARTIAL_TOKEN_SET_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void identicalStringsExtractedPartialTokenSortRatioProcessor13(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertPerfectSelfMatchIsExtracted(
                BuiltinScorer.PARTIAL_TOKEN_SORT_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    // ---------------------------------------------------------------------------------------
    // test_only_identical_strings_extracted[scorer-processor]
    // ---------------------------------------------------------------------------------------

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedRatioLambda(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(BuiltinScorer.RATIO, IDENTITY, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedRatioProcessor1(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(BuiltinScorer.RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedRatioProcessor2(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(BuiltinScorer.RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedWRatioProcessor3(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(
                BuiltinScorer.WEIGHTED_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedQRatioProcessor4(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(
                BuiltinScorer.QUICK_RATIO, FULL_PROCESS_ASCII, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedUWRatioProcessor5(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(
                BuiltinScorer.UNICODE_WEIGHTED_RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }

    @Property(tries = TRIES)
    void onlyIdenticalStringsExtractedUQRatioProcessor6(
            @ForAll("stringLists") List<String> strings, @ForAll @IntRange(min = 0, max = 9) int choiceIdx) {
        assertOnlyProcessedEqualsScore100(
                BuiltinScorer.UNICODE_QUICK_RATIO, FULL_PROCESS_UNICODE, strings, choiceIdx);
    }
}
