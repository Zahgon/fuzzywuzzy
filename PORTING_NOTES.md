# Porting notes

How `fuzzywuzzy` 0.18.0 was translated from Python to Java, what had to be reproduced exactly,
which upstream bugs were deliberately preserved, and where behaviour necessarily differs.

The guiding rule throughout: **the Python implementation is the specification.** Whenever the
answer was not obvious from reading the source, it was resolved by executing the source repository
in its own virtualenv and recording the result as a golden fixture.

---

## 1. File-by-file mapping

| Python | Java |
| --- | --- |
| `fuzzywuzzy/__init__.py` | `FuzzyWuzzy.java` (`VERSION`) |
| `fuzzywuzzy/string_processing.py` | `StringProcessor.java` |
| `fuzzywuzzy/utils.py` | `Utils.java` |
| `fuzzywuzzy/StringMatcher.py` | `matcher/LevenshteinStringMatcher.java` |
| `fuzzywuzzy/fuzz.py` | `Fuzz.java` |
| `fuzzywuzzy/process.py` | `Process.java`, `Processor.java`, `Processors.java`, `BuiltinScorer.java`, `Scorer.java` |
| `Levenshtein` C extension (rapidfuzz) | `matcher/LevenshteinCore.java` |
| `difflib.SequenceMatcher` (fallback) | `matcher/DifflibSequenceMatcher.java` |
| `benchmarks.py` | `benchmark/Benchmarks.java` |
| `test_fuzzywuzzy.py` | `RatioTest`, `ProcessTest`, `UtilsTest`, `StringProcessingTest`, `ValidatorTest` |
| `test_fuzzywuzzy_hypothesis.py` | `FuzzyWuzzyProperties.java` (jqwik) |
| `test_fuzzywuzzy_pytest.py` | `ProcessTest.testProcessWarning` |
| — (Python semantics support) | `internal/PyStr`, `PyCase`, `PyMath`, `PyRepr`, `PyWarnings`, `UnicodeTables` |

---

## 2. The dependency that is not declared

`fuzz.py` opens with:

```python
try:
    from .StringMatcher import StringMatcher as SequenceMatcher
except ImportError:
    from difflib import SequenceMatcher
```

`python-Levenshtein` is **installed in the source repo's virtualenv**, so the live backend is
`fuzzywuzzy.StringMatcher.StringMatcher`, which delegates to the rapidfuzz C extension. This is
not a detail that can be waved away: measured over 20,000 random pairs, the two backends produce
different `ratio()` values on **12%** of inputs and different `get_matching_blocks()` on **46%**.

Both are therefore ported, and `Fuzz.useLevenshteinBackend()` / `Fuzz.useDifflibBackend()` select
between them. Levenshtein is the default because that is what the installed source repo does.

Python-verified divergences pinned in `FuzzGoldenTest.difflibBackendIsSelectable`:

| s1 | s2 | Levenshtein | difflib |
| --- | --- | --- | --- |
| `beae` | `abaebcdbe` | 46 | 31 |
| `dddabd` | `ecbdecdcd` | 40 | 27 |
| `caacdcbecd` | `cababdbc` | 67 | 44 |
| `dcadd` | `abbbabedb` | 29 | 14 |
| `cedbacde` | `ebebeeadb` | 47 | 35 |

### Reverse-engineering the C extension

rapidfuzz's edit-script backtrace is not documented and does not match the textbook algorithm, so
it was recovered empirically (`tools/probe_backtrace.py`, `tools/probe_backtrace2.py`) and then
confirmed on **51,000 pairs × 5 functions = 255,000 assertions** with zero mismatches, across
tiny, short-ASCII, long (150–260 char), and Unicode regimes including Latin-1, Cyrillic, CJK and
non-BMP emoji.

The recovered algorithm, now `LevenshteinCore.editops`:

1. Strip the common prefix (length `off`), then strip the common suffix from what remains.
   Affix stripping is **required** — omitting it produced 3,454 mismatches.
2. Build the full unit-cost Levenshtein matrix `D[row][col] = distance(s1[:col], s2[:row])`.
3. Backtrace from `col = len1, row = len2` using the VP/VN tests:

```
while row and col:
    if D[row][col] - D[row][col-1] == 1:               # deletion
        col -= 1; emit('delete', col+off, row+off)
    else:
        row -= 1
        if row and D[row][col] - D[row][col-1] == -1:  # insertion
            emit('insert', col+off, row+off)
        else:
            col -= 1
            if s1[col] != s2[row]: emit('replace', col+off, row+off)
while col: col -= 1; emit('delete', col+off, off)
while row: row -= 1; emit('insert', off, row+off)
reverse()
```

