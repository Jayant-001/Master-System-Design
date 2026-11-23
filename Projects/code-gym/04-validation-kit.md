# 04: Validation Kit

> This is your **referee**. "It works on my repo" is not proof. These fixtures, scripts, and checklists are. Build the fixture repo in **M0** and extend it at each milestone.

---

## 1. The fixture repo (`testdata/fixture-<lang>/`)

A tiny project made only of **tricky cases**. Each case is a function plus its tests, with a known expected behavior. Build it in your first language, and later translate it for each new language (M6).

> Tip: make the fixture a **real git repo** created by a script (`testdata/make-fixture.sh`), so tests can rebuild it from scratch, including its commit history for M3.

### Function cases (F)

| ID | Case | What it looks like | Expected Code Gym behavior | Used in |
|---|---|---|---|---|
| **F1** | The good exercise | 15–30 lines, a loop + conditions, 3–5 tests that really check it | Offered; stub fails tests; original passes | M0+ |
| **F2** | Not covered | A function with no test touching it (or tests that ignore its result) | **Excluded**: "tests don't check it" | M1 |
| **F3** | Trivial | A getter or one-line wrapper | **Excluded**: "too small/trivial" | M1 |
| **F4** | Compile trap | Its body is the only user of an import / helper (Go: unused import) | Stub **still compiles** | M0 |
| **F5** | Flaky test | A test that fails ~30% of the time (random or time-based) | **Excluded**: "baseline flaky" | M1 |
| **F6** | Generated code | A file with the generated-code header | **Excluded** | M1 |
| **F7** | Slow test | A test that takes ~20s | Runs, and the time is measured; it doesn't block other cases | M0 |
| **F8** | Method | A method on a type/class (receiver) | Correct qualified name; works like F1 | M1 |
| **F9** | Nested function | A function containing a closure / lambda / inner function | The **outer** function is the exercise; the inner one isn't listed separately (or decide and document) | M1 |
| **F10** | Hanging test | A test that never ends (blocks forever) | Killed at the timeout; **no leftover processes** | M0 |
| **F11** | Equivalent-mutant trap | Code where a mutation doesn't change behavior (e.g. `i < len` vs `i != len` in a simple loop) | The bug-hunt mutant is **discarded** | M5 |
| **F12** | Tests in another package/module | The function's tests live elsewhere (e.g. an integration test folder) | Mapping finds them, or the function is excluded with a clear reason | M1 |
| **F13** | Changed function | Commit 1 has version A; commit 2 changes the body | Index notices the hash change; the cache is invalidated only for this function | M1 |

Java-only cases (M6): **J1** overloaded methods · **J2** constructor with `super(...)` · **J3** inner class method · **J4** method in an interface with a default body.

### Git history cases (H), for M3

| ID | Case | How to create it | Expected |
|---|---|---|---|
| **H1** | Fully AI-written | Add the function in a commit with `Co-authored-by: Claude <noreply@anthropic.com>` | `ai_ratio = 1.0` |
| **H2** | Fully human-written | A commit without any AI trailer | `0.0` |
| **H3** | Mixed | AI commit adds 10 lines; human commit edits 4 of them | Ratio matches your hand calculation |
| **H4** | Moved code | An AI-written function moved to another file by a human commit | Still counted as AI-written (move detection) |
| **H5** | Manual override | `codegym mark <id> mine` on H1 | Treated as human from then on |

### Sample transcripts (`testdata/transcripts/`), for M3
- Write **synthetic** transcript files by hand, in the format you observe. Never copy real transcripts (they may contain secrets).
- Include: a normal Edit, a Write of a new file, an edit to a file outside the repo (must be ignored), a corrupted line, and an unknown event type.

---

## 2. Adapter conformance suite (M6, but design it in M0)

One test suite that runs against **every** language adapter, using that language's fixture.

| # | Test | Passes when |
|---|---|---|
| C1 | `listFunctions` finds all F-cases | Names, line numbers, and qualified names are correct |
| C2 | `isGenerated` | F6 → true; all others → false |
| C3 | `stub` compiles | For every listed function, the stubbed project builds |
| C4 | Stub check | F1 → gradeable; F2 → not gradeable |
| C5 | Baseline check | F5 → flaky; F1 → stable |
| C6 | Test mapping | F1's tests found; F12 handled as designed |
| C7 | Test command + JUnit | Running F1's tests produces parseable JUnit with the expected test names |
| C8 | Timeout | F10 killed within timeout + 2s; no child processes left |
| C9 | Key lines (gap mode) | F1's gap stub compiles and fails tests |
| C10 | Mutations | F1: each operator yields a compiling, caught mutant; F11 discarded |

