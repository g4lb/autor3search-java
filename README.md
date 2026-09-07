# autor3search-java

[![ci](https://github.com/g4lb/autor3search-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/g4lb/autor3search-java/actions/workflows/ci.yml?query=branch%3Amain)
[![release](https://img.shields.io/github/v/release/g4lb/autor3search-java?label=release)](https://github.com/g4lb/autor3search-java/releases/latest)

**Autonomous AI-driven performance optimization for any Java repository.**

Point your coding agent at your repo and go to sleep. It proposes an optimization,
runs it through a frozen measurement harness, and the harness decides: **KEEP** or
**DISCARD**. You wake up to a log of experiments and faster code.

Inspired by [karpathy/autoresearch](https://github.com/karpathy/autoresearch), which
does this for a single-GPU LLM training loop, and a direct sibling of
[autor3search-go](https://github.com/g4lb/autor3search-go), which does it for Go.
This does it for the JVM — where the metric comes from JMH, where the machine has
to be warmed up before it can be measured at all, and where **correctness is not
optional**.

> **Status: early.** The harness is complete and tested end to end — a full
> journey through `init`, `baseline`, a real optimization, a restored test and a
> stop runs in CI against real Maven and real Gradle builds on every supported
> JDK. What it has *not* yet had is a long unattended night against a large
> third-party library. Every number below is a real measurement from this
> machine, never an illustration; there are just fewer of them than there will
> be.

---

## Start here

Open your coding agent inside the Java repository you want to make faster, and
paste this:

```text
Install and run autor3search-java on this repository, then optimize it.

Setup:
1. Download the latest release jar and its launcher:
     mkdir -p ~/.local/bin
     curl -sSL -o ~/.local/bin/autor3search-java.jar \
       https://github.com/g4lb/autor3search-java/releases/latest/download/autor3search-java.jar
     curl -sSL -o ~/.local/bin/autor3search-java \
       https://github.com/g4lb/autor3search-java/releases/latest/download/autor3search-java
     chmod +x ~/.local/bin/autor3search-java
   Make sure ~/.local/bin is on PATH.
2. autor3search-java init
   Show me the benchmarks it discovered. If it reports none, STOP and tell me:
   this tool can only optimize what it can measure.
3. git add -A && git commit -m "autor3search-java init"
4. autor3search-java doctor
   Show me any warnings. If the machine looks unfit to measure, stop and ask me
   before continuing.
5. autor3search-java baseline -tag <today, e.g. sep7>

Then:
6. Read program.md in this repository, in full. It is your instruction set for
   the rest of this run. Follow it exactly.

Rules for the whole run:
- Never edit program.md, .autor3search/config.yaml, results.tsv, or anything
  the harness writes. They are not yours.
- Never pass -force to any autor3search-java command. (I may run
  `autor3search-java stop -force` myself; that one is mine, not yours.)
- One idea per experiment. Commit before each eval.
- KEEP means the commit stays. Anything else means git reset --hard HEAD~1.
- Print one context line before each experiment, so I can see where you are:
  [exp <n> | <branch> | vs <measure_commit> | stop: autor3search-java stop]

Run the loop until I stop you. I stop you by running `autor3search-java stop` in
my own terminal — you will see it as "stop_requested": true in a verdict.
When you do: apply that verdict, do not start another experiment, run
`autor3search-java report`, summarize what you tried, and exit the loop.
```

That's the whole handoff. The agent installs the tool, sets the run up, and then
follows `program.md` — which the harness generated for your repository and which
tells it how to run the keep-or-discard loop.

What you get back: one commit per accepted change on a branch named
`autor3search-java/<tag>`, and a `results.tsv` recording every experiment that
was tried, including the ones that failed. `autor3search-java report` summarizes
it.

Three things worth knowing before you start it:

- **It needs JMH benchmarks.** The tool optimizes what it can measure, and
  refuses to guess. See [Repos with no benchmarks](#repos-with-no-benchmarks).
- **Numbers are only as good as the machine.** Run `doctor` and read it. A
  thermally throttled laptop on battery produces noise dressed as data.
- **A JVM run is slower than a Go one, and that is not a bug.** Every measured
  round pays a JVM start and a warmup before it measures anything, because a
  cold JVM reports interpreter timings rather than the speed of your code. Budget
  wall time accordingly; see [Run time](#run-time).

## The idea

You do not edit Java files to tune performance. You edit `program.md` — the
instructions that drive your agent. The agent edits the Java. A compiled harness
holds the metric, and the agent cannot reach it.

| Piece | What it is | Who edits it |
|---|---|---|
| `autor3search-java` | the harness jar: gates, measures, scores | nobody — it's compiled |
| your test and `@Benchmark` sources | frozen at baseline, restored before every run | nobody — restored automatically |
| your `pom.xml` / `build.gradle` | rejected outright if touched | nobody, mid-run |
| your Java source | whatever is in `scope` | **the agent** |
| `program.md` | the agent's instructions | **you** |
| frozen sources, baseline worktree, baseline record | lives outside your repo, under the user cache (or `AUTOR3SEARCH_JAVA_STATE_HOME`) | nobody — the agent could not reach it even by editing every file in scope |

That last row matters: the agent edits the repository, so anything the score
depends on that *lived* there would be silently writable by the very agent it is
meant to constrain. The only harness output that stays inside your repo is
`results.tsv` (a human-readable log, not part of the metric) and `run.log`
(subprocess transcripts) — both gitignored by `init`.

## Requirements

- **JDK 17 or newer** on `PATH`, and a full JDK rather than a JRE — the harness
  runs on it and your build compiles with it. `doctor` checks both.
- **Maven or Gradle.** Detected from `pom.xml` or `build.gradle(.kts)`; set
  `build_tool` in the config if a repository has both.
- **JMH declared by the module that holds your benchmarks**, both artifacts:

  ```xml
  <dependency>
    <groupId>org.openjdk.jmh</groupId><artifactId>jmh-core</artifactId>
    <version>1.37</version><scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.openjdk.jmh</groupId><artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version><scope>test</scope>
  </dependency>
  ```

  The generator is not optional and its absence is the single most common way a
  first run fails: without it the `@Benchmark` methods compile, but the classes
  JMH actually executes are never generated. On **JDK 23 and later** you also have
  to ask javac to run it, because a processor found only on the classpath is no
  longer run implicitly:

  ```xml
  <!-- maven-compiler-plugin -->
  <configuration><proc>full</proc></configuration>
  ```
  ```groovy
  // Gradle
  tasks.withType(JavaCompile).configureEach { options.compilerArgs << '-proc:full' }
  ```

  `eval` recognises both failures and tells you which one you have rather than
  passing the JVM's error through.

## Quick start

```bash
# from a release
curl -sSL -o ~/.local/bin/autor3search-java.jar \
  https://github.com/g4lb/autor3search-java/releases/latest/download/autor3search-java.jar
curl -sSL -o ~/.local/bin/autor3search-java \
  https://github.com/g4lb/autor3search-java/releases/latest/download/autor3search-java
chmod +x ~/.local/bin/autor3search-java

# or from source
git clone https://github.com/g4lb/autor3search-java && cd autor3search-java
mvn -DskipTests package        # target/autor3search-java.jar, plus bin/autor3search-java

cd your-java-project
autor3search-java init                                 # find benchmarks, write config + program.md
git add -A && git commit -m "autor3search-java init"   # baseline refuses a dirty tree
autor3search-java doctor                               # is this machine fit to measure?
autor3search-java baseline -tag sep7                   # freeze sources, pin the baseline commit
```

That commit matters — `init` only writes files, it does not commit them, and
`baseline` refuses to run against an uncommitted tree because a baseline pinned
against what's on disk (not what's in git) would not be reproducible. `init`
writes three things: `.gitignore` entries, `.autor3search/config.yaml`, and
`program.md`. `.autor3search/config.yaml` is the one file under `.autor3search/`
that gets committed — it's the run configuration, and humans own it; everything
else the harness later writes under `.autor3search/` (`profile`'s output, say) is
gitignored.

Then start your agent in the repo:

```
Read program.md and start the optimization loop.
```

It runs until you stop it. Each experiment is one commit, one verdict, one row in
`results.tsv`.

## Watching a run, and stopping it

The agent's loop calls `eval --json`, which by contract prints one JSON object
and nothing else — so there is no human-readable stream to watch. Ask the run
where it is instead, from any terminal, any branch, at any time:

```
$ autor3search-java status
run tag        sep7
branch         autor3search-java/sep7  (checked out)
baseline       a3f1c2d  (run started here)
measuring vs   9b7e410  (advanced past the baseline by earlier KEEPs)
build          maven  (module .)
worktree       ~/Library/Caches/autor3search-java/1a2b3c4d/sep7/baseline-worktree
experiments    4 run  (1 keep, 2 discard, 1 fail, 0 crash)  — next is #5
eval           running (pid 48213) — an experiment is being measured
stop           not requested

to stop after the current experiment:  autor3search-java stop
to stop now, abandoning it:            autor3search-java stop -force
```

`status` never writes anything: checking on a run cannot change it.

There are three ways to stop, and they differ in what happens to the experiment
currently in flight.

**`autor3search-java stop` — graceful.** Writes a request the agent reads at its
next verdict. The experiment under way finishes and is scored, its KEEP or
DISCARD is applied, and only then does the loop exit with a summary. Nothing is
thrown away. This is the one to use. `autor3search-java stop -clear` cancels it
if you change your mind before the agent notices.

**`autor3search-java stop -force` — immediate.** For when you cannot wait out a
long benchmark. It writes the same request, then asks the running `eval` to
abandon its experiment. `eval` cancels its own measurement, tears down the forked
benchmark JVMs, prints `"status": "ABORTED"` and exits 2. The experiment is lost
(no `results.tsv` row is written, because nothing was measured); every kept commit
before it is untouched. It then tells you what state the repository is in,
including the commit the agent had made for the abandoned experiment and how to
drop it. It does not drop anything for you. If `eval` does not let go within the
grace period, it is killed outright along with everything it started.

Worth knowing why that is a *file* the running `eval` polls rather than a signal:
a JVM terminated by a signal cannot choose its own exit status — the operating
system fixes it at 130 or 143 — so a signalled `eval` could never exit with the
code the agent's loop branches on, nor print the verdict that tells the agent to
drop its commit. Polling a sentinel means force-stopping behaves identically on
every platform.

**Ctrl+C.** Interrupting the agent works too. `eval` handles the signal rather
than dying under it, which matters more than it sounds: JMH runs the benchmark in
a **forked** JVM, so an `eval` killed without a chance to clean up would leave
that JVM running — burning CPU and corrupting every later measurement on the
machine. The exit code is the shell's 130 rather than 2, so read `"status"`
rather than the exit code when you see anything outside 0-3.

Whichever you use, the work is on the run branch `autor3search-java/<tag>` and
`autor3search-java report` summarizes it. Resuming later needs nothing special:
clear any pending stop and point the agent back at `program.md`.

## Commands

| Command | What it does |
|---|---|
| `init` | Scans the repo, discovers `@Benchmark` methods by reading the source, detects the build tool, and writes `.autor3search/config.yaml` + `program.md`. Refuses to overwrite an existing config without `-force`. |
| `doctor` | Checks whether this machine can measure reliably (JDK, git, build tool, CPU count, load average, CPU governor, disk) and prints its findings. Informational — always exits 0. |
| `baseline -tag <tag>` | Creates the run branch `autor3search-java/<tag>`, freezes every test and `@Benchmark` source, and pins a detached worktree at the baseline commit. Refuses a dirty tree, a reused tag, and a config naming a benchmark that does not exist. |
| `profile` | Runs the declared benchmarks under JMH's sampling stack profiler and its GC profiler and prints where the time and the allocations actually go — real data rather than an agent guessing from reading source. Keeps the full transcript under `.autor3search/profiles/`. |
| `eval` | Runs one experiment: gates (scope, config integrity, restore, compile, test, worktree integrity), measures the candidate against the pinned baseline worktree, scores it, appends a `results.tsv` row, exits `0`/`1`/`2`/`3` for KEEP/DISCARD/FAIL/CRASH, and on `KEEP` re-points the pinned worktree at the candidate's commit so the next `eval` measures against it (see [Scoring](#scoring)). |
| `status` | Prints where a run is: run branch and whether it is checked out, the frozen baseline commit and the advancing measurement commit, the build tool and module, the pinned worktree, how many experiments have run and with what verdicts, whether an `eval` is in flight, and whether a stop is pending. Read-only. Accepts `-tag <tag>` so it works from any branch. |
| `stop` | Asks the agent to end the run after the experiment it is running: writes a request `eval` reports back as `stop_requested`. `-clear` cancels a pending request; `-force` additionally asks the running `eval` to abandon the current experiment and reports what state that leaves the repository in. Accepts `-tag <tag>`. |
| `report` | Summarizes `results.tsv`: counts by status, the cumulative speedup as the product of every kept experiment's score, and the largest individual wins. |
| `version` | Prints which build of the harness is running, and the JVM under it. A `results.tsv` row is only as reproducible as the binary that produced it. |

Every command accepts `-C <dir>` to run against a repository other than the
current directory, rather than changing the process's working directory — safer
under concurrent invocations, and testable without changing global state.

### Where run state lives

Everything the metric depends on — the frozen golden copies, the baseline record,
the pinned worktree — is kept **out of the repository**, under
`<user cache>/autor3search-java/<repo hash>/<tag>/`. That is deliberate: the agent
edits the repository, so in-tree state would be state the agent could rewrite to
make itself look good.

Set `AUTOR3SEARCH_JAVA_STATE_HOME` to an absolute path to put it somewhere else —
useful in a container or CI runner with no durable cache. Run state is keyed
underneath it the same way, one directory per repository and one per tag. A
relative value is refused: it would resolve against whatever directory each
command happened to run from, so `eval` from a submodule and `stop` from the
repository root would address different state for the same run.

## Worked example

A word counter. Ordinary Java, with ordinary tests. It ships in this repository
under [`testdata/demo`](testdata/demo) and is what the end-to-end tests run
against.

**`WordCount.java`** — the code the agent is allowed to change:

```java
public static Map<String, Integer> count(String s) {
    Map<String, Integer> counts = new HashMap<>();
    for (String field : s.split("\\s+")) {
        String word = "";
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                word = word + c; // quadratic: rebuilds the string every character
            }
        }
        if (!word.isEmpty()) {
            counts.merge(word, 1, Integer::sum);
        }
    }
    return counts;
}
```

**`WordCountTest.java`** and **`WordCountBenchmark.java`** — the correctness gate
and the metric. **The agent cannot change either.** They are hashed at `baseline`
and restored before every single evaluation:

```java
class WordCountTest {
    @Test
    void countsRepeatedWords() {
        assertEquals(Map.of("the", 2, "quick", 1, "brown", 1),
                WordCount.count("the quick brown the"));
    }

    @Test
    void stripsPunctuationAndCase() {
        assertEquals(Map.of("hello", 2, "world", 1),
                WordCount.count("Hello, WORLD! hello?"));
    }
}

@State(Scope.Benchmark)
public class WordCountBenchmark {
    private static final String INPUT = "The Quick, Brown Fox! jumps over 2 lazy dogs. ".repeat(200);

    @Benchmark
    public void count(Blackhole bh) {
        Map<String, Integer> counts = WordCount.count(INPUT);
        if (counts.isEmpty()) {
            throw new IllegalStateException("empty result");
        }
        bh.consume(counts);
    }
}
```

**The agent's change** — a `StringBuilder`, a presized map, and a scan that never
allocates an intermediate `String[]`:

```java
public static Map<String, Integer> count(String s) {
    Map<String, Integer> counts = new HashMap<>(64);
    StringBuilder word = new StringBuilder(32);
    int n = s.length();
    int i = 0;
    while (i < n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        word.setLength(0);
        while (i < n && !Character.isWhitespace(s.charAt(i))) {
            char c = s.charAt(i++);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                word.append(c);
            }
        }
        if (word.length() > 0) {
            counts.merge(word.toString(), 1, Integer::sum);
        }
    }
    return counts;
}
```

**The result.** Both versions run the identical frozen test and benchmark classes,
and both pass. Measured with the default configuration — 10 interleaved rounds per
side, one fork, five warmup and five measurement iterations of 1s each — on an
Apple M-series laptop, OpenJDK 26:

| metric | before | after | change | p |
|---|---:|---:|---:|---:|
| `ns/op` | 76,680 | 36,003 | **−53.1 %** | 0.0000108 |
| `B/op` | 454,065 | 97,568 | **−78.5 %** | 0.0000108 |

Score is `0.4695`, and no warnings: at 10 rounds per side the median's interval
is bounded and a KEEP was reachable. That `p` is not a rounding artefact — it is
`2/C(20,10)`, the smallest two-sided p-value the exact rank test can produce at
this sample size, which is what perfect separation between the two sides looks
like.

Score is the geometric mean of the per-benchmark time ratios. Below
`1 - min_effect_pct/100` and significant past the corrected threshold, with no
benchmark regressing — so the harness returns:

```
VERDICT: KEEP
```

The commit stays, the branch advances, and the measurement baseline advances with
it — the *next* experiment is measured against this commit, not against the
original one. Had the change been slower, broken a test, or been
indistinguishable from noise, the harness would have returned `DISCARD` or `FAIL`
and the agent would `git reset --hard`.

## What the harness enforces

An agent optimizing your code can "win" by cheating. Each route is closed:

| Cheat | Why it fails |
|---|---|
| Weaken or delete a test | test and benchmark sources are hashed at baseline and **restored** before every run — edits are erased, not argued about |
| Delete the work the benchmark measures | the frozen tests still run and still assert the real behavior |
| Add an easier benchmark | any test or `@Benchmark` source absent from the frozen manifest is rejected — including one parked in `src/main/java` |
| Replace a frozen source with a symlink to a file outside the repository | freezing refuses to snapshot a symlinked source, and restoring refuses to write through one that appears later — including a symlink on any *directory* along the path, which redirects a write just as effectively |
| Rewrite a golden copy in the harness's own store | every restore checks the store against the SHA-256 the manifest recorded at baseline, so tampering has to alter both consistently rather than just the store |
| Edit files outside the agreed area | `scope` violations fail before anything is even built |
| Bank measurement noise as a win | a Mann-Whitney test must clear `p < 0.05`; noise is `DISCARD` |
| Speed up A by wrecking B | any significant regression over 5 % rejects the change outright |
| Swap a dependency, or change a compiler flag | `pom.xml`, `build.gradle(.kts)`, `settings.gradle(.kts)`, `gradle.properties`, `libs.versions.toml` and the wrapper properties are rejected regardless of `scope` — a build change is a human decision, and it would change *what* is measured rather than how fast it runs |
| Loosen the rules mid-run (raise `max_regress_pct`, narrow `scope`, drop a benchmark) | `.autor3search/config.yaml` is hashed at baseline; any change to it fails the run with a config-hash mismatch |
| Compare against a stale baseline | the measurement baseline is **re-measured every run**, interleaved with the candidate |
| Make the *baseline* slow instead | the pinned worktree's HEAD is checked against the recorded measurement commit before every measurement |
| Coast to `KEEP` on an earlier improvement doing nothing new | the measurement baseline **advances to the newly kept commit after every `KEEP`** (see [Scoring](#scoring)), so a later no-op is compared against what was just kept, not against where the run started |

That last one matters more than it looks. Comparing a candidate measured now
against a baseline measured an hour ago on a cooler CPU attributes thermal drift
to your code change. Alternating both sides in one session cancels it.

## Scoring

One number, so nothing can be cherry-picked:

```
score = geomean(new_ns / base_ns)   across the declared benchmark set
```

`KEEP` requires **all** of:

1. **A minimum real improvement.** `score` must be below `1 - min_effect_pct/100`
   (default `min_effect_pct: 1.0`, i.e. score < 0.99), not merely below 1. A
   change that is technically significant but trivially small is not worth a
   commit in an unattended overnight loop.
2. **A Bonferroni-corrected significant improvement.** At least one benchmark's
   p-value must clear `alpha / k`, where `k` is the number of benchmarks compared
   in that experiment — not the raw `alpha` (0.05). Testing `k` benchmarks against
   the same uncorrected `alpha` inflates the chance that at least one shows a
   spurious "significant" result purely by chance (with 4 benchmarks, about an
   18 % chance); dividing `alpha` by `k` is the standard correction for that.
3. **No significant regression beyond `max_regress_pct`** (default 5 %). This
   guard deliberately uses the raw, **uncorrected** `alpha`, not the
   Bonferroni-corrected one from rule 2 — on purpose, and it looks inconsistent
   until you see why: the correction in rule 2 only ever makes it *harder* to call
   a result significant, and applying it to the regression guard would make real
   regressions *easier* to miss. We want the opposite bias for harm: conservative
   about accepting a win, liberal about catching damage.

A delta's reported significance (`p < alpha`, no correction) is always the raw,
honest statistic — that's what a human or agent should see when reading a report.
The Bonferroni correction in rule 2 is a KEEP-decision threshold layered on top,
not a redefinition of "significant"; `eval`'s output calls out a benchmark that is
significant at `alpha` but did not clear the corrected bar, rather than silently
calling it "not significant."

A discard distinguishes the two ways rule 1 and rule 2 can fail.
`no_significant_improvement` means nothing measurably moved.
`improvement_below_min_effect` means the change really did speed things up, by
less than `min_effect_pct` — worth knowing, because it says the idea was
directionally right rather than inert.

### The statistics, and why they are these ones

- **A rank test, not a t-test.** Benchmark timings are not normal: they are
  bounded below by the work the code actually does and have a long tail of
  interference from everything else on the machine. The comparison is a two-sided
  Mann-Whitney U test, which assumes nothing about the shape.
- **Exact, not approximated, at these sample sizes.** With no ties and both
  samples under 21 observations the exact null distribution is enumerated rather
  than approximated by a normal. At 10 rounds per side the approximation is
  noticeably wrong in the tail, which is the only part a significance threshold
  ever looks at.
- **The median, not the mean.** One interfering round adds an arbitrarily large
  outlier on the high side and none on the low side. The mean follows it; the
  median does not.

### When the measurement cannot carry the verdict

`eval` prints `WARNING:` lines above its verdict (and a `warnings` array in
`--json`) when the statistics behind a result do not support reading it at face
value. They never change the decision. Two matter:

- **Too few rounds for a confidence interval.** At 95 % confidence an
  order-statistic interval on the median needs at least 6 observations per side;
  below that it is unbounded, and any interval printed would be invented
  precision.
- **No `KEEP` was reachable.** The Mann-Whitney U test has a floor on the p-value
  it can produce for a given sample size — with `n` rounds per side the smallest
  attainable two-sided p is `2/C(2n,n)`, however far apart the two samples are.
  Rule 2 divides `alpha` by the number of benchmarks, so enough benchmarks push
  the corrected threshold below that floor and *every* experiment discards no
  matter what the agent does. `count: 5` with 7 benchmarks is already there
  (`0.05/7 = 0.0071` against a floor of `0.0079`). The config validator's
  `count >= 4` floor cannot catch this: it does not know how many benchmarks a run
  will compare. The warning names the count to raise to.

`B/op` — JMH's normalised allocation rate, from its GC profiler — is measured and
shown to the agent as a hint, but never scored. Scoring it would let a change that
allocates less without being any faster pass as a KEEP.

`base_ns` is **not** fixed for the whole run. `baseline` pins two things that are
kept deliberately separate: a FROZEN commit that the frozen sources and the scope
gate always compare against (so an agent cannot expand what it may edit by banking
experiments), and a MEASUREMENT commit — what `base_ns` is actually measured
against — that starts equal to the frozen one and **advances to the candidate's
own commit after every `KEEP`**. So `score` always answers "did *this* experiment
help, compared to the last thing that was kept," never "is the tree better than
when the run started." Without this, once one real improvement was kept, every
later experiment — however useless — kept comparing against that same stale
starting point and a no-op could coast to `KEEP` on an earlier win it did not
contribute to.

One consequence: each kept `score` is only that experiment's own incremental
contribution, so `autor3search-java report`'s cumulative speedup is the **product**
of every kept score, not the latest one alone — successive real improvements
compound the way percentage changes do.

## Run time

A JVM measurement costs more than a Go one, and it is worth knowing where the time
goes before you tune the knobs down.

One measured **round** is one JMH invocation: a fresh JVM, then
`warmup_iterations + measurement_iterations` iterations of `benchtime` each, per
benchmark. One **experiment** is `2 x (count + 1)` rounds — both sides, plus a
discarded warmup round — on top of one compile and one test run of your project.

At the defaults (`count: 10`, `forks: 1`, 5 warmup and 5 measurement iterations of
`1s`) that is 22 rounds of about 11 seconds per benchmark, so **roughly four
minutes per benchmark per experiment**, plus your build. An overnight run is
therefore tens of experiments, not hundreds.

The knobs, in the order worth reaching for:

- **`count`** trades noise resistance for time, linearly. It is the honest knob.
  Below 6 you lose the confidence interval; below 4 the tool refuses.
- **`benchtime`** shortens each iteration. Below about `200ms` you are mostly
  paying JVM startup rather than measuring, and the numbers get noisier.
- **`warmup_iterations`** is the one to leave alone. It is what separates
  measuring your code from measuring the interpreter.
- **`jvm_args`** costs nothing and usually helps: pinning the heap
  (`-Xmx2g -Xms2g`) removes heap growth from the variance, and it applies
  identically to both sides.

## Limitations

Stated plainly, because performance tools that oversell are worse than useless:

- **A `KEEP` is evidence, not proof.** Any fixed significance threshold admits
  some false positives by construction — that's what "alpha" means. The
  minimum-effect floor and the Bonferroni correction described in
  [Scoring](#scoring) make the rule substantially stricter, but they reduce the
  false-`KEEP` rate; they do not (and cannot) eliminate it. Treat a single `KEEP`
  as evidence worth banking, not as proof the change works. `min_effect_pct` is
  the knob for this: raise it on a noisy machine, since it costs you only wins
  smaller than the noise you cannot measure anyway.
- **No long unattended run against a large third-party library yet.** Everything
  here is exercised end to end, on every supported JDK, against a small demo
  project. That validates the mechanism; it does not yet tell you what a night
  against a hundred-thousand-line codebase looks like. If you run one, the
  results — good or bad — are worth an issue.
- **Windows is untested.** Nothing in the harness is deliberately POSIX-only —
  the run claim is a `FileChannel` lock, process trees come from
  `ProcessHandle`, and both work on Windows — but CI does not run there, so it is
  unsupported rather than known-broken.
- **Laptops are noisy.** macOS P/E core scheduling makes numbers jump.
  Interleaving and `count` mitigate it and `doctor` warns you, but a quiet Linux
  box gives cleaner results.
- **One module per run.** A classpath belongs to one Maven module or Gradle
  project. If benchmarks are found in more than one, `init` refuses and tells you
  to narrow `benchmarks:` rather than merging two classpaths into a fiction that
  matches neither module as it actually builds.
- **A small measurement asymmetry remains.** Each round measures one side a moment
  before the other, and the sides alternate; on an even `count` that cancels
  exactly, on an odd one a single round's worth of offset remains.
- **Microbenchmarks are not your application.** A 50 % win on a hot method may be
  invisible end to end. Benchmark what actually matters.
- **`count` below 4 can never reach significance.** The Mann-Whitney test behind
  `p` has a best-case two-sided p-value of 0.1 at 3 measured rounds per side —
  above the 0.05 threshold no matter how large or how clean the real improvement
  is. Every experiment would be discarded on a technicality, not on its merits.
  The config refuses `count` below 4 rather than let that happen silently.

### Repos with no benchmarks

`autor3search-java init` discovers benchmarks by scanning the repository's Java
sources for methods annotated `@Benchmark`. If it finds none, it refuses to write
`.autor3search/config.yaml` and exits with an error, rather than generating a
config with an empty `benchmarks:` list that would silently optimize nothing.

That refusal is deliberate: the tool has no other notion of "faster." The verdict
— `KEEP`, `DISCARD`, `FAIL`, `CRASH` — is entirely a function of the declared
benchmarks' timings across a baseline and a candidate. No benchmarks means no
signal to gate on, at which point every candidate would either be rejected for no
reason or accepted for no reason.

To use `autor3search-java` on a repository like this:

1. Add JMH to the module you want to optimize (see [Requirements](#requirements)),
   and write at least one benchmark covering the code you actually want made
   faster:

   ```java
   @State(Scope.Benchmark)
   public class ThingBenchmark {
       @Benchmark
       public void thing(Blackhole bh) {
           bh.consume(Thing.doWork());
       }
   }
   ```

   Consume the result with a `Blackhole` or return it. A benchmark whose result is
   discarded can be optimized away entirely by the JIT, and you will measure an
   empty loop with great precision.

2. Benchmark the right thing. A benchmark that exercises a cold path, a trivial
   helper, or a method nobody calls under load produces numbers that are entirely
   real and entirely useless — confident percentages attached to work that was
   never the bottleneck. Benchmark the method, loop, or request path that actually
   dominates the workload you care about, ideally informed by a profile of the real
   program rather than a guess.
3. Re-run `autor3search-java init` once the benchmark exists. It will pick it up
   and proceed normally.

## License

MIT © 2026 Gal Be
