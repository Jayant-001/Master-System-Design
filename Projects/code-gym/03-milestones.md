# 03: Milestones and Checkpoints

> **How each milestone works**
> 1. Read the **Goal** and **Done when**.
> 2. Answer the **Decide** questions in [devlog.md](devlog.md) *before* coding (2–5 lines each). Search first if you need to.
> 3. Work through the **Tasks** and tick them off.
> 4. Run the **Checkpoint** on the fixture repo ([04-validation-kit.md](04-validation-kit.md)). Every line must pass.
> 5. Do the **Self-check**. Move on only if every 🟢 is true and no 🔴 is.
> 6. Update the status board in [README.md](README.md).

---

## M-1: Prep (1–2 evenings)

**Goal:** know that the project is worth building, choose the basics, and set up the repo.

**Tasks**
- [ ] **Prior-art check (60 min).** Search GitHub, the VS Code Marketplace, and Open VSX for: "practice your own code", "rewrite function from codebase", "spaced repetition code", "code kata from repository", "mutation testing game". Write down what you find in the devlog: name, what it does, and how Code Gym is different.
- [ ] **Talk to 3 people** (teammates or friends) for 10 minutes each. Ask: "Do you feel your coding skills fading with AI? Would you do a 15-minute workout on your own code? What would make you stop using it?" Write the answers down.
- [ ] **Name check:** GitHub, the VS Code Marketplace, Open VSX, Homebrew, and the domain. Keep "Code Gym" or pick another name.
- [ ] **Amazon policy:** start the open-source / personal-project approval process before publishing anything (see [05-launch.md](05-launch.md)). You can build privately meanwhile.
- [ ] **ADR-001:** core engine language (Go vs TypeScript). Read the cgo note in [02-design.md](02-design.md) §9.
- [ ] **ADR-002:** first supported language (Go or Python recommended).
- [ ] **ADR-003:** license (MIT or Apache-2.0). Search: "MIT vs Apache 2.0 for developer tools".
- [ ] Create the repo (private for now) with the layout from 02-design §10, CI that runs tests on every push, and a linter.
- [ ] Copy this planning folder into the repo's `docs/`.

**Decide**
1. Who is your **first real user** besides you? Name them.
2. What's the one thing that would make *you* use this every day?
3. Is there an existing project you should **contribute to** instead? If yes, why are you still building your own?

**Search:** `prior art search open source`, `MIT vs Apache 2.0`, `go project layout`, `GitHub Actions Go CI`, `golangci-lint`.

**Checkpoint**
- [ ] Devlog has: the prior-art table, 3 interview notes, and ADR-001/002/003.
- [ ] Repo exists, `go test ./...` (or equivalent) runs in CI and is green on an empty project.

**Self-check**
- 🟢 You can explain in 2 sentences how Code Gym differs from what exists.
- 🔴 You skipped the prior-art check "because it's surely new".

---

## M0: Walking skeleton (1 weekend)

**Goal:** the full loop works end to end for **one language**, in the crudest way. No database, no scoring.

**Done when:** on the fixture repo you can `list` → `start` → (edit) → `check` → `diff` → `done`, and the real working tree is untouched.

**Tasks**
- [ ] CLI skeleton with commands: `list`, `start`, `check`, `diff`, `done`, `giveup`.
- [ ] **tree-sitter:** load the grammar for your first language. Write a query that finds functions and methods. Return name, receiver/class, and the byte ranges of the whole function and its body.
- [ ] `codegym list`: print `id  path:line  name  lines` for all functions.
- [ ] **Sandbox:** create a detached worktree at `HEAD` in a temp dir you control. Track it in a small state file (no DB yet).
- [ ] **Stub:** replace the body bytes with the language's stub. Make sure the file still **compiles**. (Go: handle the unused-imports problem.)
- [ ] Open the stubbed file in the editor: `$EDITOR`, or `code -g file:line` if available.
- [ ] **Runner:** run the test command inside the sandbox with a **timeout**. On timeout, kill the **whole process tree**, not just the parent.
- [ ] **JUnit parser:** read the XML, and list passed/failed tests with their failure messages.
- [ ] `codegym check`: show ✓/✗ per test plus a summary.
- [ ] `codegym diff`: show the user's version vs the original.
- [ ] `codegym done` / `giveup`: remove the worktree and clear the state.
- [ ] On every command: detect and clean **orphaned** sandboxes.

