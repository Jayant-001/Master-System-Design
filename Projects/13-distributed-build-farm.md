# 13: Distributed Build Farm

> You built a Thread Pool and a Task Scheduler in `../../Concurrency/`. Here the "tasks" are build steps with dependencies, running on 50 machines, and the best optimization is to **not run them at all**, because someone already did.

---

## The story

Your company has a big codebase: **20,000 build steps** (compile a file, link a library, run a test). A full build on a laptop takes **2 hours**. CI runs 500 builds a day, and most of them rebuild exactly the same things. Developers wait, CI machines cost money, and "it works on my machine" is a daily phrase.

You build a **build farm**: it splits a build into steps, runs them in parallel on many workers, and **caches** results so that nobody builds the same thing twice.

## How it works (plain words)

- A build is a **graph** of steps (a DAG). Linking needs compiled files; compiling needs source files. A step can run only when everything it needs is ready.
- Each step has **inputs** (files, the command, settings) and **outputs** (files).
- If you **hash** all the inputs, you get a fingerprint. Same inputs → same fingerprint → same outputs. So if anyone has run this step before, you can **download the outputs** instead of running it.
- Files are stored by their hash (**content-addressable storage**). Two teams uploading the same file store it once.

## What you'll learn

DAG scheduling · critical path · scheduling policies · content-addressable storage · Merkle trees for directories · action caching · cache key design · hermetic builds · cache poisoning · data locality · worker leases · stragglers · speculative execution · fair-share scheduling · autoscaling with warm caches · log streaming · cancellation.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Action** | One build step: a command with inputs and outputs. |
| **DAG** | Directed Acyclic Graph: steps with arrows "B needs A", and no loops. |
| **Critical path** | The longest chain of dependent steps. The build can never be faster than this, no matter how many machines you have. |
| **Digest / hash** | A fingerprint of some bytes (e.g. SHA-256). Same bytes → same digest. |
| **CAS** | Content-Addressable Storage: store and fetch files by their digest. |
| **Action cache** | Maps "fingerprint of an action's inputs" → "digests of its outputs". |
| **Hermetic** | A step that depends **only** on its declared inputs. No clock, no network, no hidden files. |
| **Cache poisoning** | A wrong result gets into the cache, and everyone downloads it. |
| **Straggler** | One slow task that holds everything up. |
| **Speculative execution** | Running a backup copy of a slow task on another machine, and taking whichever finishes first. |

## Scope

| In | Out |
|---|---|
| Scheduler, workers, CAS, action cache, sandboxing basics, autoscaling | A real compiler toolchain (fake actions are enough; real ones are a stretch) |
| A **synthetic build-graph generator** | Bazel itself (read its Remote Execution API for ideas after L2) |

**Tech:** Java or Go. Postgres or Redis for scheduler state. Local disk or MinIO for CAS. Docker for workers and basic sandboxing. Prometheus + Grafana.

---

## Base system spec

### Build graph format (JSON)

```json
{
  "actions": [
    {"id": "compile_a", "command": ["fakecc", "a.src"], "inputs": ["file:a.src"], "outputs": ["a.obj"], "estMs": 800},
    {"id": "link_app",  "command": ["fakeld"], "inputs": ["action:compile_a", "action:compile_b"], "outputs": ["app.bin"], "estMs": 2000}
  ]
}
```

### Graph generator (build it first)
A script that makes synthetic graphs: N actions with a chosen **shape** (wide, deep, diamond, realistic mix) and random durations. The **fake commands** sleep for their duration and write output that is a deterministic function of their inputs (for example, a hash of the input bytes).
- A `--non-hermetic-rate` flag makes some actions secretly include the current time or a random number in their output. You'll need it in L3.
- A **commit simulator**: produces a stream of "commits", each changing 1-5 source files. Use it to measure cache hit rates.

### API contract

```
POST   /builds                  {graph, priority?, user}      -> {buildId}
GET    /builds/{id}                                           -> {status, actions: [{id, state, worker, cached}]}
GET    /builds/{id}/logs        (streaming)
DELETE /builds/{id}                                           # cancel

PUT    /cas/{digest}    GET /cas/{digest}    POST /cas/findMissing  {digests[]}
GET    /ac/{actionDigest}       PUT /ac/{actionDigest}  {outputDigests}
```

### Build checker (your referee)

| ID | Invariant | From |
|---|---|---|
| F1 | Final outputs match a **local, one-at-a-time reference build** (same digests) | L0 |
| F2 | No action started before all of its inputs were ready | L0 |
| F3 | Every action either ran or was a correct cache hit | L2 |
| F4 | No output in the shared cache came from a non-hermetic action (after L3) | L3 |

### Results table: `docs/results.md`

