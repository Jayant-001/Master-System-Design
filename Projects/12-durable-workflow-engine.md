# 12: Durable Workflow Engine

> At work you use workflow systems (Orca, Step Functions, SWF). Here you build one, so you understand what they do for you and why they put rules on your code. It's also the answer to "who finishes the half-done transfer after a crash?" from project 11 L3.

---

## The story

Your company's order process looks like this:

```
reserve inventory → charge card → wait for warehouse (up to 2 days) → ship
→ send email → wait 30 days → ask for a review
```

Today it's built from cron jobs, status columns, and hope. When a server crashes, orders get **stuck** in the middle. Some customers are charged and never shipped. Others get two emails. Nobody can answer "where is order 123 right now?"

You build an engine where developers write this process **as normal-looking code**, and the engine guarantees it **finishes**, through crashes, deploys, and 30-day waits.

## How it works (plain words)

- The developer writes a **workflow** function. It calls **activities** (steps with side effects, like "charge card"), sleeps, and waits for outside events.
- The engine records everything that happens in an **event history**: "activity charge card started", "activity completed with result X", "timer fired"...
- If the worker running the workflow dies, another worker picks it up and **runs the workflow code again from the top**. This time, when the code calls an activity that already finished, the engine **returns the saved result from history** instead of running it again. This is **replay**. The code "fast-forwards" to where it stopped, then continues for real.
- Replay only works if the workflow code makes **the same decisions every time** it runs. That's why these systems have strict rules for workflow code.

## What you'll learn

Durable execution · event histories · replay · determinism rules · activities vs workflow code · task queues and long-polling · activity timeouts and heartbeats · at-least-once activities + idempotency · durable timers at scale · signals · sagas and compensation · versioning running workflows · sharding workflow state · leases and fencing.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Workflow** | The process logic, written as code. It must be deterministic. |
| **Activity** | One step that touches the outside world (charge a card, send an email). It can fail, and it can be retried. |
| **Event history** | The append-only log of everything that happened to one workflow run. |
| **Replay** | Re-running workflow code using the history to skip finished steps. |
| **Deterministic** | Same history in → same decisions out, every time. |
| **Task queue** | Where work waits for workers to pick it up. |
| **Timer** | "Wake this workflow up at time T", which must survive crashes. |
| **Signal** | A message from outside into a running workflow ("warehouse shipped it"). |
| **Saga / compensation** | Undoing earlier steps when a later step fails. |
| **Versioning** | Changing workflow code while old runs are still in progress. |

## Scope

| In | Out |
|---|---|
| Engine server, a small SDK in your language, workers, timers, signals, sagas, versioning, sharding | A web UI (optional; AI can build one) |
| Fake activities with failure knobs | SDKs in several languages |

**Tech:** Java or Go. Postgres for history and timers. k6 or a small driver program for load. Prometheus + Grafana.

---

## Base system spec

### What a developer writes (your SDK must make this possible)

```
orderWorkflow(ctx, orderId):
    ctx.activity("reserveInventory", orderId)
    payment = ctx.activity("chargeCard", orderId)
    shipped = ctx.waitForSignal("warehouseShipped", timeout = 2 days)
    if not shipped: ctx.activity("refund", payment); return "CANCELLED"
    ctx.activity("sendEmail", orderId, "shipped")
    ctx.sleep(30 days)
    ctx.activity("askForReview", orderId)
    return "DONE"
```

### Engine API

```
StartWorkflow(type, workflowId, input)        -> runId     (same workflowId twice → ?  you decide in L0)
SignalWorkflow(workflowId, name, payload)
GetWorkflow(workflowId)                       -> {status, history[]}
CancelWorkflow(workflowId)

# used by workers (through your SDK)
PollWorkflowTask(queue)       -> {workflowId, history}      # "decide what to do next"
CompleteWorkflowTask(token, commands[])                     # e.g. "schedule activity X", "start timer"
PollActivityTask(queue)       -> {activity, input}
CompleteActivityTask(token, result | error)
HeartbeatActivity(token, progress)
```