**Decide**
1. Uncommitted changes in the user's repo: practice from `HEAD` only? Warn? Refuse?
2. What exactly is the stub for your language, and how do you keep the file compiling?
3. How do you find the worktree again after the CLI exits (between `start` and `check`)?
4. Which tests run in M0: the whole package/module, or everything? (Keep it simple now. M1 improves it.)
5. How do you kill a test process **and its children** on macOS/Linux?

**Search:** `tree-sitter query syntax`, `tree-sitter go bindings`, `tree-sitter-go grammar function_declaration`, `git worktree add detach`, `git worktree list --porcelain`, `os/exec context timeout`, `kill process group setpgid`, `gotestsum junitfile`, `JUnit XML schema`, `git diff --no-index`.

**Checkpoint** (fixture repo: see 04-validation-kit, cases F1, F4, F7, F10)
- [ ] `codegym list` shows every function in the fixture, with correct line numbers.
- [ ] `codegym start F1` → the sandbox file has the stub; **`git status` in the real repo is clean**; the real file is unchanged (compare hashes).
- [ ] `codegym check` with the stub → **FAIL**, with readable failure messages.
- [ ] Paste the original body back → `codegym check` → **PASS**.
- [ ] F4 (imports used only in the body): the stubbed file **compiles**.
- [ ] F10 (a test that hangs): `check` stops at the timeout, and **no leftover processes** remain (`ps` shows none).
- [ ] Kill the CLI with Ctrl-C mid-check, run any command → the orphaned sandbox is detected and cleaned up.
- [ ] `codegym diff` shows the difference clearly.

**Self-check**
- 🟢 You can explain why a worktree is safer than copying files or using `git stash`.
- 🟢 You have a test proving that the real working tree is never modified.
- 🔴 The runner only kills the parent process.
- 🔴 Any command writes into the user's real files.

---

## M1: Good exercises (1 weekend)

**Goal:** only offer exercises that are **worth practicing** and **truly gradeable**, and remember history.

**Done when:** `codegym list` shows ranked, gradeable exercises; ungradeable ones are excluded with a reason; attempts are stored in SQLite.

**Tasks**
- [ ] `codegym init`: detect language and test command, write `.codegym.yml`, offer to add `.codegym/` to `.gitignore`.
- [ ] **SQLite store** with a migrations system (start at schema v1). Tables from 02-design §5.
- [ ] **Index:** parse all included files, save functions with a body hash, lines, and complexity.
- [ ] **Exclusions:** generated files, vendor/testdata, too small or too large, trivial getters/setters.
- [ ] **Complexity:** count branching nodes (if/for/switch/&&/||...) from the AST.
- [ ] **Test mapping v1:** per the language notes in 02-design §8 (package-level for Go, or per-test coverage for Python).
- [ ] **Baseline check:** run the mapped tests on the **original** code. They must pass, twice in a row. Otherwise mark the function `not gradeable: baseline failing/flaky`.
- [ ] **Stub check:** run the mapped tests with the **stub**. If they still **pass**, mark `not gradeable: tests don't check it`.
- [ ] **Cache:** skip stub and baseline checks when neither the body hash nor the test files changed.
- [ ] **Scorer:** combine size, complexity, recency, and coverage into a score. Make the weights configurable.
- [ ] Save every attempt: start/end time, test runs, result, the user's source.
- [ ] `codegym doctor`: checks git, the test command, orphaned sandboxes, DB version.

