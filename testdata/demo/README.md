# demo

A tiny Maven project with a deliberately slow `WordCount.count`, one JUnit
test class and one JMH benchmark. The harness's end-to-end tests point at a
copy of this tree; `WordCount.count` is the thing an agent would optimize, and
the test and benchmark classes are what `baseline` freezes.