---

## 3. Demo scripts (run them by hand at checkpoints)

### D1: CLI happy path (M0, M1, M2)
```
cd testdata/fixture-go && git status            # clean
codegym init
codegym list                                    # F1, F4, F7, F8, F9 offered; F2, F3, F5, F6 not
codegym start <F1>                              # editor opens the sandbox file with the stub
codegym check                                   # FAIL with readable messages
# paste the original body back
codegym check                                   # PASS
codegym diff                                    # shows no logical difference
codegym done
git status                                      # still clean; no worktrees left (git worktree list)
```

### D2: Extension happy path (M4)
```
Open the fixture in VS Code → CodeLens "▶ Practice" appears above F1 (not above F2/F3)
Click it → sandbox file opens, status bar timer starts
Run "Code Gym: Check" → results shown
Run "Code Gym: Hint" twice → hint 1, then hint 2
Run "Code Gym: Diff" → built-in diff view opens
Run "Code Gym: Done" → timer stops, sidebar updates, stats view shows the attempt
Repeat in Cursor with the .vsix
```

### D3: Bug hunt (M5)
```
codegym bug --difficulty easy     # tells you the function; a test fails
# find and fix
codegym check                     # PASS; time-to-find recorded
codegym bug --difficulty hard     # only the package is given
```

### D4: Fresh-machine test (M7)
- A friend who has never seen the project gets **only the README link**.
- You watch silently and time them: install → `init` → first passed exercise.
- Write down every moment they hesitate. Each one is a README or UX fix.
- Target: **under 5 minutes**.

---

## 4. Safety tests (run in CI from M0 onward)

| # | Test | Passes when |
|---|---|---|
| S1 | Working tree untouched | After every command in D1, the real repo's file hashes and `git status` are unchanged |
| S2 | No commits, no branches | `git log` and `git branch` are unchanged after a full session |
| S3 | Crash recovery | Kill the CLI at random points 20 times. The next command cleans up, leaving no orphan worktrees |
| S4 | Uncommitted changes | The user has uncommitted edits → behavior matches your M0 decision, and the edits are still there afterwards |
| S5 | No network | With the network off, every non-AI feature works (proves local-first) |

---

## 5. Dogfooding protocol (from M2 onward)

- Use `codegym today` on **one of your own repos** at least **4 days a week**.
- Each day, write one line in the devlog: date · exercises · pass/fail · minutes · how it felt (1–5) · one annoyance.
- Every Sunday, read the week's lines and pick **the single biggest annoyance** to fix next.
- After 4 weeks, compare with your week 1 stats: time per exercise, hints used, pass rate.

---

## 6. Numbers to track (in the devlog's Metrics table)

| Metric | How to measure | Target |
|---|---|---|
| `check` latency p50 / p95 | Timer in the CLI (log it) | < 10s Go/Python, < 30s Java |
| `start` latency | Same | < 2s |
| Gradeable rate | Gradeable ÷ candidates after M1 filters | Know it; there's no fixed target |
| Bad-exercise rate | Your hand ratings + user reports | < 10% |
| Daily use (you) | Devlog | ≥ 4 days/week |
| First-exercise time (new user) | D4 | < 5 min |
| Real users active after 2 weeks | Ask them | 3 |

---

## 7. Checkpoint scoreboard (tick as you go)

| Check | M0 | M1 | M2 | M3 | M4 | M5 | M6 | M7 |
|---|---|---|---|---|---|---|---|---|
| Fixture cases for this milestone | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| Safety tests S1–S5 | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| Demo script | D1 ⬜ | D1 ⬜ | D1 ⬜ | — | D2 ⬜ | D3 ⬜ | D1-Java ⬜ | D4 ⬜ |
| Conformance suite | — | — | — | — | — | — | ⬜ | ⬜ |
| Self-check all 🟢, no 🔴 | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