**Decide**
1. Stub checks are expensive (one test run per function). Run them all at `init`, lazily when a function is picked, or in the background? What's the trade-off?
2. What's your **cache key** for "still gradeable"? Which changes must invalidate it?
3. Scoring weights: how will you know they're good? (Idea: rate the top 20 by hand: good, OK, or bad.)
4. What counts as "trivial"? Write a rule you can test.
5. Global DB vs per-repo DB?

**Search:** `SQLite migrations go`, `modernc.org/sqlite`, `cyclomatic complexity`, `tree-sitter node types`, `content hash cache invalidation`, `pytest-cov cov-context test`, `go test -run regex`, `flaky test detection rerun`.

**Checkpoint** (cases F1–F9)
- [ ] F2 (uncovered function): **never offered**, reason = "tests don't check it".
- [ ] F3 (getter): **never offered**, reason = "trivial/too small".
- [ ] F5 (flaky test): **never offered**, reason = "baseline flaky".
- [ ] F6 (generated file): **never offered**.
- [ ] F8 (method on a type): shows the correct qualified name, e.g. `(*Limiter).Allow`.
- [ ] Re-run `index` without changes → **no stub checks re-run** (time it: it should be much faster).
- [ ] Change one function's body → only that function is re-checked.
- [ ] Hand-rate the top 20 exercises on a real repo of yours: at least 15 are "good" or "OK".
- [ ] After 3 attempts, the DB contains 3 attempts with correct fields. Check with the `sqlite3` CLI.

**Self-check**
- 🟢 Every excluded function has a clear reason a user would understand.
- 🟢 You can explain the stub check to a friend in one sentence.
- 🔴 Exercises are offered whose tests fail even on the original code.

---

## M2: Daily workout (1–2 weekends)

**Goal:** make it a **habit**: a short daily session, spaced repetition, hints, and visible progress.

**Done when:** you've used `codegym today` on your own repo for **7 days in a row**, and the scheduling behaves sensibly.

**Tasks**
- [ ] **Rating:** turn an attempt (pass/fail, time, hints used, test runs) into a rating: `again / hard / good / easy`.
- [ ] **SM-2** scheduler written by you: interval, ease, repetitions, lapses.
- [ ] **Fake clock** everywhere the scheduler or `today` needs the time.
- [ ] `codegym today`: due reviews first, then new exercises, within the configured minutes. Estimate the time per exercise from history.
- [ ] **Hint ladder** (no AI):
  - 1: who calls this function (call sites) and its doc comment
  - 2: names of the tests that cover it
  - 3: a control-flow skeleton (e.g. "loop → if → early return → return value")
  - 4: reveal lines one at a time
- [ ] **Fill-the-gap mode:** hide only the "key lines" (for example the most branch-heavy ones), not the whole body.
- [ ] **Rotate modes** for repeat reviews (rewrite → gap → later: bug hunt) to fight memorizing.
- [ ] `codegym stats`: streak, attempts, pass rate, average time, weakest files/modules, upcoming reviews.

**Decide**
1. How exactly do pass, time, and hints map to a rating? Write the rule as a table.
2. How do you estimate exercise time for `today` when there's no history yet?
3. How do you find call sites for hint 1 without a full language server? (A tree-sitter query? Text search? `gopls`?)
4. Which lines are "key lines" for fill-the-gap? Write a rule.
5. When is a streak "broken"? (Time zones!)

**Search:** `SM-2 algorithm SuperMemo`, `FSRS algorithm`, `open-spaced-repetition`, `fake clock testing go`, `tree-sitter call_expression query`, `gopls references`, `spaced repetition ratings again hard good easy`.

**Checkpoint**
- [ ] Scheduler unit tests with a fake clock: a known sequence of ratings → the expected intervals (write the expected table first, by hand).
- [ ] `today` on day 1, day 2, and day 8 (fake clock) shows the right mix of due and new items.
- [ ] Each hint level shows the right content on fixture F1. Level 4 never reveals more than one extra line per request.
- [ ] Fill-the-gap stub compiles, and tests fail (stub check applies to gap mode too).
- [ ] `stats` numbers match direct SQL queries on the DB.
- [ ] **Dogfood:** 7 days of real use logged in the devlog (one line per day: what you practiced, how it felt).

