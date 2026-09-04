# fuzzywuzzy-java

A complete Java port of [seatgeek/fuzzywuzzy](https://github.com/seatgeek/fuzzywuzzy) 0.18.0 —
fuzzy string matching using Levenshtein distance.

> **Upstream notice (preserved from the source repo)**
>
> The Python project has been renamed and moved to https://github.com/seatgeek/thefuzz.
> **TheFuzz** version 0.19.0 correlates with this project's 0.18.0 version.
> PRs and issues for the *Python* project need to be filed against TheFuzz.

This port targets **behavioural equivalence, not idiomatic redesign**. Every scorer returns the
same integer the Python original returns, including for pathological, empty, `null` and non-BMP
input. Where the Python code has bugs, the bugs are reproduced — see
[PORTING_NOTES.md](PORTING_NOTES.md).

## Requirements

- Java 17 or newer
- Maven 3.9+

Python is **not** required to build, test, run, or package this library. The golden fixtures
are checked in, so the whole suite runs on the JDK alone. A CPython interpreter with
`python-Levenshtein` installed is needed only if you want to *regenerate* those fixtures from
the upstream implementation — see [Verification](#verification).

## Build

```bash
mvn test        # compiles and runs the full parity suite
mvn package     # builds the jar plus a sources jar
mvn verify      # both of the above; what CI runs
```

`./release {major|minor|patch}` cuts a release: it runs `mvn verify`, bumps the version in
`pom.xml`, `FuzzyWuzzy.VERSION` and `README.md`, updates `CHANGES.rst`, then tags and deploys.

## Usage

### Scoring two strings — `Fuzz` (`fuzzywuzzy.fuzz`)

```java
import com.seatgeek.fuzzywuzzy.Fuzz;

Fuzz.ratio("this is a test", "this is a test!");                 // 97
Fuzz.partialRatio("this is a test", "this is a test!");          // 100
Fuzz.tokenSortRatio("fuzzy wuzzy was a bear", "wuzzy fuzzy was a bear");  // 100
Fuzz.tokenSetRatio("fuzzy was a bear", "fuzzy fuzzy was a bear");         // 100
Fuzz.weightedRatio("The Wizard of Oz", "Wizard of Oz, The");     // 95
```

| Java | Python |
| --- | --- |
| `Fuzz.ratio` | `fuzz.ratio` |
| `Fuzz.partialRatio` | `fuzz.partial_ratio` |
| `Fuzz.tokenSortRatio` | `fuzz.token_sort_ratio` |
| `Fuzz.partialTokenSortRatio` | `fuzz.partial_token_sort_ratio` |
| `Fuzz.tokenSetRatio` | `fuzz.token_set_ratio` |
| `Fuzz.partialTokenSetRatio` | `fuzz.partial_token_set_ratio` |
| `Fuzz.quickRatio` | `fuzz.QRatio` |
| `Fuzz.unicodeQuickRatio` | `fuzz.UQRatio` |
| `Fuzz.weightedRatio` | `fuzz.WRatio` |
| `Fuzz.unicodeWeightedRatio` | `fuzz.UWRatio` |

Every scorer also has a four-argument overload taking the Python keyword arguments
`force_ascii` and `full_process` (the unicode variants take only `full_process`):

```java
Fuzz.tokenSetRatio(s1, s2, /* forceAscii */ false, /* fullProcess */ true);
```

### Searching a collection — `Process` (`fuzzywuzzy.process`)

```java
import com.seatgeek.fuzzywuzzy.Process;
import com.seatgeek.fuzzywuzzy.model.ExtractedResult;

List<String> choices = List.of(
        "Atlanta Falcons", "New York Jets", "New York Giants", "Dallas Cowboys");

List<ExtractedResult<String>> best = Process.extract("new york jets", choices);
// [('New York Jets', 100), ('New York Giants', 79), ('Atlanta Falcons', 29), ('Dallas Cowboys', 22)]

ExtractedResult<String> one = Process.extractOne("cowboys", choices);
// ('Dallas Cowboys', 90)
```

- `Process.extract(query, choices)` — top 5, ranked. Like Python it **ignores** `scoreCutoff`.
- `Process.extractBests(query, choices, processor, scorer, scoreCutoff, limit)` — honours the cutoff.
- `Process.extractOne(...)` — the single best result, or `null` when there is nothing to return.
- `Process.extractWithoutOrder(...)` — a lazy `Stream`, the counterpart of the Python generator.
- `Process.dedupe(list)` / `dedupe(list, threshold)` / `dedupe(list, threshold, scorer)`.

`ExtractedResult<T>` is a record of `choice`, `score`, `key` and `fromMapping`. The `key`
is populated only for the `Map` overloads, matching Python's 3-tuple `(choice, score, key)`.

Choices may be any `Iterable` (including an unsized one, matching Python's support for
generators) or a `Map`. Choices are never copied or normalised, so `extractOne` returns the
identical object you passed in.

> **Import `Process` explicitly.** `java.lang.Process` is imported into every compilation
> unit automatically, so a wildcard `import com.seatgeek.fuzzywuzzy.*;` makes the name
> ambiguous and the file will not compile. A single-type import — `import
> com.seatgeek.fuzzywuzzy.Process;` — takes precedence and resolves it. The class keeps the
> name of the Python module it ports rather than being renamed to avoid the clash.

### Custom scorers and processors

```java
Process.extractOne(query, choices,
        input -> input.toString().trim(),   // Processor
        (a, b) -> Fuzz.ratio(a, b),         // Scorer
        0);
```

Passing one of the ten `BuiltinScorer` constants activates the same double-processing avoidance
Python performs by identity-checking the scorer against its built-in list. A custom lambda takes
the un-optimised path, exactly as in Python.

### Choosing the distance backend

`fuzz.py` binds `SequenceMatcher` to `fuzzywuzzy.StringMatcher` when `python-Levenshtein` is
installed, and falls back to `difflib` otherwise. The two disagree on roughly 12% of `ratio()`
calls, so the choice is observable and is exposed explicitly:

```java
Fuzz.useLevenshteinBackend();   // default; equals python-Levenshtein being installed
Fuzz.useDifflibBackend();       // equals the pure-Python fallback
```

Both backends are independently verified against their Python counterparts.

## Verification

The port is checked against the real Python implementation rather than against hand-written
expectations. Golden fixtures under `src/test/resources/golden/` are generated by running the
source repository inside its own virtualenv:

| Fixture | Contents |
| --- | --- |
| `matcher.tsv.gz` | 10,177 string pairs × editops, opcodes, matching blocks, ratio, distance, for both backends |
| `fuzz.tsv.gz` | 16,230 pairs × all 24 scorer/flag combinations = 389,520 assertions |
| `process.tsv.gz` | 14,258 `extract`/`extractBests`/`extractOne`/`dedupe` scenarios over 8 corpora |
| `word_ranges.txt`, `space_ranges.txt`, `lower_map.txt`, `upper_map.txt` | Exhaustive CPython Unicode oracles across all 1,114,112 code points |

Regenerate them with the capture scripts recorded in [`tools/README.md`](tools/README.md).
They run under CPython against the original library rather than as part of this build, for
example:

```bash
PYTHONPATH=/path/to/seatgeek_fuzzywuzzy \
  /path/to/seatgeek_fuzzywuzzy/.venv/bin/python \
  generate_golden_fuzz.py src/test/resources/golden/fuzz.tsv.gz 4242
```

The Python test suites are ported too: `RatioTest`, `ProcessTest`, `UtilsTest`,
`StringProcessingTest` and `ValidatorTest` mirror `test_fuzzywuzzy.py`, and
`FuzzyWuzzyProperties` reimplements the Hypothesis properties from
`test_fuzzywuzzy_hypothesis.py` using jqwik.

## Benchmarks

`benchmarks.py` is ported to `Benchmarks.java`, reporting in the same format:

```bash
mvn -q test-compile
java -cp target/classes:target/test-classes com.seatgeek.fuzzywuzzy.benchmark.Benchmarks
```

## License

GPLv2, the same as upstream. Copyright (c) 2014 SeatGeek. See [LICENSE.txt](LICENSE.txt).
