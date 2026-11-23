# 01: Product

## The problem (in plain words)

1. **AI writes more of our code every month.** We accept, tweak, and move on. Our hands-on skill (writing logic from a blank page, handling edge cases, remembering APIs) slowly fades.
2. **Practice tools train on the wrong code.** LeetCode, Exercism, and katas are generic. They don't teach you *your* codebase, *your* team's patterns, or the code you're actually responsible for.
3. **New joiners learn a codebase by reading.** Reading isn't the same as understanding. Rewriting key functions yourself is much stronger, but nobody sets that up.
4. **Your repo already contains a perfect practice set:** real functions plus real tests that can grade any rewrite. Nobody uses it that way.

## The idea

A tool that turns your own repo into short, graded workouts:
- It picks a function that is **worth practicing** and **gradeable** (its tests actually check it).
- It hides the body in a **safe sandbox copy** of your repo.
- You rewrite it. Your **existing tests** grade it.
- **Spaced repetition** decides when you see it again.
- Bonus modes keep it fun: **fill the gap** (hide only a few lines) and **bug hunt** (find a planted bug using the failing test).

## Who it's for (personas)

| Persona | Situation | What they want | Why Code Gym |
|---|---|---|---|
| **AI-heavy developer** (you) | Uses Claude Code / Cursor daily | Keep their skills sharp without leaving their codebase | Practice on the code AI wrote for them |
| **New team member** | Just joined, big unfamiliar codebase | Learn the important parts fast | An **onboarding pack** of the 20 functions that matter |
| **Team lead / mentor** | Onboards people, wants shared understanding | A repeatable onboarding path | Curates packs, sees progress (opt-in) |
| **Interview preparer** | Wants hands-on practice that feels real | Real-world coding, not puzzles | Real functions, real tests, real edge cases |
| **Student / OSS learner** | Wants to understand a popular library | Learn by doing | Run Code Gym on any open-source repo |

## User stories

**MVP (v0.x)**
- As a developer, I can run `codegym init` in my repo, and it figures out the language and test command (or asks me).
- As a developer, I can list practice-worthy functions and start one.
- As a developer, when I start an exercise, **my real working tree is never touched**.
- As a developer, I can check my rewrite and see which tests failed and why.
- As a developer, I can see a diff of my version against the original when I'm done or give up.
- As a developer, my attempts are saved, so I can see progress over time.

**v1**
- As a developer, `codegym today` gives me a 15–20 minute workout: reviews that are due plus new exercises.
- As a developer, I get **hints in steps** instead of the full answer.
- As a developer, I can choose to prioritize **code that AI wrote for me**.
- As a developer, I can play **bug hunt**: find and fix a planted bug.
- As a developer, I can do all of this inside **VS Code or Cursor**.
- As a team lead, I can define an **onboarding pack** in the repo, and new joiners can work through it.

**Later**
- Optional AI feedback: "What edge cases did my version handle differently?"
- More languages.
- Shareable stats card (streak, exercises, % of AI-written code practiced).

## Features and priorities

| Feature | Priority | Milestone |
|---|---|---|
| List functions (one language) | Must | M0 |
| Sandbox via git worktree | Must | M0 |
| Blank body with a compiling stub | Must | M0 |
| Run relevant tests + parse results | Must | M0 |
| Diff your version vs the original | Must | M0 |
| Index + cache | Must | M1 |
| **Stub check** (is it gradeable?) | Must | M1 |
| **Baseline check** (do tests pass on the original? flaky?) | Must | M1 |
| Exercise scoring and exclusions | Must | M1 |
| Attempt history (SQLite) | Must | M1 |
| Spaced repetition scheduling | Must | M2 |
| `today` workout | Must | M2 |
| Hint ladder | Should | M2 |
| Fill-the-gap mode | Should | M2 |
| Stats | Should | M2 |
| AI authorship detection | Should (the unique angle) | M3 |
| VS Code extension | Should | M4 |
| Bug Hunt mode | Should (the fun factor) | M5 |
| Java adapter | Should | M6 |
| Onboarding packs | Could | M7 |
| Optional AI feedback | Could | after v1 |
| Shareable stats card | Could | M7 |

## Non-goals (things it will NOT do)

- ❌ Not a LeetCode clone. No generic puzzles, only your code.
- ❌ Not a test generator. If a function has no meaningful tests, it's skipped, not graded by guesswork.
- ❌ No cloud, accounts, or telemetry by default. Everything stays on the machine.
- ❌ It doesn't judge **style**. Tests decide pass/fail. Style feedback is only through the optional AI review.
- ❌ It never modifies the user's working tree or makes commits.

## Principles (use these to decide when you're unsure)

1. **Local-first and private.** Code never leaves the machine unless the user turns on an AI feature.
2. **Never touch the user's working tree.** Always use a sandbox.
3. **Honest grading.** Only offer exercises that the tests can actually grade.
4. **A fast loop.** Target: `codegym check` in **under 10 seconds** for Go/Python on a normal repo. A slow loop kills the habit.
5. **CLI-first.** The engine is a CLI with JSON output. Editors are thin UIs on top.
6. **Fun over feature count.** If a feature doesn't make practice more engaging or more useful, it waits.

## Success metrics

| Level | Metric | Target |
|---|---|---|
| Personal | I use it at least 4 days/week for 4 weeks | ✅ / ❌ |
| Personal | I can rewrite functions I struggled with in week 1 faster by week 4 (from stats) | trend ↓ |
| Product | 3 friends or teammates use it for 2 weeks without me nagging | 3 people |
| Product | A new user goes from install to first passed exercise in < 5 minutes using only the README | < 5 min |
| Quality | 0 incidents of modifying a user's working tree, ever | 0 |
| Quality | Fewer than 10% of offered exercises turn out "ungradeable" or broken | < 10% |

## Risks and open questions

| Risk / question | Why it matters | Where to address |
|---|---|---|
| Slow test suites (big Java builds) make the loop painful | Users quit | M1 (test mapping), M6 (Java speed) |
| You remember the code instead of understanding it | Practice becomes memorizing | M2 (rotate modes), M5 (bug hunt) |
| Detecting AI-written code is unreliable | The unique angle weakens | M3 (multiple signals + manual mark) |
| Agent transcript formats are undocumented and may change | The parser breaks | M3 (defensive parsing, optional feature) |
| Running tests runs arbitrary code | Security, especially with shared packs | 02-design (security), M7 |
| Something like this already exists | Wasted effort, or a chance to contribute instead | M-1 (prior-art check) |
| Amazon policy for personal open-source projects | Must be approved before publishing | M-1 and 05-launch |