| Level | Graph | Workers | Wall time | Critical path | Sum of all action times | Cache hit % | Bytes moved | F1 | F2 | F3 | F4 |
|---|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: A local DAG executor

**Incident:** None. Run a build graph on one machine with N threads, respecting dependencies.

**Done when:**
- [ ] F1 and F2 pass for every graph shape.
- [ ] You compute and report three numbers: **wall time**, **critical path**, and **sum of all action times**. You explain how they relate.
- [ ] Cycles in the graph are detected with a clear error.
- [ ] At least **2 scheduling policies** are compared, for example "any ready action" vs "longest remaining path first".

**Predict first:** For a wide graph with 8 threads, how close to the critical path will you get?

**Design questions:**
1. How do you find which actions are **ready** right now, efficiently, without scanning the whole graph every time?
2. What's the critical path, and why is it the lower limit on build time?
3. When more actions are ready than threads available, which should run first? Why does the choice matter?
4. How do you detect a cycle?
5. An action fails. What happens to the actions that depend on it, and to unrelated ones?

**Search:** `topological sort Kahn's algorithm`, `critical path method`, `list scheduling`, `longest path in DAG`, `cycle detection`.

**Self-evaluation:**
- 🟢 Your policy comparison table shows a real difference on at least one graph shape, and you can explain it.
- 🔴 The ready-check scans every action on every completion.

---

## Level 1: Many workers, and the network bottleneck

**Incident:** One machine is too slow, so you add 20 workers. Now every action's inputs must be **sent** to a worker. The network becomes the bottleneck, and some workers sit idle waiting for downloads.

**Done when:**
- [ ] A scheduler plus 20 workers plus CAS. F1 and F2 pass.
- [ ] Bytes transferred per build measured. A plan to reduce it is implemented and measured.
- [ ] A worker dies mid-action: the action is re-scheduled automatically (leases, as in project 03 L1).
- [ ] Workers keep a **local cache** of CAS blobs.

**Predict first:** What fraction of wall time will be spent moving files?

**Design questions:**
1. What is content-addressable storage? Why does hashing make deduplication automatic?
2. A directory of 10,000 files as an input: how do you represent it so that changing one file doesn't mean re-sending all of them? (Search: Merkle tree.)
3. How does a worker get inputs: download everything up front, or only what's missing from its local cache?
4. **Locality:** send an action to a worker that already has its inputs, or to whichever worker is free? What's the trade-off?
5. Big files (1 GB): how should they be stored and moved?

**Search:** `content-addressable storage`, `Bazel Remote Execution API`, `Merkle tree directory digest`, `data locality scheduling`, `delay scheduling`, `worker lease`, `chunked upload`.

**Self-evaluation:**
- 🟢 You have a bytes-moved number before and after your locality/caching change.
- 🔴 Workers re-download the same 500 MB toolchain for every action.

---

## Level 2: The action cache

**Incident:** 500 CI builds a day. 95% of them build exactly what the last build built, but every build still runs every step.

**Done when:**
- [ ] Before running an action, the scheduler checks the action cache. On a hit, it uses the cached outputs.
- [ ] A second identical build is nearly instant. Measure it.
- [ ] **Cache hit rate** measured on the commit simulator stream (each commit changes 1-5 files).
- [ ] CAS has an eviction policy. A cache entry never points to an evicted blob (or you handle it safely).
- [ ] F3 passes.

**Predict first:** With commits changing 1-5 files out of 5,000 sources, what's your expected hit rate?

**Design questions:**
1. What must go into the action's fingerprint? The command, input digests, environment variables, tool versions, OS/CPU type... What goes wrong if you **forget** one?
2. What goes wrong if you **add** something that changes every time (like a timestamp)?
3. Who is allowed to **write** to the cache: only CI, or developers too? Why does that matter?
4. CAS eviction: LRU, but an action cache entry still points to an evicted blob. What do you do?
5. Cache hit rate is lower than expected. How do you find out **why** a specific action missed?

**Search:** `Bazel remote caching`, `action cache key`, `cache miss debugging execution log`, `LRU eviction with references`, `cache hit rate`.

**Self-evaluation:**
- 🟢 You can explain any cache miss using a tool you built.
- 🔴 The fingerprint is just the command string.

---

## Level 3: Cache poisoning

**Incident:** One action secretly reads the current time and puts it in its output. It gets cached. Now 200 developers download that output, and a test that depends on it fails for everyone. Separately, a developer's laptop with a different compiler version uploaded broken results.

