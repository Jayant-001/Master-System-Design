# 05: Launch and Open Source

> Start the **approval** item early (it can take time). Do everything else in M7.

---

## 1. Before going public (blocking items)

- [ ] **Amazon approval.** Amazon has an internal process for employees' personal and open-source projects. Find the current policy on the internal wiki (search "open source" / "personal projects" / "outside activities") and get approval **before** making the repo public or publishing to any marketplace.
- [ ] **No internal content.** No Amazon code, package names, internal tool names, screenshots of internal repos, or internal data in the repo, issues, demos, or posts. Demo only on public open-source repos and the fixture repo.
- [ ] **Built on personal time and equipment**, as the policy requires. Check what it says.
- [ ] **Prior-art check updated** (things move fast). If something very similar appeared, decide: differentiate, collaborate, or contribute.
- [ ] **Name is free** on GitHub, the VS Code Marketplace, Open VSX, Homebrew, and npm (if relevant).
- [ ] **License chosen** (ADR-003) and a `LICENSE` file added.

## 2. Repo readiness

- [ ] `README.md` (see structure below)
- [ ] `LICENSE`
- [ ] `CONTRIBUTING.md`: how to build, test, and add a language adapter (point to the conformance suite)
- [ ] `CODE_OF_CONDUCT.md` (a standard template)
- [ ] `SECURITY.md`: how to report issues; explain that running tests runs code
- [ ] `CHANGELOG.md`: following semantic versioning
- [ ] Issue templates: bug report, language request, feature idea
- [ ] CI badges: build, tests
- [ ] `docs/`: these planning docs, cleaned of anything personal

### README structure (in this order)
1. **One line + GIF:** what it is, shown working in under 15 seconds (record it with `vhs`).
2. **Why:** 3 sentences about skills fading with AI coding, and practicing on your own code.
3. **Install:** brew / go install / binary download / VS Code / Cursor (Open VSX).
4. **Quick start:** 5 commands, from `init` to the first passed exercise.
5. **How it works:** sandbox, stub check, spaced repetition (one paragraph + one diagram).
6. **Modes:** rewrite, fill-the-gap, bug hunt.
7. **Privacy:** "Your code never leaves your machine" and exactly what optional features send.
8. **Supported languages** and how to add one.
9. **FAQ:** "Does it change my code?" (no), "What if my tests are slow?", "Is it AI-generated practice?" (no, it's your real code)
10. **Contributing, License.**

## 3. Packaging and release

| Artifact | Tool | Notes |
|---|---|---|
| CLI binaries (macOS arm64/x64, Linux x64/arm64; Windows later?) | goreleaser + GitHub Actions | With tree-sitter + cgo, build on each OS's runner, or use `zig cc` (see 02-design §9) |
| Homebrew | goreleaser Homebrew tap | `brew install <you>/tap/codegym` |
| `go install` | — | Works if cgo builds on the user's machine |
| VS Code extension | `vsce package` / `vsce publish` | Needs a publisher account |
| Open VSX (for Cursor, VSCodium) | `ovsx publish` | Many AI-editor users are here, so don't skip it |
| Checksums + signed releases | goreleaser | Builds trust |

- [ ] Version `v0.1.0` as the first public release. Use semver from then on.
- [ ] A release checklist in `CONTRIBUTING.md` (tag → CI builds → verify install on a clean machine → announce).

## 4. Launch plan

**Pre-launch (1 week before)**
- [ ] 3–5 friends try it (fresh-machine test D4). Fix the top issues.
- [ ] Prepare the GIF, a 60-second video (optional), and a short blog post: "I built a gym for my coding skills".

**Launch day**
- [ ] Blog post (your own site or dev.to / Hashnode / Medium).
- [ ] LinkedIn post with the GIF. Your network of engineers is the main audience.
- [ ] Show HN (read the Show HN guidelines first; post on a weekday morning US time).
- [ ] Relevant subreddits (read each one's self-promotion rules first).
- [ ] Communities of AI-coding-tool users (Claude Code, Cursor) where sharing tools is welcome.

**After launch (first 2 weeks)**
- [ ] Reply to every issue and comment within 24–48h.
- [ ] Track: stars (vanity), installs, **issues from real usage** (the real signal), and people who come back.
- [ ] Ship a `v0.1.1` with the top 3 fixes in week 1. It shows the project is alive.

## 5. Message (draft and refine)

> **Code Gym: practice on the code you actually work with.**
> AI writes more of our code every day, and our own skills quietly fade. Code Gym picks functions from your repo (especially the ones AI wrote for you), hides the body, and lets your own tests grade your rewrite. Spaced repetition brings back the hard ones. Local-first, no account, works in your terminal, VS Code, and Cursor.

## 6. Maintenance (decide before launch)

- [ ] How much time per week will you give it? (Be honest. 2 hours is fine.)
- [ ] Which issues you'll accept: languages, modes, UI. Write a short "project scope" note in `CONTRIBUTING.md`.
- [ ] Label `good first issue` items so others can help (new language adapters are perfect for this).
- [ ] A dependency update routine (monthly).
