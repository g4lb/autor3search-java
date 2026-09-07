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

This was a directed session of three experiments, not an unattended overnight
run. Treat it as evidence the loop works end to end on real code, not as a claim
about what a full night produces.

## Result

One optimization was found, verified against the library's own 59 frozen test
files, and kept:

| Benchmark | Change | p |
|---|---:|---:|
| `writeObject` | **−19.6 %** | < 0.001 |
| `parseArray` | −0.6 % | 0.315 (not significant) |
| `parseObject` | −0.5 % | 0.436 (not significant) |
| `xmlToJson` | −0.3 % | 1.000 (not significant) |

Percentages are the harness's own output — the change in the median of ten
interleaved rounds per side. Absolute `ns/op` figures are deliberately not
quoted: they are meaningful only against this machine, this JDK and this input,
whereas the ratio is what the verdict is actually made from.

**Cumulative: −5.6 %** across the declared set, geomean. No benchmark regressed.

### What the change was

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
12afd5c   0.9436  -19.62            -0.00        keep     quote-batch-runs
80ce452   0.9789   -3.33            -5.51        discard  int-without-biginteger
d5c2385   0.9908   -1.78            -0.00        discard  delimiter-switch
```

Two of the three experiments were discarded, and both discards are more
interesting than the win.

## The discard that was right, and looked wrong

`stringToNumber` allocates a `BigInteger` for **every integer in the document**,
purely to decide whether the value fits in an `int` or a `long`. The library's
own comment concedes the cost:

```java
// BigInteger down conversion: We use a similar bitLength compare as
// BigInteger#intValueExact uses. Increases GC, but objects hold
// only what they need.
```

Narrowing short values with `Long.parseLong` first — 18 digits always fit, so the
returned types are provably unchanged — produced this:

```
JsonBenchmark.parseArray    -2.4%  [p=0.247 n=10]  (not significant)
  B/op                      -6.5%  (140177 -> 131105)
JsonBenchmark.parseObject   -3.3%  [p=0.143 n=10]  (not significant)
  B/op                      -5.5%  (140945 -> 133185)
JsonBenchmark.xmlToJson     -1.8%  [p=0.123 n=10]  (not significant)

SCORE  0.979  (-2.1%)
VERDICT: DISCARD
```

Every benchmark moved the right way. Allocations fell by a measured 6.5 %. The
score of 0.979 cleared the 1 % minimum-effect floor. And it was still discarded,
because **not one benchmark cleared the Bonferroni-corrected significance
threshold** of `0.05/4 = 0.0125`.

That is the harness working, not failing. Four benchmarks tested at an
uncorrected `alpha` would give roughly an 18 % chance that one looks significant
when nothing changed; the correction is what stops a run banking that. A −2.1 %
geomean built entirely from p-values between 0.12 and 0.25 is exactly the shape
of a result that might be real and might be the machine.

What it should tell an agent is not "the idea was wrong" — the allocation
reduction is real and measured — but "this needs more evidence than the run is
configured to collect." Raising `count` is the response; banking it is not.

## The discard that was simply too small

`nextSimpleValue` asks, for **every character of every number, boolean and
null**, whether that character terminates the token — by calling
`",:]}/\\\"[{;=#".indexOf(c)`, a linear scan of twelve characters. Replacing it
with a `switch` is textbook.

```
SCORE  0.991  (-0.9%)   min effect: 1.0%
VERDICT: DISCARD
```

Directionally right on three of four benchmarks and worth about 0.9 % — which
lands just the wrong side of the 1 % floor. This is the floor doing its job: in
an unattended loop, a sub-1 % change is not worth a commit, and the run should
spend the night on bigger ideas.

Both discards are also the advancing measurement baseline earning its keep. Each
was measured against the *kept* `quote` commit, not against where the run
started, so neither could coast on the −19.6 % already banked.

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
always detected. Both are in the release that follows this run.

## Caveats

- **Three experiments, not a night.** A directed session proves the loop runs on
  real code. It says nothing about what thirty unattended experiments do.
- **I wrote the benchmarks.** Mitigated by choosing them from the public API
  before profiling, and by freezing them, but not eliminated. A library that
  ships its own would be stronger evidence.
- **One machine, one JDK, one afternoon.** macOS on P/E cores is the noisy end of
  the range; `doctor` warns about it, and the two discards are what that noise
  looks like when the statistics refuse to over-read it.
- **`writeObject`'s win does not transfer to every workload.** It helps
  serialization of string-heavy documents. A caller that only parses sees
  nothing, which is exactly what the other three benchmarks report.