### Fake activities
Each one logs every execution (with an idempotency key) to an **effects log** and has knobs: `LATENCY_MS`, `FAIL_RATE`, `HANG_RATE`, `CRASH_AFTER_EFFECT_RATE` (does the effect, then dies before reporting success).

### Workflow checker (your referee)

| ID | Invariant | From |
|---|---|---|
| W1 | Every started workflow reaches a final state (done / failed / cancelled). None stuck. | L1 |
| W2 | Each activity's **effect** happened once per logical step (by idempotency key in the effects log), or the duplicate is explained | L1 |
| W3 | History is well-formed (no "completed" without "started", no gaps) | L0 |
| W4 | One workflow never makes progress on two workers at the same time | L1 |
| W5 | Timers fire no earlier than scheduled, and no later than your bound | L3 |

### Results table: `docs/results.md`

| Level | Scenario | Workflows | Steps/sec | Schedule→start p99 | Timer lateness p99 | Stuck | W1 | W2 | W4 | W5 |
|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: An engine with a history

**Incident:** None. One engine server, one worker. Workflows run activities in order, and every step is recorded.

**Done when:**
- [ ] The sample workflow (without the long sleep yet) completes. `GetWorkflow` shows a readable history.
- [ ] W3 passes.
- [ ] You decided what happens when `StartWorkflow` is called twice with the same ID, and tested it.

**Predict first:** Which events will your history need? List them before coding.

