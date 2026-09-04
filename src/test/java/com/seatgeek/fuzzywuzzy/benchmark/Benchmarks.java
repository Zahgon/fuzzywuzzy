package com.seatgeek.fuzzywuzzy.benchmark;

import com.seatgeek.fuzzywuzzy.BuiltinScorer;
import com.seatgeek.fuzzywuzzy.Fuzz;
import com.seatgeek.fuzzywuzzy.Process;
import com.seatgeek.fuzzywuzzy.Processors;
import com.seatgeek.fuzzywuzzy.Utils;
import com.seatgeek.fuzzywuzzy.internal.PyRepr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Port of the upstream {@code benchmarks.py} script: same workloads, same corpora, same
 * {@code "Total time: %fs. Average run: %.3f%s."} report format.
 *
 * <p>Run it with the test classpath on:
 * <pre>{@code
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes com.seatgeek.fuzzywuzzy.benchmark.Benchmarks
 * }</pre>
 *
 * <p>Unlike the Python original this warms the JIT before timing; without that every measurement
 * would report interpreter startup rather than steady-state throughput.
 */
public final class Benchmarks {

    private static final int ITERATIONS = 100_000;

    private static final String[] CIRQUE_STRINGS = {
            "cirque du soleil - zarkana - las vegas",
            "cirque du soleil ",
            "cirque du soleil las vegas",
            "zarkana las vegas",
            "las vegas cirque du soleil at the bellagio",
            "zarakana - cirque du soleil - bellagio",
    };

    private static final String[] CHOICES = {
            "",
            "new york yankees vs boston red sox",
            "",
            "zarakana - cirque du soleil - bellagio",
            null,
            "cirque du soleil las vegas",
            null,
    };

    private static final String[] MIXED_STRINGS = {
            "Lorem Ipsum is simply dummy text of the printing and typesetting industry.",
            "C\\'est la vie",
            "\u00c7a va?",
            "C\u00e3es danados",
            "\u00acCamar\u00f5es assados",
            "a\u00ac\u1234\u20ac\u8000",
    };

    private static final String[] UNITS = {"s", "ms", "us", "ns"};

    private Benchmarks() {
    }

    /**
     * Runs every benchmark in the same order as {@code benchmarks.py}.
     *
     * @param args ignored
     * @throws IOException if {@code data/titledata.csv} cannot be read from the classpath
     */
    public static void main(String[] args) throws IOException {
        List<String> titles = readTitles();

        for (String s : CHOICES) {
            String arg = PyRepr.str(s);
            System.out.printf("Test validate_string for: \"%s\"%n", arg);
            time(() -> Utils.validateString(arg) ? 1L : 0L, ITERATIONS);
        }
        System.out.println();

        List<String> processTargets = new ArrayList<>();
        processTargets.addAll(Arrays.asList(MIXED_STRINGS));
        processTargets.addAll(Arrays.asList(CIRQUE_STRINGS));
        for (String s : CHOICES) {
            processTargets.add(PyRepr.str(s));
        }
        for (String s : processTargets) {
            System.out.printf("Test full_process for: \"%s\"%n", s);
            time(() -> Utils.fullProcess(s).length(), ITERATIONS);
        }

        for (String s : CIRQUE_STRINGS) {
            System.out.printf("Test fuzz.ratio for string: \"%s\"%n", s);
            System.out.println("-------------------------------");
            time(() -> Fuzz.ratio("cirque du soleil", s), ITERATIONS / 100);
        }

        for (String s : CIRQUE_STRINGS) {
            System.out.printf("Test fuzz.partial_ratio for string: \"%s\"%n", s);
            System.out.println("-------------------------------");
            time(() -> Fuzz.partialRatio("cirque du soleil", s), ITERATIONS / 100);
        }

        for (String s : CIRQUE_STRINGS) {
            System.out.printf("Test fuzz.WRatio for string: \"%s\"%n", s);
            System.out.println("-------------------------------");
            time(() -> Fuzz.weightedRatio("cirque du soleil", s), ITERATIONS / 100);
        }

        List<String> randomChoices = randomChoices();

        System.out.println("Test process.extract(scorer = fuzz.QRatio)");
        System.out.println("-------------------------------");
        time(() -> Process.extract("cirque du soleil", randomChoices,
                Processors.FULL_PROCESS, BuiltinScorer.QUICK_RATIO, 5).size(), 10);

        System.out.println("Test process.extract(scorer = fuzz.WRatio)");
        System.out.println("-------------------------------");
        time(() -> Process.extract("cirque du soleil", randomChoices,
                Processors.FULL_PROCESS, BuiltinScorer.WEIGHTED_RATIO, 5).size(), 10);

        System.out.println("Real world ratio(): \"New York Yankees\"");
        System.out.println("-------------------------------");
        time(() -> {
            List<String> sorted = new ArrayList<>(titles);
            sorted.sort((a, b) -> Integer.compare(
                    Fuzz.ratio("New York Yankees", a), Fuzz.ratio("New York Yankees", b)));
            return sorted.size();
        }, 100);
    }

    /**
     * Times {@code number} executions of {@code body} and prints the result the way the Python
     * helper {@code print_result_from_timeit} does.
     *
     * @param body   the workload; its return value is consumed so the JIT cannot elide the call
     * @param number how many times to run it
     */
    private static void time(Work body, int number) {
        long blackhole = 0;
        int warmup = Math.max(1, Math.min(number, 2000));
        for (int i = 0; i < warmup; i++) {
            blackhole += body.run();
        }

        long start = System.nanoTime();
        for (int i = 0; i < number; i++) {
            blackhole += body.run();
        }
        double duration = (System.nanoTime() - start) / 1e9;

        if (blackhole == Long.MIN_VALUE) {
            System.out.print("");
        }
        double avgDuration = duration / number;
        int thousands = (int) Math.floor(Math.log(avgDuration) / Math.log(1000));
        System.out.printf("Total time: %fs. Average run: %.3f%s.%n",
                duration, avgDuration * Math.pow(1000, -thousands), UNITS[-thousands]);
    }

    /**
     * Reproduces the Python corpus {@code [''.join(random.choice(ascii_uppercase + digits) ...)]}.
     * CPython's Mersenne Twister and {@link Random} disagree, so the strings differ; only their
     * length, alphabet and count matter to the measurement.
     *
     * @return 5000 random 30-character strings
     */
    private static List<String> randomChoices() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random(18);
        List<String> choices = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            StringBuilder sb = new StringBuilder(30);
            for (int j = 0; j < 30; j++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            choices.add(sb.toString());
        }
        return choices;
    }

    /**
     * Reads the {@code custom_title} column of the pipe-delimited title corpus.
     *
     * @return every title, in file order
     * @throws IOException if the resource is missing or unreadable
     */
    private static List<String> readTitles() throws IOException {
        List<String> titles = new ArrayList<>();
        try (InputStream in = Benchmarks.class.getResourceAsStream("/data/titledata.csv")) {
            if (in == null) {
                throw new IOException("data/titledata.csv is not on the classpath");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String header = reader.readLine();
            if (header == null) {
                return titles;
            }
            int column = Arrays.asList(header.split("\\|", -1)).indexOf("custom_title");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\|", -1);
                if (column >= 0 && column < fields.length) {
                    titles.add(fields[column]);
                }
            }
        }
        return titles;
    }

    /** A timed workload returning a value the timer accumulates so it cannot be optimised away. */
    @FunctionalInterface
    private interface Work {
        long run();
    }
}
