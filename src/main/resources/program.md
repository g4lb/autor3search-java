# program.md

This file is your instructions, coding agent. Read it fully before doing
anything. It is the only thing a human edits to steer this run — everything
else (the metric, the gates, the verdict) is a compiled harness you cannot
change and should not try to.

## Setup

Before starting the loop, do this once:

1. Agree a run tag with the human if one was not already given to you (a
   short slug like `sep7` — today's date or similar is fine).
2. Confirm `autor3search-java baseline -tag <tag>` has already been run for
   this tag. If it has not, stop and ask the human to run it, or run it
   yourself if you have been told you may. `baseline` creates the run branch,
   freezes the current test and benchmark sources as the golden copies, and
   records the commit this run measures against. Everything downstream
   depends on this step having happened exactly once.
3. Read the repository. Skim the packages named in `scope` in
   `.autor3search/config.yaml`. Run `autor3search-java profile` once so you
   know what you are starting from before changing anything.

## Experimentation

What you MAY do:

- Edit any Java source file that falls under one of the `scope` patterns in
  `.autor3search/config.yaml`.
- Add new files inside `scope`, as long as they are not test or benchmark
  sources.
- Run any read-only diagnostic command (`autor3search-java profile`, your
  build tool's own compile) as often as you like between experiments.

What you MUST NOT do:

- Edit anything under `src/test/java` or `src/jmh/java`, or any file
  declaring a `@Benchmark`. Every one of those is restored from the frozen
  baseline copy before every `eval`, so an edit there is silently discarded —
  worse, it wastes an experiment slot for nothing. If a test looks wrong, say
  so in your `-desc` for that experiment and move on; do not try to route
  around it.
- Add a new test or benchmark source file. The frozen set is fixed at
  baseline, and a file that is not in it fails the run with reason
  `new_test_file` — including a benchmark you added to reason about a hot
  path. Profile instead: `autor3search-java profile` answers the same
  question without changing what is measured.
- Edit `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`,
  `gradle.properties`, `libs.versions.toml` or the Gradle wrapper properties,
  ever. These are rejected outright regardless of what `scope` says. Changing
  a dependency is a supply-chain decision a human makes, not something an
  unattended loop decides, and a swapped dependency or a changed compiler
  flag can change *what* is measured, not just how fast it runs.
- Edit `.autor3search/config.yaml`. It is not covered by the scope gate —
  the gate only sees ordinary source files, and this file is gitignored so it
  is invisible to it either way. Instead, its hash is recorded at `baseline`
  time; if it has changed by `eval` time, the run fails with reason
  `config_changed`, not `scope_violation`.
- Edit an ordinary source file outside `scope`. This *is* what the scope gate
  rejects, failing the experiment before it is even measured.
- Try to weaken, disable, or reinterpret the verdict. `eval`'s exit code and
  `--json` output are the only truth. If a result looks wrong, say so in your
  `-desc`; do not try to make the harness agree with you by other means.
- Batch multiple unrelated changes into one experiment. One idea per
  experiment keeps every result attributable and every discard cheap.

## Output format

`autor3search-java eval` is the only command whose result decides anything.
It exits with one of four codes, and the loop below branches on exactly these:

| Exit code | Meaning | Verdict status |
|---|---|---|
| `0` | KEEP  — the change is a real, safe improvement | `KEEP` |
| `1` | DISCARD — no significant improvement, or it lost the coin flip against noise | `DISCARD` |
| `2` | FAIL — a gate rejected the change: scope violation, a new or edited frozen file, or a test failure | `FAIL` |
| `3` | CRASH — the compile failed outright or a phase timed out | `CRASH` |

Any status the harness cannot classify is reported as exit code `2` (FAIL)
rather than a silent success — treat an unrecognized `--json` status the same
way you would treat FAIL. `ABORTED` (see "Stopping") also arrives as exit
code `2` for exactly that reason: it is not a verdict, and treating it like
FAIL is the right thing to do with it.

**A `KEEP` requires a real, not just a technically significant, improvement.**
`eval` only returns `KEEP` when `score` clears `1 - min_effect_pct/100`
(default 1%, i.e. score < 0.99) AND at least one benchmark improved past a
Bonferroni-corrected significance bar. A change that shaves off a fraction of
a percent will be `DISCARD`ed by design, even if the numbers technically moved
in the right direction — do not spend a night chasing sub-1% wins, the harness
will not bank them. And even a `KEEP` is evidence, not proof: any significance
threshold admits some false positives, so treat one `KEEP` as a good sign
worth building on, not as a guarantee that the change actually helped.

With `--json`, `eval` prints one JSON object to stdout with (at least) these
fields:

```json
{
  "status": "KEEP",
  "reason": "improved",
  "score": 0.9123,
  "message": "score 0.9123 (-8.77%)",
  "regressions": [],
  "warnings": [],
  "stop_requested": false,
  "run": {
    "tag": "sep7",
    "branch": "autor3search-java/sep7",
    "baseline_commit": "a3f1c2d",
    "measure_commit": "9b7e410",
    "worktree": "/Users/you/Library/Caches/autor3search-java/1a2b3c4d/sep7/baseline-worktree",
    "experiment": 5
  }
}
```

`status` is one of `KEEP`, `DISCARD`, `FAIL`, `CRASH` — or `ABORTED`, which is
not a verdict at all but an interrupted experiment; see "Stopping" below.
`reason` is a stable, machine-readable code (e.g. `improved`,
`no_significant_improvement`, `improvement_below_min_effect`,
`guard_regression`, `scope_violation`, `new_test_file`, `build_failed`,
`tests_failed`, `timeout`, `stop_forced`).

Two of those discard reasons mean genuinely different things, and the
difference should change what you do next. `no_significant_improvement` means
nothing measurably moved — the idea did not work, drop it.
`improvement_below_min_effect` means it DID work and the harness measured a
real speedup, just a smaller one than `min_effect_pct` will bank. That is a
signal the direction is right: a variation on the same idea with a larger
effect may well clear the bar, and the same idea applied to a hotter path
almost certainly would. Do not read it as failure.

`score` is the geometric mean of `new_ns / base_ns` across the declared
benchmarks — below 1 is faster. `message` is a human-readable one-liner
suitable for a log row. `regressions`, when present, lists which benchmarks
tripped the regression guard and by how much.

`warnings`, when present, says the measurement is too weak to carry the
verdict printed beside it — the same lines appear as `WARNING:` in the human
output, just above the `VERDICT:` line. They never change the decision; they
tell you not to over-read it. Two you may see:

- **too few rounds for a confidence interval** — the numbers are still the
  measured medians, but the interval around them is unbounded. Raise `count`.
- **no KEEP was reachable** — the significance threshold is corrected for the
  number of benchmarks compared (`alpha/k`), and with the configured `count`
  the test cannot produce a p-value that small however large the improvement
  is. Every experiment will `DISCARD` until `count` is raised, so treat this
  as a broken configuration and stop rather than continuing to burn the night
  on experiments that cannot be banked.

**What `base_ns` means changes as the run progresses.** It is NOT always the
commit `baseline` recorded — it is whatever the harness's measurement baseline
currently points to, and a KEEP moves that pointer to the commit you just kept
(see the loop below). So `score` always answers "did THIS experiment help,
compared to the last thing that was kept" — never "is the tree better than
when the run started." Two consequences: (1) after a KEEP, running `eval`
again with nothing new committed measures your last commit against itself and
correctly `DISCARD`s, it is not a bug or a fluke; (2) a long, honest run's
total progress is not any single `score` — it is the product of every kept
`score`, which is what `autor3search-java report`'s "cumulative speedup"
computes for the human.

`run` is the context for the experiment just recorded: which run tag and
branch you are on, the frozen `baseline_commit` the run started from, the
advancing `measure_commit` this experiment was scored against, the pinned
baseline worktree, and `experiment`, the 1-based number of the row just
written to `results.tsv`. None of it changes what you do — it is there so you
can tell the human where the run is, since `--json` is the only channel they
can see through you.

`stop_requested` is the one field that does change what you do. See "Stopping"
below.

## Stopping

The loop does not end on its own. It ends in one of two ways, and only one of
them is yours to act on.

**A graceful stop.** The human runs `autor3search-java stop`. That does not
touch you or the experiment you are running; it writes a request that `eval`
reports back to you as `"stop_requested": true`, alongside a verdict that is
still fully valid. When you see it:

1. Apply this verdict exactly as you would have anyway — KEEP leaves the
   commit, anything else is `git reset --hard HEAD~1`. A stop must never leave
   a commit on the branch that nothing decided on.
2. Do NOT start another experiment.
3. Run `autor3search-java report` and summarize, in a few lines: what you
   tried, what was kept, and what you would try next if the run resumed.
4. Exit the loop and say you have stopped because the human asked.

`stop_requested` never changes the exit code, so read it as a separate
question from the verdict — it says whether to continue, not what this
experiment was worth.

**An interrupt.** The human presses Ctrl+C, or runs `autor3search-java stop
-force` because they could not wait for a long benchmark to finish. Either one
cancels `eval` mid-experiment. You will see `"status": "ABORTED"` with
`"reason": "stop_forced"` and no `results.tsv` row — nothing was measured, so
nothing was recorded. Treat the commit the way you would treat any FAIL
(`git reset --hard HEAD~1`), then stop as above.

The exit code differs between the two, and only one of them is a number you
have seen before. `stop -force` lets `eval` cancel itself, so it exits `2`
like any other FAIL. A signal — Ctrl+C, or a `kill` — terminates the JVM
before it can choose its own status, and the exit code is then `130` or `143`
by the operating system's convention, not by ours. Read the `"status"` field
rather than the exit code when you see anything outside `0`-`3`, and treat it
as FAIL if you cannot.

If the human wants the run to continue after all, they clear the request with
`autor3search-java stop -clear`. That is their decision, not something to wait
for or ask about.

## Logging

`results.tsv` is HARNESS-OWNED. `eval` appends exactly one row to it,
automatically, on every invocation, KEEP or not. You must never create, append
to, or edit `results.tsv` yourself — any manual write is either redundant (the
row already exists) or corrupts a file the harness parses strictly, which
fails the human's morning `autor3search-java report`, or a future
`autor3search-java baseline -force`, on the whole file, not just your line.

The columns the human sees are:

```
commit	score	best_bench_delta	bytes_delta	status	description
```

`commit`, `score`, `best_bench_delta`, `bytes_delta` and `status` are filled
in by the harness from the verdict — you do not control them. The one column
that is yours is `description`, and you set it by passing `-desc` to `eval`:

```
autor3search-java eval --json -desc "presize the HashMap"
```

Always pass `-desc`, on every invocation, KEEP or not — it is the only record
of what you were trying. Keep it terse — a short slug, e.g. `presize-map` —
and never dishonest: describe what you actually tried, including for a
DISCARD, FAIL, or CRASH. This file is the human's morning read. A long trail
of honest discards is more useful than a short trail that hides them.

## JVM optimization idea bank

Measure first, then reach for these. `autor3search-java profile` gives you
JMH's sampling stack profiler and its GC profiler, which between them answer
"where is the time" and "what is allocating" without you having to guess.

- **Allocation on the hot path.** `B/op` in the eval output is the fastest
  lead there is. Escape analysis frees you from some of it, but not from a
  boxed `Integer`, a growing `ArrayList`, an iterator on a hot loop, or a
  lambda that captures.
- **Presize collections.** `new HashMap<>(expected)` and
  `new ArrayList<>(expected)`. Resizing a map rehashes every entry; doing it
  four times during one call is invisible in source and obvious in a profile.
- **`StringBuilder` instead of `+` in a loop.** Repeated concatenation is
  quadratic. Presize it too, with `new StringBuilder(n)`.
- **Byte vs char vs code point.** `String.charAt` is cheap; `codePointAt` and
  a `Stream` over characters are not. Only work at the width the data
  actually needs.
- **Avoid megamorphic call sites.** A call site that sees three or more
  implementations cannot be inlined the way one seeing one or two can.
  Sometimes specialising a class removes the polymorphism entirely.
- **Streams in the hottest loop.** They are usually fine and occasionally are
  not. A plain indexed `for` over an array has no lambda, no boxing and no
  iterator; measure before assuming either way.
- **Arrays over collections for small fixed sets.** A linear scan over a small
  array beats a `HashMap` at sizes smaller than most people expect. Measure at
  your real size.
- **Reuse buffers.** A caller-supplied `byte[]` scratch buffer, or a
  `ThreadLocal` one, removes an allocation per call — but be certain about the
  lifetime, because a shared buffer is a correctness bug waiting to happen.
- **Bounds checks and array covariance.** Hoisting a length into a local, and
  using the most specific array type, both help the JIT.
- **Algorithmic change.** Usually the biggest win by a wide margin. Ask
  whether the work is needed at all before micro-optimizing it.

## The experiment loop

`eval` already splits its own output for you: the verdict — one compact JSON
object — is written to stdout, and the full, noisy compile/test/benchmark
transcript is written automatically to `run.log` in the repository root.
`run.log` is the harness's own file, opened by `eval` itself before it does
anything else — never redirect `eval`'s stdout into it yourself
(`eval --json > run.log 2>&1` or similar). Doing so opens a second,
independent handle on a path the harness already has open; whichever one
writes second overwrites the other from byte 0, destroying exactly the
diagnostic transcript you need when a step FAILs or CRASHes and you want to
see why. Run `eval --json` bare and read the verdict straight from its stdout;
open `run.log` only to read it, never to write to it.

LOOP FOREVER:

0. Print one context line, so the human watching knows where the run is
   without having to read the JSON:
   `[exp <n> | <branch> | vs <measure_commit> | stop: autor3search-java stop]`
   Take the numbers from the previous experiment's `run` object; on the first
   pass, from `autor3search-java status`.
1. Check git state: confirm you are on the run branch.
2. If you have no strong hypothesis, run `autor3search-java profile` and read
   the hot spots.
3. Change ONE thing in the in-scope Java source. One idea per experiment.
4. `git add -A && git commit -m "<idea>"`
5. `autor3search-java eval --json -desc "<idea>"` (no redirect — do NOT tee or
   send stdout to `run.log`; either floods your context or clobbers the
   transcript, see above). `-desc` is what lands in `results.tsv`'s
   `description` column for this experiment — always pass it.
6. Read the verdict directly from stdout: it is one compact JSON object, so
   parse or `grep '"status"'` it straight from the command's own output.
7. KEEP  -> leave the commit in place, the branch advances, AND the harness's
   measurement baseline advances to this commit too — your next experiment is
   measured against what you just kept, not against where the run started.
   Anything else -> `git reset --hard HEAD~1`
8. If the verdict carried `"stop_requested": true`, or its status was
   `ABORTED`, the human has asked you to stop. Do not start another
   experiment — follow "Stopping" above and leave the loop.
9. Go to 0.

**NEVER STOP ON YOUR OWN**: Do not pause to ask whether to continue. The human
may be asleep. You are autonomous. If you run out of ideas, re-read the
profile output, re-read the idea bank, combine previous near-misses, or try a
more radical change.

Only the human ends this loop, and only in the two ways "Stopping" describes:
a stop request (`"stop_requested": true`) or an interrupt (`"status":
"ABORTED"`). Running out of ideas is not one of them, and neither is a long
string of DISCARDs.
