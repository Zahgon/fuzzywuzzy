package com.seatgeek.fuzzywuzzy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code TestCodeFormat} from test_fuzzywuzzy.py:317.
 *
 * <p>The Python test points pycodestyle at the {@code fuzzywuzzy} package, silences {@code E501}
 * and demands zero findings. pycodestyle enforces PEP 8, which for the parts that survive
 * translation is the same set of whitespace rules this repository already declares in its
 * {@code .editorconfig}. So the port keeps the shape of the original - run the project's own
 * formatting policy over the project's own sources, exempt line length, require zero findings -
 * and swaps the rule engine for the one that governs Java here.
 */
class CodeFormatTest {

    /**
     * The Python test only scans the implementation package; there is no reason to be that lenient
     * with the tests, and both trees are clean, so both are scanned.
     */
    private static final List<Path> SOURCE_TREES = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java"));

    private static final int INDENT_SIZE = 4;

    @Test
    @DisplayName("every Java source obeys the repository .editorconfig, line length excepted")
    void testPep8Conformance() throws IOException {
        Path root = projectRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path tree : SOURCE_TREES) {
            List<Path> sources = javaSourcesUnder(root.resolve(tree));
            assertFalse(sources.isEmpty(), tree + " has no Java sources, so the scan would pass vacuously");
            scanned += sources.size();
            for (Path source : sources) {
                check(root.relativize(source).toString(), Files.readAllBytes(source), violations);
            }
        }
        assertTrue(scanned >= 30, "expected the whole port to be scanned, saw only " + scanned + " files");
        assertEquals(List.of(), violations, "EDITORCONFIG POLICE - WOOOOOWOOOOOOOOOO");
    }

    /**
     * Surefire forks with the project directory as its working directory, but walking up keeps the
     * test runnable from an IDE that picked some nested module directory instead.
     */
    private static Path projectRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve(SOURCE_TREES.get(0)))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no Maven project root at or above " + start);
    }

    private static List<Path> javaSourcesUnder(Path tree) throws IOException {
        try (Stream<Path> entries = Files.walk(tree)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static void check(String name, byte[] bytes, List<String> violations) {
        String text = decodeUtf8(name, bytes, violations);
        if (text == null) {
            return;
        }
        if (text.indexOf('\r') >= 0) {
            violations.add(name + ": end_of_line = lf, but the file carries a carriage return");
        }
        if (!text.endsWith("\n")) {
            violations.add(name + ": insert_final_newline = true, but the file has no final newline");
        } else if (text.endsWith("\n\n")) {
            violations.add(name + ": insert_final_newline = true means one, but the file ends blank");
        }

        String[] lines = text.split("\n", -1);
        String previous = null;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String position = name + ":" + (index + 1);
            if (!line.equals(line.stripTrailing())) {
                violations.add(position + ": trim_trailing_whitespace = true, but this line is padded");
            }
            String content = line.strip();
            if (content.isEmpty()) {
                continue;
            }
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            if (indent.indexOf('\t') >= 0) {
                violations.add(position + ": indent_style = space, but this line is indented with a tab");
            }
            if (opensAStatement(previous) && !content.startsWith("*") && indent.length() % INDENT_SIZE != 0) {
                violations.add(position + ": indent_size = " + INDENT_SIZE + ", but this line is indented by "
                        + indent.length());
            }
            previous = content;
        }
    }

    /**
     * A line that continues the previous one may line up with whatever delimiter it is continuing -
     * pycodestyle allows the same alignment - so the indent rule only applies where a statement,
     * declaration or block actually begins.
     */
    private static boolean opensAStatement(String previous) {
        return previous == null
                || previous.endsWith("{")
                || previous.endsWith("}")
                || previous.endsWith(";")
                || previous.endsWith("*/");
    }

    private static String decodeUtf8(String name, byte[] bytes, List<String> violations) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            violations.add(name + ": charset = utf-8, but the bytes do not decode: " + e.getMessage());
            return null;
        }
    }
}
