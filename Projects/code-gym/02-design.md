# 02: Design

> This is a **starting design**, not a final one. When you change something, record it in [devlog.md](devlog.md) under Decisions with one line: *"Changed X to Y because Z."*

---

## 1. Big picture

```
 VS Code / Cursor extension (TypeScript, thin UI)        Terminal users (CLI)
                 │   calls CLI with --json                    │
                 └──────────────────────┬─────────────────────┘
                                        ▼
                             ┌──────────────────────┐
                             │   Core engine (CLI)   │
                             └──────────┬───────────┘
   ┌───────────────┬──────────────┬─────┴───────┬──────────────┬───────────────┐
   ▼               ▼              ▼             ▼              ▼               ▼
 Language       Sandbox        Test runner   Index & cache   Scheduler       Store
 adapters       manager        + result      + scorer        (SM-2→FSRS)     (SQLite)
 (tree-sitter)  (git worktree) parser                                          ▲
   ▲                           (JUnit XML)                                     │
   │                                                                           │
 Authorship detector (git trailers, git blame, agent transcripts) ─────────────┘
 Hint generator · Mutator (bug hunt) · Pack loader (teams)
```

**Key idea:** the **engine** knows nothing about any specific language. Everything language-specific lives behind the **language adapter** interface. Adding a language means writing one adapter and passing the conformance tests.

---

## 2. Components (what each one is responsible for)

| Component | Responsibility | Milestone |
|---|---|---|
| **CLI** | Commands, flags, human-readable output, `--json` output | M0 |
| **Language adapter** | Find functions, make stubs, map tests, build test commands (and mutations in M5) | M0 |
| **Sandbox manager** | Create and clean up git worktrees; never touch the real working tree; recover orphaned sandboxes | M0 |
| **Test runner** | Run a command with a timeout, kill the whole process tree on timeout, capture output | M0 |
| **Result parser** | Read JUnit XML (one parser for all languages) → passed/failed tests + messages | M0 |
| **Index & cache** | Store functions, hashes, test mappings, gradeable flags; only recompute what changed | M1 |
| **Scorer** | Rank functions by how good an exercise they are | M1 |
| **Store** | SQLite: functions, exercises, attempts, schedule; schema migrations | M1 |
| **Scheduler** | Turn attempts into ratings and next due dates; build the `today` list | M2 |
| **Hint generator** | Hint levels from AST, tests, and callers (no AI needed) | M2 |
| **Authorship detector** | Mark functions as AI-written / human-written / unknown | M3 |
| **Mutator** | Create bug-hunt mutants that compile and are caught by tests | M5 |
| **Pack loader** | Read team onboarding packs from the repo | M7 |
| **Extension** | UI only: CodeLens, sidebar, timer, diff, stats webview | M4 |

---

## 3. Language adapter: the plugin interface

Described in plain words. Write it as an interface/trait in your language.

```
LanguageAdapter
  name()                          -> "go" | "python" | "java" | ...
  detect(repo)                    -> does this repo use this language? + suggested test command
  listFunctions(file)             -> [Function{ id, name, receiver/class, signature,
                                                startByte, endByte, bodyStart, bodyEnd,
                                                lines, complexity }]
  isGenerated(file)               -> bool   (e.g. "Code generated ... DO NOT EDIT.")
  stub(function, source)          -> new source text with the body replaced by a stub that COMPILES
  findTests(function, repo)       -> [TestRef]  (strategy depends on language: see section 8)
  testCommand(testRefs, repo)     -> command + args + env + where the JUnit XML will be written
  keyLines(function)              -> which lines to hide in fill-the-gap mode           (M2)
  mutations(function)             -> [Mutation{ description, newSource }]                (M5)
```