**Design questions:**
1. What event types do you need? (Started, ActivityScheduled, ActivityStarted, ActivityCompleted, ActivityFailed...) What does each store?
2. Who decides the **next step**: the server or the worker? Why do real engines let the **worker** run the workflow code?
3. Where does workflow state live **between** steps? (Not in a worker's memory. Why not?)
4. Same `workflowId` started twice: reject it, return the existing run, or start a new one? What do users of the API need?
5. How big can one history get? What does that mean for storage?

**Search:** `durable execution`, `event history workflow`, `Temporal concepts`, `AWS Step Functions concepts`, `Amazon SWF decider`, `event sourcing`.

**Self-evaluation:**
- 🟢 You can read a history and tell exactly what the workflow did.
- 🔴 Workflow progress is stored as a "current step" number that gets overwritten.

---

## Level 1: The crash, and replay

**Incident:** `kill -9` a worker in the middle of a workflow. The workflow is stuck forever. Or worse: when it's restarted, it **charges the card again**.

**Done when:**
- [ ] Another worker picks up the workflow and **replays** it from history. Finished activities are **not** run again.
- [ ] **Chaos test:** 1,000 workflows running, workers killed at random every few seconds. **W1 and W4 pass.** W2 duplicates are counted and explained.
- [ ] Activity timeouts implemented: schedule-to-start, start-to-close, and heartbeat. Each is tested with a fake activity that triggers it.

**Predict first:** With `CRASH_AFTER_EFFECT_RATE = 5%`, how many duplicate effects per 1,000 workflows?

**Design questions:**
1. During replay, what does `ctx.activity("chargeCard")` return? How does the SDK know this call matches the "chargeCard completed" event in history?
2. How does the engine notice a worker died? (Leases again, as in project 03 L1.)
3. An activity did its work, then the worker died **before** reporting success. The engine retries it. So activities are **at-least-once**. What must every activity do because of that? (Project 01 L3.)
4. What does each activity timeout catch?
   - schedule-to-start: ?
   - start-to-close: ?
   - heartbeat: ?
5. How do you make sure two workers never advance the same workflow at the same time (W4)?

**Search:** `deterministic replay workflow`, `activity timeouts schedule-to-start start-to-close heartbeat`, `at-least-once activities idempotency`, `worker lease`, `fencing token`.

**Self-evaluation:**
- 🟢 You can trace one workflow through two crashes using only its history.
- 🔴 After a crash, finished activities run again.

<details><summary>Hint (how replay matches calls)</summary>
Every time workflow code asks for an activity, timer, or signal, give that request a sequence number. Look up that number in history.
</details>

---

## Level 2: Determinism bugs

**Incident:** A developer writes workflow code that uses `random()`, `now()`, iterates over a hash map, and makes an HTTP call directly. On replay the code takes a **different path** than the first time. Some workflows crash with confusing errors. Others **silently do the wrong thing**.

**Done when:**
- [ ] The engine **detects** non-determinism during replay (the code asks for something different from what history says) and fails loudly with a clear error.
- [ ] The SDK gives safe versions of what developers need: `ctx.now()`, `ctx.random()`, and a way to record a one-off side effect.
- [ ] **Replay tests:** a tool that takes saved histories and replays them against new code, so the problem is caught **before deploy**.
- [ ] A short "rules for workflow code" doc for your users.

**Predict first:** Which of the 4 bad patterns will be the hardest to detect?

**Design questions:**
1. List every source of non-determinism you can think of. (Time, randomness, map order, threads, I/O, environment variables, config...)
2. How can the engine tell that replay went off track? What exactly does it compare?
3. During replay, what should `ctx.now()` return? Where does that value come from?
4. Why can't workflow code do I/O directly? What goes wrong even if the I/O "always returns the same thing"?
5. How do you help developers find these bugs before production?

**Search:** `workflow determinism constraints`, `Temporal non-deterministic error`, `side effect API workflow`, `replay testing workflows`.

**Self-evaluation:**
- 🟢 All 4 bad patterns are caught by detection or by replay tests.
- 🔴 Detection only works when the bug causes a crash.

---

## Level 3: Timers and signals at scale

**Incident:** Business is good. **10M workflows** are sleeping for 30 days, **1M timers** fire at midnight for a campaign, and signals arrive while the workflow isn't running, or arrive twice.

**Done when:**
- [ ] Durable timers: they survive engine restarts. 10M pending timers don't slow anything down.
- [ ] Short timers fire within **1 second** of their time (W5). Measure lateness.
- [ ] The midnight spike (1M timers) doesn't hurt normal traffic. (Project 03 L5. Can you reuse that work?)
- [ ] Signals are always delivered into history **exactly once**, even when sent twice, and even when they arrive during a workflow task.
- [ ] `waitForSignal` with a timeout works when the signal and the timeout happen at almost the same moment.

**Predict first:** What's timer lateness p99 at the midnight spike?

**Design questions:**
1. Where do timers live? How do you find the ones that are due, efficiently, with 10M pending?
2. The engine was down for 5 minutes. When it comes back, 300k timers are overdue. What happens?
3. A signal arrives while a worker is in the middle of a workflow task. How do you avoid losing it or applying it twice?
4. A signal and a timeout happen at the same moment. Which one "wins", and how does the workflow see a single clear order of events?
5. A client retries sending a signal. How do you dedupe it?

**Search:** `durable timers`, `timer service sharding`, `hierarchical timing wheel`, `signal workflow`, `mailbox pattern`, `exactly-once signal dedupe`.

**Self-evaluation:**
- 🟢 You have a timer lateness histogram, including at the spike.
- 🔴 Timers are kept in memory only.

---

## Level 4: Sagas (undo when things fail)

**Incident:** A travel booking workflow books a hotel, then a flight, then a car. The **car** booking fails. The customer has paid for half a trip and can't use it.

**Done when:**
- [ ] The SDK lets developers register a **compensation** after each step ("if anything later fails, cancel this hotel").
- [ ] On failure, compensations run **in reverse order**.
- [ ] Compensations that fail are retried. If one fails "forever", a human is alerted, with clear state.
- [ ] Chaos test: crash **during** compensation. The final state is consistent.

**Predict first:** What's the hardest step to compensate in the travel example?

**Design questions:**
1. Why reverse order?
2. A compensation can't succeed for 2 days (the refund API is down). What should the workflow do meanwhile?
3. Some steps **can't** be undone (an email was sent). Where should those steps go in the workflow?
4. What's the difference between a step that **failed** and one that **timed out** (it may have worked!)? How do you compensate a step you're not sure about?
5. Compare with project 11 L3. Could your ledger's cross-shard transfer run as a workflow here?

**Search:** `saga pattern`, `compensating transaction`, `Sagas paper Garcia-Molina 1987`, `pivot transaction`, `orchestration vs choreography`.

**Self-evaluation:**
- 🟢 Crash-during-compensation test passes 10/10.
- 🔴 Compensations assume the original step definitely succeeded.

---

## Level 5: Deploying new code (versioning)

**Incident:** You add a fraud check as step 2 of the order workflow and deploy. **1M orders are in progress** with histories from the old code. On replay, the new code asks for "fraud check" where history says "charge card". **Every in-progress order fails.**

**Done when:**
- [ ] New workflow code can be deployed while old runs continue correctly.
- [ ] At least one versioning strategy implemented and tested with **saved old histories** (your replay tests from L2).
- [ ] You know how to remove old code paths, and when it's safe.

**Predict first:** How many in-progress workflows break with a naive deploy?

**Design questions:**
1. Option A: keep old and new workers running side by side, and send each run to the code it started with. Cost?
2. Option B: put version branches in the code (`if ctx.version("fraud-check") >= 1`). How does replay know which branch an old run took?
3. Workflows that sleep for 30 days: how long must old code paths live?
4. Which code changes are **safe** without versioning? (Changing an activity's internals? Adding a log line? Reordering steps?)
5. How do you know when no old runs remain?

**Search:** `workflow versioning`, `Temporal patching getVersion`, `worker versioning build id`, `blue-green deployment`, `replay compatibility`.

**Self-evaluation:**
- 🟢 A deploy with 10k in-progress old runs completes with 0 failures.
- 🔴 The versioning plan is "drain all workflows before deploying".

---

## Level 6: Scaling the engine

**Incident:** 50,000 workflow steps per second. One Postgres history table is hot, and workers polling the task queues are melting the database.

**Done when:**
- [ ] Workflows split across N **history shards** by workflow ID. Throughput measured with 1, 2, and 4 shards.
- [ ] Each shard is owned by exactly one engine node at a time (lease + fencing). Ownership moves when a node dies.
- [ ] Task queues use **long-polling** (or push). DB load from polling measured before and after.
- [ ] Very long histories are handled (for example, start a fresh run that carries the state forward).
- [ ] (Stretch) Search workflows by status or custom fields, from a separate store.

**Predict first:** What will DB queries/sec be with 1,000 workers polling every 100 ms?

**Design questions:**
1. What must be **strongly consistent** for one workflow? Why is "one writer per workflow" enough?
2. How does a node prove it still owns a shard, and what stops a node that **thinks** it does but doesn't?
3. Long-poll vs push vs short poll: what does each cost?
4. A workflow with 1M events in history: what breaks? What's "continue-as-new"?
5. Visibility search ("all orders stuck in warehouse wait > 2 days"): why keep it in a separate store from history?

**Search:** `sharding by workflow id`, `shard ownership lease fencing`, `long polling`, `continue-as-new`, `visibility store`, `Temporal architecture history service`.

**Self-evaluation:**
- 🟢 Throughput scales nearly linearly with shards, and you know where it stops.
- 🔴 Two nodes can both write to the same shard during a handover.

---

## Graduation: answer out loud
1. Explain durable execution and replay to a new engineer.
2. Why must workflow code be deterministic? Give 4 ways to break it.
3. Why are activities at-least-once, and what does that require?
4. How do you run 10M timers reliably?
5. How do you deploy a code change while 1M workflows are mid-run?
6. Compare Step Functions (state machine JSON) with Temporal-style (code + replay). When is each better?

## Taking it further
A web UI showing each workflow's history as a timeline · an SDK in a second language · use it to run project 11's cross-shard transfers · compare your design with Orca at work and write up the differences · open-source a minimal engine.