**Done when:**
- [ ] Turn on `--non-hermetic-rate`. Your system **detects** non-hermetic actions by sometimes running them twice on different workers and comparing outputs. Measure the detection rate.
- [ ] Detected actions are flagged and **not cached**.
- [ ] Basic **sandboxing**: clean environment variables, read-only inputs, only declared inputs visible, no network (optional, via Docker).
- [ ] Developer machines **can't** poison the shared CI cache.
- [ ] A **purge** tool removes a bad cache entry **and everything built from it**.
- [ ] F4 passes.

**Predict first:** With 1% non-hermetic actions and 5% double-run sampling, how long until you catch them?

**Design questions:**
1. List what can make an action non-hermetic: time, randomness, network, undeclared files, environment variables, machine differences...
2. Running everything twice doubles cost. How do you catch problems cheaply?
3. Who do you trust to write to the cache, and how do you enforce it?
4. A poisoned entry is found. Which **other** cache entries are now suspect? How do you find them?
5. Sandboxing: what's the cheapest isolation that stops most problems?

**Search:** `hermetic builds`, `reproducible builds`, `build sandboxing`, `remote cache poisoning`, `cache trust model CI only writes`, `provenance`.

**Self-evaluation:**
- 🟢 The purge tool removes a bad result and its downstream results in one command.
- 🔴 Developers' laptops write directly into the shared cache.

---

## Level 4: Stragglers

**Incident:** One worker has a failing disk and runs 10× slower. Whenever an action on the critical path lands on it, the whole build waits **10 extra minutes**.

**Done when:**
- [ ] Stragglers detected: an action running much longer than expected.
- [ ] A **backup copy** is started on another worker. The first one to finish wins, and the other is cancelled.
- [ ] Bad workers are noticed and taken out of rotation.
- [ ] Measured: build time with 1 slow worker, before and after. Extra work (wasted %) measured.

**Predict first:** How much does one slow worker (out of 20) slow down the average build?

**Design questions:**
1. How do you know how long an action **should** take? Where does that estimate come from?
2. When should you launch a backup: after 2× expected time? Only on the critical path?
3. Why is speculative execution **safe only for hermetic actions**? (Connect it to L3.)
4. How do you cancel the loser cleanly, including its partial outputs?
5. How do you tell "this worker is slow" apart from "this action is just slow today"?

**Search:** `speculative execution stragglers MapReduce paper`, `The Tail at Scale`, `hedged requests`, `task duration estimation`, `outlier detection workers`.

**Self-evaluation:**
- 🟢 You have a before/after build time chart, plus the extra work your backups cost.
- 🔴 Backups are launched for every action.

---

## Level 5: Monday morning (fairness and autoscaling)

**Incident:** Monday 10am: 300 developers and all of CI start builds at once. Queue time is **40 minutes**. One giant release build uses all the workers. At night, 200 idle workers cost money for nothing.

**Done when:**
- [ ] **Fair share** between users and builds: one giant build can't take everything. (Project 03 L3 again, different system.)
- [ ] Priorities: a developer waiting at a keyboard comes before a nightly job.
- [ ] **Autoscaling** of workers based on queue depth (or something better you choose).
- [ ] New workers start with a **warm cache** (or you measured how slow cold ones are and dealt with it).
- [ ] Scale-down never kills a running action.
- [ ] Measured: p50/p99 queue wait at the Monday peak, and worker-hours per day vs a fixed fleet.

**Predict first:** How long does a brand-new worker take to become as fast as an old one?

**Design questions:**
1. Fair share by user, by build, or by team? What does each one feel like for a developer?
2. What signal should trigger scale-up: queue length, wait time, or predicted work? Why might queue length lie?
3. A new worker has an empty local cache. How slow is it? How could you warm it?
4. How do you scale down safely?
5. Should CI and developers share workers at all?

**Search:** `fair share scheduling`, `dominant resource fairness`, `autoscaling on queue depth`, `KEDA`, `warm pools`, `scale-in protection`, `graceful drain`.

**Self-evaluation:**
- 🟢 You have a Monday-peak chart (queue wait) and a cost chart (worker-hours), before and after.
- 🔴 Autoscaling kills workers in the middle of actions.

---

## Stretch levels
- **Real actions:** compile a real small C or Java project through your farm.
- **Test sharding:** split a slow test suite across workers, and detect **flaky** tests (pass/fail on the same inputs).
- **Live log streaming** for 1,000 concurrent actions.
- **Build analytics:** which targets cause the most cache misses?

## Graduation: answer out loud
1. Why can't a build be faster than its critical path?
2. How does content-addressable storage make caching and dedupe easy?
3. What goes into an action cache key, and what happens if you get it wrong?
4. How do you stop cache poisoning?
5. What is speculative execution, and when is it unsafe?
6. How do you autoscale workers without making builds slower?

## Taking it further
Implement enough of the Bazel Remote Execution API that real Bazel can use your farm · a dashboard of build times and hit rates · flaky-test detection service · a cost report per team.