A subtle consequence worth stating: **`Levenshtein.ratio` is not derived from these opcodes.** It
is the normalised Indel (LCS) similarity, `1 - (len1 + len2 - 2·LCS) / (len1 + len2)`, a different
alignment entirely — the opcodes allow substitution, the ratio does not. `ratio("", "")` is `1.0`.

---

## 3. Python string semantics that Java does not share

These are the traps that make a naive port silently wrong on real data.

### 3.1 Banker's rounding

`utils.intr(n)` is `int(round(n))`, and Python 3's `round` breaks ties to **even**:
`intr(66.5) == 66` but `intr(67.5) == 68`. `Math.round` breaks ties upward and would be wrong on
every half-integer score. `PyMath.intr` implements the correct rule.

### 3.2 Code points, not UTF-16 chars

Python indexes strings by code point. Java indexes by `char`. Every length, slice, matcher input
and length ratio in this port goes through `int[]` code-point arrays (`PyStr.toCodePoints`), so
`Fuzz.ratio("😀😁😂", "😀😁") == 80` exactly as in Python. A `char`-based port returns a
different number here.

### 3.3 `\w` under `(?ui)`

`string_processing.py` uses `re.compile(r"(?ui)\W")`. Java's `\W` is ASCII-only, and
`UNICODE_CHARACTER_CLASS` is *also* wrong — it additionally admits `Mn`, `Me`, `Mc`, `Pc` and
`Join_Control`. Python's `\w` here is exactly the categories `Lu Ll Lt Lm Lo Nd Nl No` plus `_`,
verified over all 1,114,112 code points.

Rather than trust either runtime, the predicate is a frozen table
(`UnicodeTables.WORD_RANGES`, 771 ranges) generated from the CPython oracle.

### 3.4 The JDK's Unicode tables are *newer* than CPython's

This was the most surprising finding. JDK 26 ships a later Unicode revision than CPython 3.14, so
it assigns letter status and case mappings to code points CPython still considers unassigned —
U+088F, U+0C5C, U+0CDC, the `A7Cx`/`A7Dx` Latin extensions, U+10940–U+10950, and the entire
Vithkuqi block U+16EA0–U+16EB8. That is 28 divergent lowercase mappings, 28 uppercase, and several
hundred `\w` classifications.

All of it is pinned to the CPython oracle in `lower_map.txt` / `upper_map.txt`.

### 3.5 …but case mapping is still context-sensitive

A per-code-point mapping table would have been the obvious fix, and it would have been **wrong**.
Python's `str.lower()` implements Final_Sigma: `'ΟΔΟΣ'.lower()` is `'οδος'` with a *final* sigma
(U+03C2), not U+03C3. `'İ'.lower()` expands to two code points. `'ß'.upper()` is `"SS"`. Java's
`String.toLowerCase(Locale.ROOT)` implements the same rules and agrees with Python.

So `PyCase` keeps the JDK's string-level algorithm and only shields the divergent code points: it
substitutes each one with an unused **noncharacter** from U+FDD0–U+FDEF (permanently unassigned in
every Unicode revision, so the JDK treats it as uncased and case-ignorable — precisely how CPython
treats the original), runs `toLowerCase(Locale.ROOT)`, then splices the oracle mapping back in.

The regression test for this is the input `U+0391 U+03A3 U+16EA0`. Python yields
`03B1 03C2 16EA0`; a naive Java `toLowerCase` yields `03B1 03C3 16EBB` — wrong on *both*
characters, because lowercasing the Unicode-17 character also convinces the JDK that the sigma is
no longer final.

`Locale.ROOT` is used everywhere; the default locale would break Turkish dotted-I environments.

### 3.6 Python whitespace ≠ Java whitespace

`str.isspace()` is true for U+001C–U+001F, U+0085 and U+00A0, which `Character.isWhitespace`
rejects. `PyStr.isSpace` hardcodes the 29 code points CPython accepts, and `PyStr.strip` /
`PyStr.split` are built on it. `split()` with no argument splits on *runs* of whitespace and
discards empty fields — including the leading one — which is not what `String.split(" ")` does.

### 3.7 Token sorting is by code point

`sorted(tokens)` orders by code point. Java's `String.compareTo` orders by UTF-16 code unit, which
misorders astral characters against U+E000–U+FFFF. `PyStr.CODE_POINT_ORDER` fixes this and is used
for both `_process_and_sort` and `dedupe`'s alphabetical pass.

### 3.8 Slice clamping

`longer[long_start:long_end]` never raises in Python even when the indices run past the end.
`PyStr.slice` clamps identically, which `partial_ratio` depends on.

---

## 4. Upstream bugs preserved on purpose

None of these are fixed. They are observable behaviour, and the tests assert them.

### 4.1 `asciionly` deletes only U+0080–U+00FF

```python
bad_chars = str('').join([chr(i) for i in range(128, 256)])
```