**Self-check**
- 🟢 After a week, you *want* to open it. If not, write down why. That's your next feature.
- 🟢 Your rating table is written down and tested.
- 🔴 The scheduler calls the real clock directly.
- 🔴 Hint 1 already gives away the solution.

---

## M3: Practice what AI wrote (3–5 evenings)

**Goal:** find **AI-written code** and let users prioritize it. This is the unique angle.

**Done when:** on a repo with mixed commits, functions are correctly marked AI / human / unknown, and `today` can prefer AI-written ones.

**Tasks**
- [ ] **Git trailers:** read commit trailers (`Co-authored-by: Claude ...` and others from config).
- [ ] **git blame:** map each function's lines to commits. Compute `ai_ratio` = share of lines from AI-trailer commits.
- [ ] Handle moved/copied code (blame options for detecting moves).
- [ ] **Manual override:** `codegym mark <id> ai|mine`.
- [ ] **Optional transcript adapter** (off by default): read local Claude Code transcripts (`~/.claude/projects/*/*.jsonl`) for Edit/Write tool calls on files and lines in this repo. Parse defensively, since the format is undocumented and may change.
- [ ] Config: `prefer_ai: true` raises the score of AI-written functions.
- [ ] Stats: "% of AI-written functions you've practiced".

**Decide**
1. Line-level vs function-level: at what `ai_ratio` is a function "AI-written"?
2. A human edited AI code later. Whose is it now?
3. Tools that leave no trace (some Copilot use): what can you honestly claim? (Manual marks + "unknown".)
4. Transcripts: what's the **minimum** data you need, and how do you avoid storing secrets?
5. What happens when the transcript format changes? (Fail softly, with a clear message.)

**Search:** `git interpret-trailers`, `git log format trailers key`, `git blame --porcelain`, `git blame -M -C`, `JSON Lines parsing`, `Claude Code hooks documentation`, `Co-Authored-By commit trailer`.

**Checkpoint** (fixture git history: case H1–H4 in 04-validation-kit)
- [ ] H1: function fully from an AI-trailer commit → `ai_ratio = 1.0`.
- [ ] H2: function fully from human commits → `0.0`.
- [ ] H3: mixed → the ratio matches your hand calculation.
- [ ] H4: function moved to another file in a later commit → it still keeps its AI attribution.
- [ ] A synthetic transcript sample (`testdata/transcripts/`, made up, no real data) → the correct files are marked.
- [ ] A corrupted or unknown transcript line → skipped with a warning, no crash.

**Self-check**
- 🟢 You can explain each signal's weakness honestly in the README.
- 🔴 Transcript contents are copied into the DB.

---

## M4: VS Code / Cursor extension (1–2 weekends)

**Goal:** practice where people actually work, with the extension as a **thin UI** over the CLI.

**Done when:** from a fresh repo in VS Code you can start, check, get hints, see a diff, and see stats, all without the terminal. It also works in Cursor (installed from Open VSX).

**Tasks**
- [ ] Scaffold with `yo code` (TypeScript).
- [ ] Find the CLI binary (setting + auto-detect). Check the CLI's JSON `schema` version on startup.
- [ ] **CodeLens** "▶ Practice this function" above gradeable functions.
- [ ] **Sidebar (TreeView):** Today's workout, due reviews, recent attempts.
- [ ] **Status bar:** running exercise + timer.
- [ ] Commands: Start, Check, Hint, Diff, Give up, Done, Stats.
- [ ] Show test results: an Output channel first; later maybe the VS Code Testing API.
- [ ] **Diff view** with the built-in diff editor.
- [ ] **Stats webview:** a streak calendar and weak areas.
- [ ] Package with `vsce` and `ovsx` (local install first, publish in M7).

