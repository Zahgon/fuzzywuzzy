# Golden fixture generators

The Java port asserts against fixtures captured from the reference Python library, so the
scripts that capture them have to run under CPython, against `seatgeek/fuzzywuzzy` itself,
with the `python-Levenshtein` backend installed. They are not part of this project's build
and nothing here compiles or ships them; they are recorded below so the fixtures under
`src/test/resources/golden/` stay reproducible from a clean checkout.

To use one, copy the block into a file and run it with the reference interpreter:

```bash
PYTHONPATH=/path/to/seatgeek_fuzzywuzzy \
  /path/to/seatgeek_fuzzywuzzy/.venv/bin/python \
  generate_golden_fuzz.py src/test/resources/golden/fuzz.tsv.gz 4242
```

The seed argument is what makes a regenerated fixture byte-identical to the committed one.
Each script's own docstring gives its exact invocation and output format.

| Script | Fixture | Consumed by |
| --- | --- | --- |
| `generate_golden_fuzz.py` | `golden/fuzz.tsv.gz` | `FuzzGoldenTest` |
| `generate_golden_matcher.py` | `golden/matcher.tsv.gz` | `MatcherGoldenTest` |
| `generate_golden_process.py` | `golden/process.tsv.gz` | `ProcessGoldenTest` |

## generate_golden_fuzz.py