Despite the name, everything from U+0100 upward survives:
`asciionly('a\xac\u1234\u20ac') == 'aሴ€'`. A "corrected" implementation would strip the
Cyrillic and CJK test fixtures and change dozens of documented scores.

### 4.2 `full_process` does not remove underscores

The docstring claims non-alphanumeric characters are replaced, but `_` is a `\w` character, so
`full_process('a_b-c') == 'a_b c'`. Underscores survive.

### 4.3 The falsy-cache bug in `StringMatcher`

Every cached value is guarded with `if not self._x:`, not `if self._x is None:`. A cached ratio of
`0.0`, a distance of `0`, or an empty opcode list is falsy and gets recomputed on every call.
`LevenshteinStringMatcher` reproduces this exactly (`ratio == null || ratio == 0.0`, and so on) —
it is a performance quirk, but it is also observable through instrumentation and costs nothing to
keep faithful.

### 4.4 `real_quick_ratio` divides by zero

`2.0 * min(len1, len2) / (len1 + len2)` raises `ZeroDivisionError` when both sequences are empty.
`LevenshteinStringMatcher.realQuickRatio` throws `ArithmeticException("division by zero")`.
Note the fallback backend does *not* share this: difflib's `_calculate_ratio` returns `1.0` for
zero length, and `DifflibSequenceMatcher` follows suit.

### 4.5 The `isjunk` warning typo

`warn("isjunk not NOT implemented, it will be ignored")` is reproduced verbatim, double negative
included.

### 4.6 `extract` silently ignores `score_cutoff`

```python
def extract(query, choices, processor=default_processor, scorer=default_scorer, limit=5):
    sl = extractWithoutOrder(query, choices, processor, scorer)   # score_cutoff never passed
```

`extractBests` passes it; `extract` does not. Ported as-is, with a test that pins the asymmetry.

### 4.7 `dedupe` raises `IndexError`

If an item does not score **strictly above** `threshold` against *itself* — which happens with
`QRatio`/`WRatio`/`UQRatio`/`UWRatio` for strings that fail `validate_string`, since those return
0 — then `filtered` is empty, the `len(filtered) == 1` branch is skipped, and `filter_sort[0][0]`
raises. A pool like `['', ' ', '::::', 'a', 'aa']` triggers it. 24 golden fixture rows record
`ERROR:IndexError`; Java throws `IndexOutOfBoundsException` at the same point.

---

## 5. Semantics that required care but are not bugs

### 5.1 Decorator stack order

```python
@utils.check_for_none
@utils.check_for_equivalence
@utils.check_empty_string
def ratio(s1, s2): ...
```

Equivalence is tested **before** emptiness, so `ratio('', '') == 100` while `ratio('x', '') == 0`.
Reversing the two decorators would flip the first result. The Java version composes real
higher-order `Scorer` wrappers (`Utils.checkForNone(checkForEquivalence(checkEmptyString(...)))`)
rather than inlining the checks, both because it preserves the order visibly and because
`test_fuzzywuzzy.py`'s `ValidatorTest` applies those decorators to an arbitrary function and so
requires them to be reusable.

### 5.2 `partial_ratio`'s early return

```python
if r > .995:
    return 100
```

Strictly greater, and it short-circuits the loop — a later block cannot lower the result. Also,
the block list is never empty because both backends append the `(len1, len2, 0)` sentinel.

### 5.3 `WRatio`'s arithmetic

The thresholds are strict (`len_ratio < 1.5`, `len_ratio > 8`), the scale factors compound
(`unbase_scale * partial_scale`), and there is exactly **one** rounding at the very end. Rounding
any intermediate changes results. All intermediates are `double` in Java.

### 5.4 The identity dispatch in `extractWithoutOrder`

Python checks `scorer in [WRatio, QRatio, token_set_ratio, ...]` and
`processor == utils.full_process` **by object identity** to avoid processing every choice twice.
A user-supplied lambda fails that check and takes the slow path.

Java models this with the `BuiltinScorer` enum (`avoidsDoubleProcessing()`, `kind()`) and the
single canonical `Processors.FULL_PROCESS` instance. A custom `Scorer` or `Processor` falls
through to the `else` branch exactly as in Python.

### 5.5 Tie-breaking

`heapq.nlargest(limit, sl, key=...)` decorates entries with a *decreasing* order counter, so ties
resolve to the earlier element — which makes it equivalent to a **stable descending sort** followed
by `take(limit)`. `sorted(..., reverse=True)` is stable too, so the `limit is None` branch agrees.
`max()` returns the **first** maximum. `Process.rank` uses `List.sort` (TimSort, stable) and
`firstMaximum` uses a strict `>`.

### 5.6 Laziness