**Decide**
1. How do you open the sandbox file? Open just the file from the worktree path, or the worktree folder? What does each feel like?
2. Where does logic live? (Rule: if the CLI can do it, the extension shouldn't re-implement it.)
3. How do you ship the CLI binary: bundle per platform inside the extension, or ask users to install it?
4. How does the extension react to an old or incompatible CLI?

**Search:** `VS Code extension yo code`, `CodeLensProvider`, `TreeDataProvider`, `vscode.diff command`, `Webview API`, `StatusBarItem`, `vsce package`, `ovsx publish Open VSX`, `@vscode/test-electron`, `platform-specific extensions vsce target`.

**Checkpoint**
- [ ] Demo script D2 (04-validation-kit) passes in VS Code.
- [ ] The same demo passes in **Cursor** with the `.vsix` installed.
- [ ] Extension integration tests run in CI.
- [ ] An incompatible CLI version shows a clear message, not a crash.

**Self-check**
- 🟢 Zero business logic duplicated in TypeScript.
- 🔴 The extension parses human CLI output instead of `--json`.

---

## M5: Bug Hunt (1 weekend)

**Goal:** the fun mode. A small bug is planted in real code, a test fails, and you find and fix it.

**Done when:** `codegym bug` gives a fair, solvable bug, with a difficulty level, on the fixture and on a real repo.

**Tasks**
- [ ] **Mutation operators** on the AST, at least 5:
  - relational operator swap (`<` ↔ `<=`)
  - negated condition
  - boolean operator swap (`&&` ↔ `||`)
  - off-by-one constants
  - removed statement or changed return value
- [ ] Every mutant must **compile**. Discard the ones that don't.
- [ ] Every mutant must be **caught** (at least one test fails). Discard surviving mutants.
- [ ] **Difficulty:** tell the user the function (easy), the file (medium), or only the package (hard).
- [ ] Record time-to-find. Rating feeds the scheduler as a separate exercise mode.
- [ ] The failing test's output is shown. That's the main clue.

**Decide**
1. Which operators are **fun** and which are just annoying? (Try each one on yourself.)
2. How do you avoid "equivalent mutants" (changes that don't change behavior)?
3. How many mutants do you generate and test per function, given each needs a test run?
4. Should a bug hunt ever combine 2 mutations? (Probably a stretch.)

**Search:** `mutation testing`, `mutation operators list`, `equivalent mutant problem`, `PIT mutators`, `mutmut`, `Stryker mutators`, `go-mutesting`.

**Checkpoint** (cases F1, F11)
- [ ] For F1, each operator yields a compiling mutant that the tests catch.
- [ ] F11 (an equivalent-mutant trap): the mutant survives, so it's **discarded** and never offered.
- [ ] Play 5 bug hunts on a real repo: each one is solvable, and you note how fun each felt (1–5) in the devlog.

**Self-check**
- 🟢 No bug hunt was "impossible", meaning one where the tests don't point anywhere useful.
- 🔴 Offered mutants that don't compile or don't fail any test.

---

## M6: Second language, Java (1–2 weekends)

**Goal:** prove the adapter design works, and serve your Java-heavy teammates.

**Done when:** the **same conformance suite** passes for both languages, and Java `check` takes under 30s on a mid-size Gradle or Maven project.

**Tasks**
- [ ] **Adapter conformance suite:** shared tests that every adapter must pass (list, stub compiles, stub check, mapping, JUnit parsing, mutations).
- [ ] Java fixture (`testdata/fixture-java/`) with the same F-cases translated into Java, plus Java-specific ones (overloads, constructors, nested classes).
- [ ] tree-sitter-java: methods, constructors, nested/inner classes, **overloaded methods** (identity must include parameter types).
- [ ] Stub with `throw new UnsupportedOperationException("codegym");`. Handle constructors (`super(...)` / `this(...)`).
- [ ] Test mapping v1: naming convention (`FooTest` ↔ `Foo`). v2: JaCoCo per class.
- [ ] Test command: Gradle `--tests` / Maven `-Dtest=`. Read JUnit XML from the standard folders.
- [ ] **Speed:** Gradle daemon, offline mode, only the affected module, reusing build caches in the worktree.
- [ ] Smoke test on a real open-source Java project with good tests (for example Apache Commons Lang).

**Decide**
1. Overloads: what's the function ID? How do you show it to users?
2. Constructors: include them, or skip them? Why?
3. Lombok and annotation processors: generated methods don't exist in the source. Does that matter?
4. How do you make the worktree reuse build caches so the first `check` isn't a full rebuild?

**Search:** `tree-sitter-java`, `gradle --tests filter`, `maven surefire -Dtest`, `gradle test results xml location`, `JaCoCo`, `gradle build cache`, `gradle daemon`.

**Checkpoint**
- [ ] Conformance suite: green for both languages.
- [ ] Java fixture: all F-cases behave like the first language's.
- [ ] Commons Lang (or similar): `init` works, top-20 exercises look reasonable, and `check` p50 is under 30s (measure it).

**Self-check**
- 🟢 Adding Java required **no changes to the engine**, only a new adapter. If you had to change the engine, record why.
- 🔴 Java-specific `if` statements in the engine.

---

## M7: Teams and launch (1–2 weekends)

**Goal:** make it useful for teams, and ship it publicly.

**Done when:** a stranger can install and pass their first exercise in under 5 minutes using only the README, and you've launched.

**Tasks**
- [ ] **Onboarding packs:** `.codegym/packs/<name>.yml` lists functions plus a note from the lead ("why this matters"), in order. `codegym pack list` and `codegym pack start onboarding`.
- [ ] A warning when running packs from repos you don't own.
- [ ] **Shareable stats card:** markdown or an image (streak, exercises, % AI-written practiced). No code content.
- [ ] Everything in [05-launch.md](05-launch.md): approval, license, README, demo GIF, packaging, release, launch posts.
- [ ] Feedback channel: GitHub Discussions or Issues with templates.

**Decide**
1. What goes in a pack file, and how does it survive refactors (functions renamed or moved)?
2. Opt-in team progress: do you want it at all? If yes, how without a server? (Maybe export a file that the lead collects.)
3. What's your v1.0 announcement message, in one sentence?

**Search:** `vhs terminal gif`, `goreleaser homebrew tap`, `vsce publish`, `ovsx publish`, `GitHub issue templates`, `Show HN guidelines`, `semantic versioning`.

**Checkpoint**
- [ ] **Fresh-machine test (D4):** a friend installs it from the README alone, and passes an exercise in under 5 minutes. Time it, and watch silently.
- [ ] A pack of 5 functions works end to end on a real repo.
- [ ] The release pipeline produces CLI binaries for macOS (arm64/x64) and Linux, plus a `.vsix`.
- [ ] Launch posts are published. Feedback is collected in the devlog.

**Self-check**
- 🟢 You fixed at least 3 things you saw the friend struggle with.
- 🔴 The README needs you standing next to the user.

---

## v1.0: The finish line

- [ ] M0–M7 checkpoints all passed.
- [ ] **3 real users** (not you) used it for **2 weeks**. Their feedback is in the devlog.
- [ ] 0 reports of modifying a user's working tree.
- [ ] Fewer than 10% of offered exercises reported as broken or ungradeable.
- [ ] A blog post: what you built, what you learned, and your own stats from dogfooding.

## After v1.0 (ideas parking lot; move them into the devlog when you start one)
- Optional AI review ("which edge cases did your version handle differently?"), bring-your-own key or local model.
- Python/TypeScript adapters (whichever you didn't do).
- FSRS scheduler, compared with SM-2 on your own data.
- Neovim / JetBrains frontends using the same JSON contract.
- "Explain mode": write in words what the function does before rewriting it.
- Weekly email-free summary as a local HTML report.