```python
"""Emit a golden fixture of fuzz.py scores for the Java port to assert against.

Run with the reference interpreter so that the python-Levenshtein backend is active:

    .venv/bin/python tools/generate_golden_fuzz.py src/test/resources/golden/fuzz.tsv.gz 4242

Output is a gzipped TSV whose first line is a header of scorer specs and whose remaining
lines are `b64(s1) TAB b64(s2) TAB score...` with one score per spec, in header order.
"""

import base64
import gzip
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))

from fuzzywuzzy import fuzz

# (name, callable) pairs. The name is what the Java test switches on.
SPECS = [
    ("ratio", lambda a, b: fuzz.ratio(a, b)),
    ("partial_ratio", lambda a, b: fuzz.partial_ratio(a, b)),
    ("token_sort_ratio:T:T", lambda a, b: fuzz.token_sort_ratio(a, b, True, True)),
    ("token_sort_ratio:F:T", lambda a, b: fuzz.token_sort_ratio(a, b, False, True)),
    ("token_sort_ratio:T:F", lambda a, b: fuzz.token_sort_ratio(a, b, True, False)),
    ("partial_token_sort_ratio:T:T", lambda a, b: fuzz.partial_token_sort_ratio(a, b, True, True)),
    ("partial_token_sort_ratio:F:T", lambda a, b: fuzz.partial_token_sort_ratio(a, b, False, True)),
    ("partial_token_sort_ratio:T:F", lambda a, b: fuzz.partial_token_sort_ratio(a, b, True, False)),
    ("token_set_ratio:T:T", lambda a, b: fuzz.token_set_ratio(a, b, True, True)),
    ("token_set_ratio:F:T", lambda a, b: fuzz.token_set_ratio(a, b, False, True)),
    ("token_set_ratio:T:F", lambda a, b: fuzz.token_set_ratio(a, b, True, False)),
    ("partial_token_set_ratio:T:T", lambda a, b: fuzz.partial_token_set_ratio(a, b, True, True)),
    ("partial_token_set_ratio:F:T", lambda a, b: fuzz.partial_token_set_ratio(a, b, False, True)),
    ("partial_token_set_ratio:T:F", lambda a, b: fuzz.partial_token_set_ratio(a, b, True, False)),
    ("QRatio:T:T", lambda a, b: fuzz.QRatio(a, b, True, True)),
    ("QRatio:F:T", lambda a, b: fuzz.QRatio(a, b, False, True)),
    ("QRatio:T:F", lambda a, b: fuzz.QRatio(a, b, True, False)),
    ("UQRatio:T", lambda a, b: fuzz.UQRatio(a, b, True)),
    ("UQRatio:F", lambda a, b: fuzz.UQRatio(a, b, False)),
    ("WRatio:T:T", lambda a, b: fuzz.WRatio(a, b, True, True)),
    ("WRatio:F:T", lambda a, b: fuzz.WRatio(a, b, False, True)),
    ("WRatio:T:F", lambda a, b: fuzz.WRatio(a, b, True, False)),
    ("UWRatio:T", lambda a, b: fuzz.UWRatio(a, b, True)),
    ("UWRatio:F", lambda a, b: fuzz.UWRatio(a, b, False)),
]

# Strings lifted from test_fuzzywuzzy.py plus the shapes that exercise the decorator stack,
# the empty-token paths and the length-ratio thresholds in WRatio.
EDGE_CASES = [
    ("", ""),
    ("", "a"),
    ("a", ""),
    ("a", "a"),
    ("new york mets", "new york mets"),
    ("new york mets", "new YORK mets"),
    ("new york mets", "the wonderful new york mets"),
    ("new york mets", "new york mets vs atlanta braves"),
    ("new york mets vs atlanta braves", "atlanta braves vs new york mets"),
    ("new york mets", "new york mets - atlanta braves"),
    ("mariners vs angels", "los angeles angels of anaheim at seattle mariners"),
    ("HSINCHUANG", "SINJHUANG DISTRICT"),
    ("' Test Your Urge To Purge '", "Test Your Urge To Purge"),
    (". . . . .", "!!!!!"),
    ("_ _ _", "___"),
    ("a_b-c", "a b c"),
    ("\u00c1\u00c9\u00cd", "AEI"),
    ("\u0430\u0431\u0432", "abv"),
    ("\U0001f600\U0001f601", "\U0001f600"),
    ("fuzzy wuzzy was a bear", "wuzzy fuzzy was a bear"),
    ("fuzzy was a bear", "fuzzy fuzzy was a bear"),
    ("fuzzy was a bear", "muzzy was a bear"),
    ("a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
    ("ab", "abababababababababab"),
    ("     ", "  "),
    ("\t\n\u00a0", " "),
    ("x", "\u00a0\u00a0\u00a0x\u00a0\u00a0\u00a0"),
    ("one two three", "three two one"),
    ("one two three", "one two three four five six"),
    ("chicago cubs vs new york mets", "CitiField"),
]

ALPHABETS = {
    "tiny": "ab",
    "small": "abcde ",
    "ascii": "abcdefghij klmnop_-.,'0123456789ABCDEF",
    "unicode": "ab \u00e9\u00fc\u00df\u0430\u0431\u4e2d\u6587\U0001f600\u0301_",
}


def random_pairs(rng, alphabet, count, min_len, max_len):
    pairs = []
    for _ in range(count):
        n1 = rng.randint(min_len, max_len)
        n2 = rng.randint(min_len, max_len)
        s1 = "".join(rng.choice(alphabet) for _ in range(n1))
        s2 = "".join(rng.choice(alphabet) for _ in range(n2))
        pairs.append((s1, s2))
    return pairs


def mutated_pairs(rng, alphabet, count, min_len, max_len):
    """Pairs that are close to each other, where the interesting score differences live."""
    pairs = []
    for _ in range(count):
        n = rng.randint(min_len, max_len)
        s1 = "".join(rng.choice(alphabet) for _ in range(n))
        chars = list(s1)
        for _ in range(rng.randint(0, 3)):
            if not chars:
                break
            op = rng.choice(("del", "ins", "sub", "swap"))
            i = rng.randrange(len(chars))
            if op == "del":
                del chars[i]
            elif op == "ins":
                chars.insert(i, rng.choice(alphabet))
            elif op == "sub":
                chars[i] = rng.choice(alphabet)
            elif op == "swap" and i + 1 < len(chars):
                chars[i], chars[i + 1] = chars[i + 1], chars[i]
        pairs.append((s1, "".join(chars)))
    return pairs


def token_shuffled_pairs(rng, count):
    """Pairs built from real words so the token_sort / token_set paths get exercised."""
    words = ("new york mets atlanta braves chicago cubs boston red sox "
             "cirque du soleil zarkana las vegas bellagio the wonderful").split()
    pairs = []
    for _ in range(count):
        n = rng.randint(1, 8)
        t1 = [rng.choice(words) for _ in range(n)]
        t2 = list(t1)
        rng.shuffle(t2)
        for _ in range(rng.randint(0, 3)):
            action = rng.choice(("drop", "add", "dupe"))
            if action == "drop" and t2:
                t2.pop(rng.randrange(len(t2)))
            elif action == "add":
                t2.insert(rng.randrange(len(t2) + 1), rng.choice(words))
            elif action == "dupe" and t2:
                t2.append(rng.choice(t2))
        pairs.append((" ".join(t1), " ".join(t2)))
    return pairs


def titledata_pairs(rng, count):
    """Real titles from the bundled corpus, the workload the library was written for."""
    path = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources",
                        "data", "titledata.csv")
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("|")
            if len(parts) >= 4:
                rows.append(parts)
    pairs = []
    for _ in range(count):
        row = rng.choice(rows)
        pairs.append((row[1], row[2]))
        if len(pairs) < count:
            pairs.append((row[1], rng.choice(rows)[3]))
    return pairs[:count]


def main():
    out_path = sys.argv[1]
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 4242
    scale = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    rng = random.Random(seed)

    pairs = list(EDGE_CASES)
    pairs += random_pairs(rng, ALPHABETS["tiny"], 1500 * scale, 0, 6)
    pairs += random_pairs(rng, ALPHABETS["small"], 1500 * scale, 0, 12)
    pairs += random_pairs(rng, ALPHABETS["ascii"], 1500 * scale, 0, 30)
    pairs += random_pairs(rng, ALPHABETS["unicode"], 1500 * scale, 0, 24)
    pairs += mutated_pairs(rng, ALPHABETS["small"], 1500 * scale, 1, 16)
    pairs += mutated_pairs(rng, ALPHABETS["ascii"], 1500 * scale, 1, 40)
    pairs += mutated_pairs(rng, ALPHABETS["unicode"], 1000 * scale, 1, 20)
    pairs += token_shuffled_pairs(rng, 2500 * scale)
    pairs += titledata_pairs(rng, 2500 * scale)
    # Lopsided pairs so that both WRatio length thresholds (1.5 and 8) are crossed.
    pairs += random_pairs(rng, ALPHABETS["ascii"], 600 * scale, 1, 4)
    pairs += [(a, b * rng.randint(2, 12))
              for a, b in random_pairs(rng, ALPHABETS["small"], 600 * scale, 1, 10)]

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with gzip.open(out_path, "wt", encoding="ascii", newline="\n") as out:
        out.write("\t".join(name for name, _ in SPECS) + "\n")
        for s1, s2 in pairs:
            cells = [
                base64.b64encode(s1.encode("utf-8")).decode("ascii"),
                base64.b64encode(s2.encode("utf-8")).decode("ascii"),
            ]
            for _, fn in SPECS:
                cells.append(str(fn(s1, s2)))
            out.write("\t".join(cells) + "\n")

    print("wrote {} pairs x {} scorers to {}".format(len(pairs), len(SPECS), out_path))


if __name__ == "__main__":
    main()
```