`extractWithoutOrder` is a generator: the `choices`-empty check runs before `processor(query)`, so
an empty choice list produces **no warning**. Java defers the whole pipeline through
`Stream.of((Object) null).flatMap(...)` so that nothing executes until a terminal operation.

### 5.7 `None` choices are scored, not skipped

`utils.full_process(None, force_ascii=True)` returns the string `'none'`, because `asciidammit`
falls through to `str(s)`. So a `None` choice is compared as the four-letter word "none". No null
guard is involved, and `Fuzz.weightedRatio(null, "x") == 0` happens to fall out of the arithmetic
rather than a special case.

Conversely `full_process(None, force_ascii=False)` raises `TypeError`, so `UWRatio(None, 'x')`
raises in Python. The Java port raises there too.

### 5.8 Choice identity

`ProcessTest.testWithCutoff2` asserts `best_match is choices[0]`. Choices are never copied,
normalised or re-boxed; `ExtractedResult.choice()` returns the original reference and the port has
an `assertSame` test for it.

### 5.9 Python `repr` of non-string choices

`ProcessTest.testWithProcessor` passes a *tuple containing a list* as the query with
`processor=lambda event: event[0]`. Because that processor is not `utils.full_process`, the choice
is stringified by `asciidammit` → `str(list)` → Python `repr`. The expected score of 90 depends on
the resulting four-space gaps produced by `', '` plus the two repr quotes, so `PyRepr` implements
Python's `str`/`repr` for lists, maps, sets, numbers and strings, including its single/double quote
selection rule.

### 5.10 Warning output

`test_fuzzywuzzy_pytest.py` asserts the exact stderr bytes. `PyWarnings.logWarning` emits

```
WARNING:root:Applied processor reduces input query to empty string, all comparisons will have score 0. [Query: ':::::::']
```

byte for byte, and `PyWarnings.setSink` makes it capturable in tests.

---

## 6. Deliberate deviations

Three, all narrow, all documented here because "functionally equivalent" should not mean "quietly
different".

**Scorers receive `String`.** Java's type system cannot hand a user `Scorer` a raw non-`String`
object the way Python can. `Process` stringifies at the scorer boundary using `PyRepr.str`, which
is what `make_type_consistent` does inside every built-in scorer anyway — so all built-in paths are
bit-identical. The only observable difference is a **custom** processor that returns a non-string
*combined with* `RATIO`/`PARTIAL_RATIO`: Python applies `check_for_equivalence` and
`check_empty_string` to the raw object first (`len([]) == 0` → score 0) whereas Java stringifies
first (`len("[]") == 2`). No built-in configuration reaches this branch.

**`dedupe` return type.** Python returns a `dict_keys` view when duplicates were merged and the
*original list object* when nothing merged. Java returns a `List<String>`, still returning the
identical input instance in the nothing-merged case so the `is` check in `test_dedupe` holds.

**Unsized iterables.** Python distinguishes sized from unsized choices via `except TypeError`.
`Process.isDefinitelyEmpty` checks `Collection`/`Map` emptiness and lets anything else fall
through, which reproduces the behaviour for generators. `Process.pyLen` throws
`IllegalArgumentException` where Python would raise `TypeError`.

---

## 7. Verification strategy

Hand-written expectations were avoided wherever possible; the fixtures are machine-generated from
the running Python package.

| Layer | Assertions |
| --- | --- |
| Unicode primitives | 1,114,112 code points × 4 predicates (`\w`, `isspace`, `lower`, `upper`) |
| Matcher backends | 10,177 pairs × 7 outputs, both backends |
| Scorers | 16,230 pairs × 24 scorer/flag combinations = 389,520 |
| Process layer | 14,258 scenarios across 8 corpora, 15 queries, 10 scorers |
| Ported Python suites | `test_fuzzywuzzy.py` (49 cases), Hypothesis properties (2 × 600 tries) |

Corpora deliberately include the adversarial cases: empty strings, whitespace-only strings,
punctuation-only strings, `null`, mixed-case, Cyrillic, CJK, non-BMP emoji, 2,500 real titles from
`titledata.csv`, token-shuffled pairs, and lopsided pairs straddling both `WRatio` length
thresholds (1.5 and 8).

`mvn test` runs all of it: **98 tests, 0 failures**.

---

## 8. Things a future maintainer should not "clean up"

- `Math.round` is not a valid substitute for `PyMath.intr`.
- `String.toLowerCase()` without `Locale.ROOT`, or bypassing `PyCase`, breaks Unicode parity.
- `Character.isLetterOrDigit` / `Character.isWhitespace` are not the Python predicates.
- `String.compareTo` is not code-point order.
- `str.length()` / `charAt` are not Python's `len` / indexing.
- The empty `filtered` list in `dedupe` is *supposed* to throw.
- `extract` is *supposed* to ignore `scoreCutoff`.
