# Code Gym: Project Home

> **A gym for your coding skills.** Code Gym picks real functions from **your own repo** (especially ones an AI wrote for you), hides their body, and you rewrite them. Your project's **existing tests** grade the result. Spaced repetition brings back the ones you found hard.

This folder is the full plan. You can stop for weeks and pick up again from **"Where I left off"** below.

---

## Why this exists (the context)

- We now write a lot of code with AI (Claude Code, Cursor, Copilot). It's fast, but our own skills fade. We read code more than we write it, and we forget how to build things from scratch.
- LeetCode and katas train on **generic** puzzles. Nothing trains you on **the code you actually work with**.
- Your repo already has everything needed for practice: real functions with real tests. Code Gym turns that into short daily workouts.
- It's a real, useful, shareable open-source tool, and building it teaches parsing, git internals, test tooling, mutation testing, spaced repetition, CLI design, and VS Code extensions.

---

## How to use these docs

| File | What's inside | Read when |
|---|---|---|
| [01-product.md](01-product.md) | Problem, users, features, user stories, non-goals, success metrics | Before starting; when deciding what to build next |
| [02-design.md](02-design.md) | Architecture, components, data model, tech stack, per-language notes, testing strategy | Before each milestone |
| [03-milestones.md](03-milestones.md) | **M0–M7**, each with tasks, decisions, search terms, a **checkpoint**, and a self-check | While building. This is the main file. |
| [04-validation-kit.md](04-validation-kit.md) | The **fixture repo** (your referee), conformance tests, demo scripts, dogfooding plan | To prove each milestone works |
| [05-launch.md](05-launch.md) | Prior-art check, naming, Amazon approval, license, packaging, launch, feedback | Before going public (M7) |
| [devlog.md](devlog.md) | Your progress log, decision records, idea parking lot | Every session (write 3 lines at the end) |

**Rules for yourself** (same spirit as the learning projects):
1. **You write the core logic.** AI is fine for boilerplate (CI config, extension scaffolding, README polish) and for reviewing your code.
2. **Every checkpoint must pass on the fixture repo** before you call a milestone done.
3. **Write one line in the devlog every session.** Future-you will thank you.
4. **Dogfood:** use Code Gym on your own repos from M2 onward.

---

## Status board

Legend: ⬜ not started · 🟨 in progress · ✅ done · ⏸️ paused

| Milestone | Goal | Status | Started | Done | Checkpoint passed? |
|---|---|---|---|---|---|
| **M-1** Prep | Prior-art check, name, language choice, repo setup | ⬜ | | | ⬜ |
| **M0** Walking skeleton | List → blank in sandbox → rewrite → check → diff (one language) | ⬜ | | | ⬜ |
| **M1** Good exercises | Index, stub check, baseline check, scoring, history | ⬜ | | | ⬜ |
| **M2** Daily workout | `today`, spaced repetition, hints, stats, fill-the-gap mode | ⬜ | | | ⬜ |
| **M3** Practice what AI wrote | Authorship detection (git trailers, blame, transcripts) | ⬜ | | | ⬜ |
| **M4** VS Code extension | CodeLens, sidebar, timer, diff, stats view | ⬜ | | | ⬜ |
| **M5** Bug Hunt | Mutation-based "find the bug" mode | ⬜ | | | ⬜ |
| **M6** Second language | Java adapter (Gradle/Maven) + adapter conformance tests | ⬜ | | | ⬜ |
| **M7** Teams + launch | Onboarding packs, packaging, docs, public launch | ⬜ | | | ⬜ |
| **v1.0** | All of the above + 3 real users for 2 weeks | ⬜ | | | ⬜ |

---

## Where I left off

> Update this every time you stop. Keep it to 3 lines.

- **Last session:** _(date)_
- **I was doing:** _(milestone + task)_
- **Next step:** _(the very next concrete action)_

---

## The core loop (keep this picture in mind)

```
pick a function ──▶ hide its body (in a sandbox copy) ──▶ you rewrite it in your editor
       ▲                                                          │
       │                                                          ▼
schedule next review ◀── record attempt (time, hints, result) ◀── run the tests that cover it
```

## What "done" looks like (v1.0)

```
$ codegym init            # detect language and test command, build the index
$ codegym today           # 3 exercises: 2 reviews due, 1 new (AI-written last week)
$ codegym start 2         # opens parseRetryAfter() with an empty body in your editor
$ codegym check           # ✗ 2/5 tests failed: TestParseRetryAfter/http_date ...
$ codegym hint            # hint 1/4: called by backoff.go:88, handles 2 header formats
$ codegym check           # ✓ 5/5 in 6m12s, 1 hint → next review in 3 days
$ codegym diff            # your version vs the original
$ codegym bug             # bug hunt: a test is failing somewhere in ratelimit/, find it
$ codegym stats           # streak 12 days · 41 exercises · weakest: internal/retry
```

Plus a VS Code / Cursor extension that does the same things with buttons.