## generate_golden_matcher.py

```python
"""Emit the matcher golden fixture from the *installed* Python backends.

Every expectation in the generated TSV comes from either the C ``Levenshtein``
extension (the backend ``fuzz.py`` actually binds) or from ``difflib`` (its
documented fallback). Nothing here reimplements anything: the point is to freeze
observed behaviour so the Java port can be diffed against it.

The file is gzipped; columns are tab separated::

    b64(s1)  b64(s2)  editops  opcodes  mblocks  ratio  distance  dl_ratio  dl_mblocks

Strings are base64 of UTF-8 so the file stays pure ASCII and single-line, and so
that no separator, quoting or newline convention can be mistaken for data.
"""
import base64
import gzip
import random
import string
import sys
from difflib import SequenceMatcher

import Levenshtein as L

TAG = {'delete': 'd', 'insert': 'i', 'replace': 'r', 'equal': 'e'}


def enc(s):
    return base64.b64encode(s.encode('utf-8')).decode('ascii')


def fmt_editops(ops):
    return ';'.join('%s:%d:%d' % (TAG[t], a, b) for t, a, b in ops)


def fmt_opcodes(ops):
    return ';'.join('%s:%d:%d:%d:%d' % (TAG[t], a, b, c, d) for t, a, b, c, d in ops)


def fmt_blocks(blocks):
    return ';'.join('%d:%d:%d' % (a, b, c) for a, b, c in blocks)


def gen(regime, rnd):
    if regime == 'tiny':
        return ''.join(rnd.choice('ab') for _ in range(rnd.randint(0, 8)))
    if regime == 'small':
        return ''.join(rnd.choice('abcde') for _ in range(rnd.randint(0, 15)))
    if regime == 'ascii':
        alpha = string.ascii_letters + string.digits + string.punctuation + ' '
        return ''.join(rnd.choice(alpha) for _ in range(rnd.randint(0, 60)))
    if regime == 'long':
        return ''.join(rnd.choice('abcdefg') for _ in range(rnd.randint(150, 260)))
    if regime == 'autojunk':
        # Long enough to trip difflib's autojunk heuristic (len(b) >= 200) with a
        # small alphabet, so most elements exceed the 1% popularity threshold.
        return ''.join(rnd.choice('abc ') for _ in range(rnd.randint(200, 400)))
    if regime == 'unicode':
        pool = [chr(c) for c in range(0x80, 0x100)] + \
               [chr(c) for c in range(0x400, 0x460)] + \
               [chr(c) for c in range(0x4E00, 0x4E40)] + \
               [chr(0x1F600 + i) for i in range(0x20)] + list('abc ')
        return ''.join(rnd.choice(pool) for _ in range(rnd.randint(0, 30)))
    if regime == 'nearly_equal':
        base = ''.join(rnd.choice('abcdefgh') for _ in range(rnd.randint(5, 40)))
        if not base or rnd.random() < 0.25:
            return base
        i = rnd.randrange(len(base))
        mode = rnd.randrange(3)
        if mode == 0:
            return base[:i] + rnd.choice('abcdefgh') + base[i + 1:]
        if mode == 1:
            return base[:i] + base[i + 1:]
        return base[:i] + rnd.choice('abcdefgh') + base[i:]
    raise ValueError(regime)


EDGE_CASES = [
    ('', ''), ('', 'a'), ('a', ''), ('a', 'a'), ('a', 'b'),
    ('abcd', 'xbcd'), ('abcd', 'abed'), ('ab', 'abc'), ('abxcd', 'abcd'),
    ('qabxcd', 'abycdf'), (' abcd', 'abcd abcd'), ('ab', 'acab'),
    ('new york mets', 'new YORK mets'), ('mariners vs angels', 'angels vs mariners'),
    ('\U0001f600', '\U0001f601'), ('\u00e9', 'e'), ('\u0041\u030a', '\u00c5'),
]


def main():
    out_path = sys.argv[1]
    rnd = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 4242)
    scale = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    regimes = ['tiny', 'small', 'ascii', 'long', 'autojunk', 'unicode', 'nearly_equal']

    pairs = list(EDGE_CASES)
    for regime in regimes:
        # Long inputs produce edit scripts hundreds of entries wide, so they are
        # sampled sparsely to keep the checked-in fixture around a megabyte.
        n = (80 if regime in ('long', 'autojunk') else 2000) * scale
        for _ in range(n):
            pairs.append((gen(regime, rnd), gen(regime, rnd)))

    with gzip.open(out_path, 'wt', encoding='ascii', compresslevel=9) as fh:
        for a, b in pairs:
            editops = [tuple(x) for x in L.editops(a, b)]
            opcodes = [tuple(x) for x in L.opcodes(a, b)]
            blocks = [tuple(x) for x in L.matching_blocks(L.opcodes(a, b), a, b)]
            ratio = L.ratio(a, b)
            dist = L.distance(a, b)
            sm = SequenceMatcher(None, a, b)
            dl_blocks = [tuple(x) for x in sm.get_matching_blocks()]
            dl_ratio = sm.ratio()
            fh.write('\t'.join([
                enc(a), enc(b),
                fmt_editops(editops), fmt_opcodes(opcodes), fmt_blocks(blocks),
                repr(ratio), str(dist),
                repr(dl_ratio), fmt_blocks(dl_blocks),
            ]) + '\n')

    print('wrote %d pairs to %s' % (len(pairs), out_path))


if __name__ == '__main__':
    main()
```