**Function identity:** how do you recognize "the same function" after edits? Suggested ID: `relative path + class/receiver + name + parameter types`. Store a **hash of the body** separately, to notice when it changed. *(Decide: if the body changed a lot, do you reset the exercise's schedule?)*

---

## 4. Sandbox (the safety core)

**Rule:** the user's working tree and branches are **never** modified.

Flow for one exercise:
1. `git worktree add --detach <temp-dir> HEAD`: a clean copy of the last commit.
2. Write the stubbed file **inside the worktree**.
3. The user edits the file **inside the worktree**. (The editor opens that path.)
4. `check` runs the tests **inside the worktree**.
5. On finish or give-up: save the user's version to the store (for history and diff), then remove the worktree.

Things to decide (record them in the devlog):
- The user has **uncommitted changes**. Practice from `HEAD` only, and warn them? Or include their changes?
- One exercise at a time, or several?
- **Crash recovery:** on startup, find orphaned sandboxes (`git worktree list --porcelain`) and clean them up.
- Where temp dirs live, and how they're named, so you can always find your own.
- **Build caches:** a new worktree may rebuild everything from scratch (slow for Java). Can you reuse caches?

---

## 5. Data model (SQLite, starting point)

```
repos(        id, path, language, test_command, created_at )
functions(    id, repo_id, path, qualified_name, signature, start_line, end_line,
              body_hash, lines, complexity, ai_ratio, gradeable, gradeable_reason,
              last_indexed_at )
test_map(     function_id, test_ref )                       -- which tests cover which function
exercises(    id, function_id, mode,                        -- mode: rewrite | gap | bug
              state, due_at, interval_days, ease, reps, lapses, created_at )
attempts(     id, exercise_id, started_at, finished_at, result,   -- result: pass | fail | gave_up
              test_runs, hints_used, user_source, failure_summary )
packs(        id, repo_id, name, source_file )              -- M7
pack_items(   pack_id, function_id, note, order_index )     -- M7
schema_version( version )
```

Questions to answer:
- Store the user's rewritten code? (Useful for "my progress over time", but think about the size.)
- How do you **migrate** the schema when it changes? (Write migrations from day 1.)
- Per-repo database (`.codegym/` in the repo) or one global database (`~/.codegym/`)? What about `.gitignore`?

---

## 6. Config file (`.codegym.yml`, example)

```yaml
version: 1
language: go                     # auto-detected by init; can be overridden
test:
  command: "go test ./... -json"  # or gotestsum --junitfile ...
  timeout_seconds: 60
  junit_path: ".codegym/junit.xml"
include: ["internal/**", "pkg/**"]
exclude: ["**/*_gen.go", "vendor/**", "**/testdata/**"]
exercise:
  min_lines: 5
  max_lines: 60
  min_complexity: 2
daily:
  minutes: 20
  new_per_day: 1
authorship:
  trailers: ["Co-authored-by: Claude", "Co-authored-by: Copilot"]
  transcripts: false              # opt-in parsing of local agent transcripts (M3)
```

---

## 7. CLI commands and the JSON contract

| Command | What it does | Milestone |
|---|---|---|
| `codegym init` | Detect language/test command, write config, build index | M1 (basic in M0) |
| `codegym list` | Show candidate functions with scores | M0 |
| `codegym start <id>` | Create sandbox, blank function, open in editor | M0 |
| `codegym check` | Run mapped tests, show results, record attempt | M0 |
| `codegym diff` | Your version vs the original | M0 |
| `codegym giveup` / `codegym done` | Finish the exercise and clean up | M0 |
| `codegym index` | Rebuild or refresh the index | M1 |
| `codegym today` | Today's workout | M2 |
| `codegym hint` | Next hint level | M2 |
| `codegym stats` | Streaks, progress, weak areas | M2 |
| `codegym mark <id> ai\|mine` | Manual authorship override | M3 |
| `codegym bug` | Start a bug-hunt exercise | M5 |
| `codegym pack list\|start <name>` | Team onboarding packs | M7 |
| `codegym doctor` | Check git, test command, orphaned sandboxes, DB health | M1 |

**JSON contract** (for the extension and scripts): every command supports `--json`. Include a **version number** in the output, so the extension can detect an incompatible CLI.

```json
{ "schema": 1, "command": "check", "exerciseId": 42,
  "result": "fail", "durationMs": 4210,
  "tests": [{"name": "TestParseRetryAfter/http_date", "status": "fail", "message": "expected 120, got 0"}],
  "hintsUsed": 1, "nextDue": null }
```

---

## 8. Per-language notes

| | **Go** | **Python** | **Java** | **TypeScript** |
|---|---|---|---|---|
| Find functions (tree-sitter) | `function_declaration`, `method_declaration` | `function_definition` (incl. methods) | `method_declaration`, `constructor_declaration` | `function_declaration`, `method_definition`, arrow functions |
| Stub idea | `panic("codegym")` | `raise NotImplementedError("codegym")` | `throw new UnsupportedOperationException("codegym");` | `throw new Error("codegym")` |
| Stub gotcha | **Unused imports don't compile**: removing the body can leave imports unused | Generators and `async` functions: does the stub keep the same "kind" of function? | **Constructors**: removing a required `super(...)` call breaks compilation | Return type inference changes without an explicit type |
| Map tests to function | Package tests (simple); per-test coverage via `-run` + `-coverprofile` (slower) | `pytest --cov --cov-context=test`: per-test coverage out of the box | Naming convention (`FooTest`), then JaCoCo at class level | `jest --findRelatedTests` / `vitest related` (import graph) |
| Run selected tests | `go test ./pkg -run 'Regex'` | `pytest -k` / node IDs | Gradle `--tests`, Maven `-Dtest=Class#method` | `jest <paths>` / `vitest run <paths>` |
| JUnit XML | `gotestsum --junitfile` | `pytest --junitxml` | `build/test-results/test/*.xml` (Gradle), `target/surefire-reports/*.xml` (Maven) | `jest-junit`, vitest junit reporter |
| Generated-code marker | `// Code generated ... DO NOT EDIT.` | (by path or convention) | `@Generated`, Lombok | (by path) |
| Loop speed | ⚡ fast | ⚡ fast | 🐢 can be slow (daemon and caching matter) | ⚡ fast-ish |

**Recommendation:** first language **Go or Python** (fast loop, easy test mapping). **Java** in M6 for your teammates.

---

## 9. Tech stack

| Part | Recommended | Alternatives | Notes |
|---|---|---|---|
| Core engine language | **Go** | TypeScript (Node) | Decide in M-1 and write it down as decision ADR-001. See the cgo note below. |
| CLI framework | `cobra` or stdlib `flag` | `urfave/cli` | Keep it simple |
| Parsing | **tree-sitter** (official Go bindings) | `web-tree-sitter` (WASM) in TS; language-native parsers (`go/ast`) | Tree-sitter gives one approach for every language |
| Storage | **SQLite** | JSON files (too limited for stats) | Go: `modernc.org/sqlite` (pure Go) or `mattn/go-sqlite3` (cgo) |
| Test results | **JUnit XML** | Language-specific JSON (e.g. `go test -json`) | One parser for all languages |
| Diff | `git diff --no-index` | A diff library | Reuses git, which is already required |
| Scheduler | **SM-2** (write it yourself), later **FSRS** | — | FSRS has open implementations (`go-fsrs`, `ts-fsrs`) to compare against |
| Extension | TypeScript + VS Code API | — | Generate the scaffold with `yo code` |
| Extension tests | `@vscode/test-electron` / `@vscode/test-cli` | — | |
| Release (CLI) | **goreleaser** + GitHub Actions + Homebrew tap | `go install` | |
| Release (extension) | `vsce` (VS Code Marketplace) + `ovsx` (**Open VSX**, which Cursor and VSCodium use) | — | |
| Demo GIFs | `vhs` (charmbracelet) | asciinema | For the README |

> ⚠️ **cgo note:** tree-sitter's Go bindings use **cgo** (C code). That makes cross-compiling release binaries harder: you can't just build every OS from one Mac. Options: build per-OS on CI runners (a GitHub Actions matrix), use `zig cc` as the cross-compiler, or choose TypeScript + `web-tree-sitter` (WASM, no native build). Decide this in M-1, before writing code.

---

## 10. Repo layout (suggested, for Go)

```
codegym/
  cmd/codegym/            # main.go: CLI entry
  internal/
    engine/               # orchestrates the flow; no language-specific code
    adapter/              # interface + registry
      golang/  python/  java/
    sandbox/              # git worktree management
    runner/               # process exec, timeouts, kill process tree
    junit/                # JUnit XML parser
    index/                # indexing, hashing, cache
    score/                # exercise scoring
    schedule/             # SM-2 / FSRS + "today" builder
    hint/                 # hint ladder
    authorship/           # trailers, blame, transcripts
    mutate/               # bug hunt
    store/                # SQLite + migrations
    output/               # human + JSON printers
  testdata/
    fixture-go/           # the fixture repo (see 04-validation-kit.md)
    fixture-python/
    transcripts/          # sample agent transcripts (synthetic, no real data)
  extension/              # VS Code extension (TypeScript), from M4
  docs/                   # copy these planning docs here when the repo exists
  .github/workflows/      # CI: test, lint, release
```

---

## 11. Testing strategy

| Layer | What | How |
|---|---|---|
| Unit | Parser queries, stub generation, JUnit parsing, scheduler math, scoring | Normal unit tests; table-driven |
| Golden files | CLI output (human and JSON) | Compare with stored expected output; update intentionally |
| Fake clock | Scheduler and `today` across days | Inject a clock; never call "now" directly in logic |
| **Fixture repos** | End-to-end behavior on known tricky cases | `testdata/fixture-*`, see [04-validation-kit.md](04-validation-kit.md) |
| **Adapter conformance** | The same test suite runs against every language adapter | One shared suite + one fixture per language |
| Real-world smoke | Run on a real open-source repo per language | A nightly or manual CI job |
| Extension | Commands, CodeLens, results view | `@vscode/test-electron` + a manual demo script |

---

## 12. Security and privacy

- **Running tests = running code.** Fine for the user's own repo. For **shared packs** (M7) from someone else's repo, it's the same risk as running any untrusted code, so show a clear warning.
- **Nothing leaves the machine** by default. AI features are opt-in, show what will be sent, and support a local model.
- **Agent transcripts** can contain secrets. Read them locally only, never copy them into the DB in full, and store only what you need (file path + time + tool).
- **Never commit** anything to the user's repo. Never push. Sandboxes use a detached HEAD.
- The `.codegym/` folder should be in `.gitignore`. `init` should offer to add it.

## 13. Performance budget (targets; measure them)

| Operation | Target |
|---|---|
| `codegym check` (Go/Python, typical repo) | < 10 s |
| `codegym start` (create worktree + stub + open) | < 2 s |
| Initial index, 5k functions (without stub checks) | < 30 s |
| Stub check per function | Cached; re-run only when the function's body or its tests change |
| `codegym today` | < 300 ms |
| Memory | < 200 MB during indexing |
