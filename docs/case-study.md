# Case study: optimizing org.json (JSON-java)

A real run of `autor3search-java` against a real, maintained, third-party Java
library. Every number here was measured; nothing is illustrative.

## Setup

| | |
|---|---|
| Target | [`stleary/JSON-java`](https://github.com/stleary/JSON-java) @ `4f859fd` — the `org.json` artifact |
| Machine | Apple M-series, 10 logical cores, macOS |
| Toolchain | OpenJDK 26.0.2.1, Maven 3.9.16 |
| Benchmarks declared | `parseObject`, `parseArray`, `writeObject`, `xmlToJson` |
| Source files frozen | 60 |
| `count` | 10 interleaved rounds per side |
| `benchtime` | 300 ms, 5 warmup + 5 measurement iterations, 1 fork |
| Library test suite | ~9.5 s, run in full before every measurement |
| Experiments | 10 — 4 kept, 5 discarded, 1 failed |
| Harness | v0.1.2, the published release jar |

**The library ships no benchmarks, so I wrote them.** That is not a shortcut
around the design — it is the documented path in
[Repos with no benchmarks](../README.md#repos-with-no-benchmarks), and it is the
normal case in Java, where almost no library keeps JMH in-tree. It does mean one
honest caveat: I chose what "faster" means here. I chose it *before* profiling,
from the library's headline public API — parse a document, parse an array, write
one back out, convert XML to JSON — precisely so the benchmark could not be
picked to match a weakness I had already found. The benchmark file was then
frozen at `baseline` like every other test source, so nothing after that point
could touch it.

This was a full loop of ten experiments driven by `program.md`, ended by
`autor3search-java stop` rather than by running out of things to try.

## Result

**Cumulative: −25.1 %** across the declared benchmark set, geomean, from four
kept experiments. Independently verified: applying all four changes at once to
the original tree and measuring that as a single experiment gives **−26.1 %**,
with every benchmark significant at p ≤ 0.001.

| Benchmark | End-to-end change | p |
|---|---:|---:|
| `writeObject` | **−48.1 %** | 0.00001 |
| `xmlToJson` | **−22.8 %** | 0.00105 |
| `parseObject` | **−13.9 %** | 0.00001 |
| `parseArray` | **−13.6 %** | 0.00001 |

That verification matters beyond this library. The harness advances its
measurement baseline after every KEEP, so each kept score is only that
experiment's own contribution and `report` composes the run total by
**multiplying** them. Nothing had ever checked that the product corresponds to
reality. 25.1 % composed against 26.1 % measured, on four benchmarks over four
compounding changes, says the design does what it claims.

### The four changes that were kept

| # | Change | Effect |
|---|---|---|
| 1 | `JSONObject.quote` writes runs of ordinary characters in one call instead of one call per character | `writeObject` −20.1 % |
| 3 | `JSONTokener` reads a `String` source through a non-synchronizing `Reader` | `parseObject` −15.0 %, `parseArray` −13.7 % |
| 4 | `XML.toJSONObject(String)` uses that reader too, instead of building its own `StringReader` | `xmlToJson` −21.6 % |
| 5 | The write path scans the JSON number grammar directly instead of matching a regex per number | `writeObject` −33.9 % |

Experiments 3 and 4 are the same insight applied twice: `StringReader.read()` is
`synchronized`, and the tokener reads one character at a time, so parsing a
12 KB document cost twelve thousand uncontended lock acquisitions. Experiment 4
exists because `XML.toJSONObject(String)` builds its own `StringReader` and
bypasses the tokener's `String` constructor entirely — which is why `xmlToJson`
was the one benchmark experiment 3 did not move.

### What the first change was, in full

`JSONObject.quote(String, Writer)` — the function that writes every key and every
string value of every document the library serializes — wrote **one character at
a time**:

```java
for (i = 0; i < len; i += 1) {
    b = c;
    c = string.charAt(i);
    switch (c) {
    case '\\': case '"':  w.write('\\'); w.write(c); break;
    // ...
    default:              writeAsHex(w, c);
    }
}
```

`writeAsHex`'s default branch is a plain `w.write(c)`, so an ordinary character
costs a virtual call into the Writer. The change scans forward for the next
character that actually needs escaping and writes the whole run in one call:

```java
int runStart = 0;
for (int i = 0; i < len; i += 1) {
    char c = string.charAt(i);
    if (!needsQuoting(c)) {
        b = c;
        continue;
    }
    if (i > runStart) {
        w.write(string, runStart, i - runStart);
    }
    // ... the original switch, unchanged ...
    b = c;
    runStart = i + 1;
}
if (len > runStart) {
    w.write(string, runStart, len - runStart);
}
```

`needsQuoting` is exactly the set the original switch and `writeAsHex` treat
specially, so the output is unchanged — which is what the library's own test
suite was there to confirm, and did.

## The full log

```
commit    score   best_bench_delta  bytes_delta  status   description
70c3916   0.9433  -20.10             -0.00       keep     quote-batch-runs
a4bab52   0.0000    0.00              0.00       fail     unsync-string-reader
dd71831   0.9207  -14.96             -1.04       keep     unsync-string-reader-v2
5e20ef9   0.9388  -21.56             -0.01       keep     xml-unsync-reader
44ee18a   0.9186  -33.94            -22.02       keep     no-regex-per-number
25ee804   0.9657   -7.46             -0.00       discard  delimiter-switch
61caf16   0.9825   -2.80             -6.54       discard  int-without-biginteger
85a2298   0.9839   -3.14             -6.60       discard  combined-parse-near-misses
d599aea   0.9616   -5.86             -0.00       discard  delimiter-switch-retry
faa806c   0.9482  -13.00              9.34       discard  presize-token-buffers
```

Four kept, five discarded, one failed. The six that did not stick are the more
interesting half.

## The failure: the tests caught a real regression

Experiment 2 replaced `StringReader` with a non-synchronizing reader, and the
constructor was written defensively:

```java
this.source = source == null ? "" : source;
```

`StringReader`'s own constructor throws on a null source, and three of the
library's tests depend on that — `exceptionOnNullString`, `nullXMLException`,
`nullCookieException`. Swallowing the null turned an exception into an empty
document. `FAIL`, `tests_failed`, commit dropped.

This is the whole design in one experiment. A performance change quietly altered
behaviour at an edge nobody optimizing would think about, the library's own
frozen tests rejected it, and the fix — reproduce `StringReader`'s throw
faithfully — turned it into experiment 3, which was kept and is worth −15 %.
The gate did not slow the run down; it is the reason the run produced something
correct.

## The discards: an effect the run could not resolve

Three discards are one idea measured three times. Replacing a linear
`String.indexOf` scan over twelve delimiters with a `switch` — asked once per
character of every number and boolean in the document — gave:

| Attempt | `parseArray` | `parseObject` | machine load |
|---|---:|---:|---:|
| exp 6 | −4.89 % (p=0.063) | −7.46 % (p=0.036) | 6.05 |
| exp 8, combined with another near-miss | −1.93 % | −3.14 % | — |
| exp 9, re-run on a quiet machine | −5.54 % (p=0.023) | −2.65 % (p=0.48) | 1.79 |

The same code, three times, between −1.9 % and −7.5 %. The effect is real and
the harness never banked it, because with four benchmarks a KEEP needs
`p < 0.05/4 = 0.0125` and nothing reached it.

Experiment 10 is the sharpest version. Presizing the tokeniser's accumulators
measured **−13.0 %** and **−9.9 %**, at p = 0.0185 and p = 0.0355 — significant
at alpha, comfortably past the 1 % effect floor, and still discarded.

Watching a visible double-digit improvement get thrown away reads as the tool
being broken. It is not: at `count: 10` on a laptop, with four benchmarks
splitting the significance budget, a ~10 % effect sits right at the resolution
limit. **The answer is to raise `count`, and the harness did not say so** — the
per-benchmark line noted "significant at alpha, not at corrected alpha/4" and
left the reader to work out the rest. That was fixed as a result of this run:
a discard whose improvement lost to the correction, rather than to noise, now
says so and names the knob.

The `int-without-biginteger` discard is the other kind. Allocations fell a
measured 6.5 %, every benchmark moved the right way, and the p-values were
0.44–0.91. That one really is indistinguishable from the machine.

## What this run found in the tool itself

Pointing the harness at a real repository for the first time found two bugs that
the demo project could not have:

1. **`init` gave advice the user could not take.** JSON-java ships both a
   `pom.xml` and a `build.gradle` — normal for a library that publishes to Maven
   Central but builds with Gradle. `init` correctly refused to guess, and told the
   user to *"set `build_tool:` in `.autor3search/config.yaml`"* — a file `init`
   had just refused to create. Fixed by adding `init -build-tool maven|gradle`,
   which the message now names.

2. **`doctor` reported a healthy repository as broken.** It re-detected the build
   tool with `auto` regardless of what the config had already settled, so it
   printed `✗ build: ... cannot be inferred` on a repository that was configured
   correctly and running fine. It now reads the config first.

Neither was reachable from the bundled demo, which has one build file and is
always detected.

3. **A discard could not explain itself.** Experiment 10 above measured −13.0 %
   and −9.9 % and was thrown away. That is correct behaviour, and it looked like
   a malfunction. `eval` now says when an improvement lost to the Bonferroni
   correction rather than to noise, and names the knob that fixes it.

The first two shipped in v0.1.1, the third in the release that follows this run.

## Two more libraries

`org.json` had obvious headroom. Two libraries that do not, run the same way, to
see whether the verdicts track reality rather than the tool's enthusiasm.

### jsoup @ `6d3a579` — 2 experiments, nothing kept

Benchmarks: `parse`, `select`, `text`, `outerHtml`, from the headline API. 97
frozen sources.

| Experiment | Result |
|---|---|
| Reject ordinary characters with one comparison before binary-searching the tokeniser's delimiter set | `parse` −1.44 % (p=0.19) — DISCARD |
| Match selectors against an array instead of a `List` | `select` −1.41 % (p=0.043) — DISCARD |

Both ideas were sound and both effects were real; neither was worth 1 %. jsoup
has clearly been tuned already, and the profile's biggest item — 27 % in
`NodeTraversor.traverse` — is structural rather than a missed trick. **A tool
that reported wins here would be lying.**

### commons-codec @ `1f9eea7` — 2 experiments, nothing kept

Benchmarks: Base64 and Hex, both directions, over 64 KiB. 89 frozen sources.

The profile was the most emphatic of the three runs: `BaseNCodec.ensureBufferSize`
at **44.5 %** of Base64 decode and **31.4 %** of encode. The buffer starts at
8 KiB and doubles, so a large payload resizes repeatedly.

| Experiment | Result |
|---|---|
| Allocate the buffer once from the known length, in the shared base class | **FAIL** — `tests_failed` |
| The same, scoped to `Base64.decode` | `base64Decode` −2.07 % (p=0.0015) — DISCARD, `improvement_below_min_effect` |

The first was rejected by 49 errors in `Base58Test`. Base58 does not follow the
block model `BaseNCodec.getEncodedLength` assumes, so a change that is correct
for Base64 corrupts it — caught by the library's own tests, in a class the
benchmark never touches.

The second is the more interesting verdict, and the only time in any of these
runs that `improvement_below_min_effect` fired. `base64Decode` improved 2.07 % at
p = 0.0015 — significant even at the corrected `0.05/4` — so the change
demonstrably worked. It was still discarded, because one benchmark of four
moving 2 % is a geomean of 0.9964, and the 1 % floor is applied to the run's
overall score, not to its best number. The reason code says exactly that, which
is the difference between "your idea did nothing" and "your idea worked and is
too small to bank".

### What the three runs together say

**The sampling profiler is a lead, not evidence.** `ensureBufferSize` at 44.5 %
of a profile yielded 2.07 % when actually measured. `Arrays.copyOf` — the real
copying — was only 3.5 %, which should have been the tell. Every library here had
at least one hot spot that did not pay out, and the only way to know which was to
run the experiment.

**The verdicts tracked the headroom.** org.json had a `synchronized` reader on a
per-character path and a regex per number written, and gave up 25 %. jsoup and
commons-codec are mature and gave up nothing. The harness did not manufacture a
result for either.

## Caveats

- **Fourteen experiments across three libraries**, the largest of which is 97
  source files with a 15-second test suite. Enough to show the loop sustains
  itself, finds real wins where they exist and declines to invent them where they
  do not. Not enough to say what it does against a codebase a hundred times
  larger, where the test suite alone may cost minutes per experiment.
- **Getting JMH into an existing project is the fiddly part.** Three libraries
  needed three different edits: jsoup already had a `<configuration>` to merge
  into, commons-codec's Apache parent passes `-proc:none` in `compilerArgs` which
  silently defeats `<proc>full</proc>`, and its `apache-rat` licence check fails
  the build on the `program.md` that `init` writes. None of that is the harness's
  doing, but all of it stands between a user and their first run.
- **The configuration was too tight for the last third of the run.** Four
  benchmarks at `count: 10` could not resolve a 10 % effect. A second run at
  `count: 20` would very likely have banked two more of the discards — at twice
  the wall time per experiment.
- **I wrote the benchmarks.** Mitigated by choosing them from the public API
  before profiling, and by freezing them, but not eliminated. A library that
  ships its own would be stronger evidence.
- **One machine, one JDK.** macOS on P/E cores is the noisy end of the range,
  and `doctor` warned about it at baseline: load average 6.05 against a threshold
  of 5.00. The discards are what that warning looks like in the results.
- **`writeObject`'s win does not transfer to every workload.** It helps
  serialization of string-heavy documents. A caller that only parses sees
  nothing, which is exactly what the other three benchmarks report.