## generate_golden_process.py

```python
#!/usr/bin/env python
# encoding: utf-8
"""Generate the golden fixture for process.py.

Run with the source repo's interpreter and PYTHONPATH:

    PYTHONPATH=/path/to/seatgeek_fuzzywuzzy \
        /path/to/seatgeek_fuzzywuzzy/.venv/bin/python \
        tools/generate_golden_process.py src/test/resources/golden/process.tsv.gz 4242

Output format (gzipped TSV). Two record kinds:

    POOL <name> <b64item;b64item;...>
    ROW  <op> <scorer> <limit> <cutoff> <b64query> <pool> <result>

`limit` and `cutoff` are integers or `None`/`-`. `result` encodes an ordered list of
`b64(choice):score` pairs joined by `;`, or `NONE` for a null extractOne, or `ERROR:<type>`
when Python raised.
"""
from __future__ import annotations

import base64
import csv
import gzip
import os
import random
import sys

from fuzzywuzzy import fuzz, process

SCORERS = [
    ("ratio", fuzz.ratio),
    ("partial_ratio", fuzz.partial_ratio),
    ("token_sort_ratio", fuzz.token_sort_ratio),
    ("partial_token_sort_ratio", fuzz.partial_token_sort_ratio),
    ("token_set_ratio", fuzz.token_set_ratio),
    ("partial_token_set_ratio", fuzz.partial_token_set_ratio),
    ("QRatio", fuzz.QRatio),
    ("UQRatio", fuzz.UQRatio),
    ("WRatio", fuzz.WRatio),
    ("UWRatio", fuzz.UWRatio),
]

HERE = os.path.dirname(os.path.abspath(__file__))
TITLEDATA = os.path.join(HERE, "..", "src", "test", "resources", "data", "titledata.csv")


def b64(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def load_titles(limit):
    titles = []
    with open(TITLEDATA, encoding="utf-8") as handle:
        reader = csv.DictReader(handle, delimiter="|")
        for row in reader:
            for column in ("custom_title", "stubhub_title", "vividseats_title"):
                value = row.get(column)
                if value:
                    titles.append(value)
            if len(titles) >= limit:
                break
    # Distinct values only: the fixture identifies a choice by its text, so duplicates
    # would make the expected ordering ambiguous.
    seen = set()
    unique = []
    for title in titles:
        if title not in seen:
            seen.add(title)
            unique.append(title)
    return unique[:limit]


def build_pools(rng):
    titles = load_titles(400)
    pools = {
        "titles": titles[:120],
        "tiny": ["a", "b", "ab", "ba", "aab"],
        "ties": ["aa", "ab", "ac", "ad", "ae", "af"],
        "words": [
            "new york mets", "new YORK mets", "the wonderful new york mets",
            "the wonderful new york mets vs atlanta braves", "new york city mets",
            "chicago cubs", "atlanta braves", "boston red sox",
        ],
        "unicode": [
            "\u00e5\u00e4\u00f6", "aao", "\u4f60\u597d\u4e16\u754c", "hello world",
            "\U0001f600\U0001f601", "\u0441\u043e\u0431\u0430\u043a\u0430", "sobaka",
        ],
        "empties": ["", " ", "::::", "a", "aa"],
        "mixed_case": ["ABC", "abc", "AbC", "a b c", "a_b_c", "a-b-c"],
    }
    rng.shuffle(titles)
    pools["titles_shuffled"] = titles[120:240]
    return pools


QUERIES = [
    "new york mets", "new york", "mets", "", "::::", "a", "aa",
    "the wonderful new york mets", "chicago cubs vs new york mets",
    "\u00e5\u00e4\u00f6", "\u4f60\u597d", "\U0001f600",
    "ABC", "a_b_c", "  padded  ",
]


def encode_results(results):
    parts = []
    for item in results:
        parts.append("%s:%d" % (b64(item[0]), item[1]))
    return ";".join(parts) if parts else "EMPTY"


def encode_keyed(results):
    parts = []
    for item in results:
        parts.append("%s:%d:%s" % (b64(item[0]), item[1], b64(item[2])))
    return ";".join(parts) if parts else "EMPTY"


def run(callable_):
    try:
        return callable_()
    except Exception as exc:  # noqa: BLE001 - the exception type is part of the fixture
        return "ERROR:%s" % type(exc).__name__


def main():
    out_path = sys.argv[1]
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 4242
    rng = random.Random(seed)

    pools = build_pools(rng)
    rows = []

    for name, items in sorted(pools.items()):
        rows.append(("POOL", name, ";".join(b64(i) for i in items), "", "", "", ""))

    for pool_name, items in sorted(pools.items()):
        for query in QUERIES:
            for scorer_name, scorer in SCORERS:
                # extractWithoutOrder: full unranked listing.
                value = run(lambda: encode_results(
                    list(process.extractWithoutOrder(query, items, scorer=scorer))))
                rows.append(("wo", scorer_name, "-", "0", b64(query), pool_name, value))

                for limit in (None, 1, 3, 5):
                    value = run(lambda limit=limit: encode_results(
                        process.extract(query, items, scorer=scorer, limit=limit)))
                    rows.append(("extract", scorer_name,
                                 "None" if limit is None else str(limit),
                                 "-", b64(query), pool_name, value))

                for cutoff in (0, 50, 90):
                    value = run(lambda cutoff=cutoff: encode_results(
                        process.extractBests(query, items, scorer=scorer,
                                             score_cutoff=cutoff, limit=None)))
                    rows.append(("bests", scorer_name, "None", str(cutoff),
                                 b64(query), pool_name, value))

                    one = run(lambda cutoff=cutoff: process.extractOne(
                        query, items, scorer=scorer, score_cutoff=cutoff))
                    if isinstance(one, str) and one.startswith("ERROR:"):
                        value = one
                    elif one is None:
                        value = "NONE"
                    else:
                        value = encode_results([one])
                    rows.append(("one", scorer_name, "-", str(cutoff),
                                 b64(query), pool_name, value))

    # Mapping-shaped choices.
    for pool_name in ("words", "tiny", "unicode"):
        items = pools[pool_name]
        mapping = {"k%d" % index: value for index, value in enumerate(items)}
        for query in QUERIES[:8]:
            for scorer_name, scorer in SCORERS:
                value = run(lambda: encode_keyed(
                    list(process.extractWithoutOrder(query, mapping, scorer=scorer))))
                rows.append(("wo_map", scorer_name, "-", "0", b64(query), pool_name, value))

                value = run(lambda: encode_keyed(
                    process.extract(query, mapping, scorer=scorer, limit=None)))
                rows.append(("extract_map", scorer_name, "None", "-",
                             b64(query), pool_name, value))

                one = run(lambda: process.extractOne(query, mapping, scorer=scorer))
                if isinstance(one, str) and one.startswith("ERROR:"):
                    value = one
                elif one is None:
                    value = "NONE"
                else:
                    value = encode_keyed([one])
                rows.append(("one_map", scorer_name, "-", "0", b64(query), pool_name, value))

    # dedupe over every pool and a few thresholds.
    dedupe_pools = dict(pools)
    dedupe_pools["dupes"] = [
        "Frodo Baggin", "Frodo Baggins", "F. Baggins", "Samwise G.",
        "Gandalf", "Bilbo Baggins",
    ]
    dedupe_pools["nodupes"] = ["Tom", "Dick", "Harry"]
    dedupe_pools["names"] = [
        "Frodo Baggins", "Tom Sawyer", "Bilbo Baggin", "Samuel L. Jackson",
        "F. Baggins", "Frody Baggins", "Bilbo Baggins",
    ]
    for pool_name, items in sorted(dedupe_pools.items()):
        for threshold in (50, 70, 90):
            for scorer_name, scorer in SCORERS:
                value = run(lambda: ";".join(
                    b64(x) for x in process.dedupe(items, threshold=threshold, scorer=scorer))
                    or "EMPTY")
                rows.append(("dedupe", scorer_name, "-", str(threshold),
                             "-", pool_name, value))

    with gzip.open(out_path, "wt", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write("\t".join(row) + "\n")

    print("wrote %d rows to %s" % (len(rows), out_path))


if __name__ == "__main__":
    main()
```
