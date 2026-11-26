# 11. Testing Distributed Algorithms

> **In one line:** Bugs in CRDT, OT, and sync code hide in rare orderings of messages, so test with **properties** over **thousands of random, reproducible scenarios**, not just a few hand-written examples.
>
> **You'll learn:** scenario tests, property-based testing, fuzzing, randomized simulation with seeds, shrinking, deterministic simulation testing in industry, model checking (TLA+, P), Jepsen, and testing real-time UIs.
>
> **Before this:** [2. Merging](02-replication-consistency-merging.md) (the network simulator task). Use this chapter alongside Chapters 5–8.

**Summary:**
- **Scenario tests** document intended behavior (every walkthrough in this guide).
- **Properties** catch what you didn't think of: merge laws, TP1, convergence, "no op lost or duplicated".
- **Random simulation:** seeded RNG + simulated network (delay, reorder, duplicate, drop, partition) + invariant checks; print the seed on failure.
- Industry does the same at scale: FoundationDB, TigerBeetle, Antithesis; AWS uses TLA+ and P for design-level checking.

---

## 1. Why ordinary tests aren't enough

A test like "Alice inserts X, Bob inserts Y, check the result" checks **one** ordering. With 3 replicas and 20 operations, the number of possible delivery orders is astronomically large. The bug is usually in an ordering nobody wrote down.

Example of a real-feeling bug: your OR-Set works in every scenario test, but fails when a **remove arrives before the add it refers to** (only possible with reordering), or when the **same add is delivered twice**.

---

## 2. Scenario (example-based) tests

Still essential:
- They **document** the intended semantics: "concurrent add and remove → add wins".
- They're the walkthroughs from each chapter, turned into tests.
- Use **table-driven tests**: a list of (name, setup, operations, expected result).

---

## 3. Property-based testing

Instead of a specific input and output, state a **property** that must hold for **all** inputs, and let the framework generate hundreds of random inputs.

| Property | For |
|---|---|
| `merge(a, b) == merge(b, a)` | State-based CRDTs |
| `merge(merge(a, b), c) == merge(a, merge(b, c))` | State-based CRDTs |
| `merge(a, a) == a` | State-based CRDTs |
| `apply(apply(S, a), T(b, a)) == apply(apply(S, b), T(a, b))` (TP1) | OT |
| Same set of ops, any order → same state | Op-based CRDTs |
| `compare(a, b) == BEFORE ⇔ compare(b, a) == AFTER` | Vector clocks |
| After sync, every op appears exactly once | Sync, dedup |
| `decode(encode(x)) == x` | Message encoding, storage |

**Go tools:** the standard library's `testing/quick` (simple), `pgregory.net/rapid` (property testing with shrinking), and built-in **fuzzing** (`go test -fuzz=FuzzName`), which mutates inputs to find crashes and failing properties.

---

## 4. Randomized simulation (the big one)

### 4.1 The harness
```text
function simulate(seed):
    rng      = newRandom(seed)
    network  = SimNetwork(rng, dropRate, dupRate, maxDelay)
    replicas = [newReplica(id) for id in 1..N]

    for step in 1..STEPS:
        choice = rng.pick(["local op", "deliver", "partition", "heal"])
        if local op:  r = rng.pick(replicas); op = randomOp(r, rng); r.apply(op); network.send(r, op or state)
        if deliver:   network.deliverOne()             # random in-flight message; may duplicate
        if partition: network.cut(randomGroups())
        if heal:      network.heal()
        checkInvariants(replicas)                       # e.g. no crash, local invariants

    network.heal(); network.deliverAll(noDrops)        # let everyone sync fully
    assert all replicas have the same state            # convergence
    assert expected ops are present exactly once       # no loss, no duplicates

for seed in 1..10000:
    if not simulate(seed): print("FAILED with seed", seed); stop
```

### 4.2 Reproducibility is everything
- **All** randomness comes from the seeded RNG: operation choice, delays, drops, IDs. No `time.Now()`, no unseeded random, no goroutine scheduling decisions in the core.
- On failure, print the seed; re-run that seed with logging to see the exact sequence.

### 4.3 Shrinking
A failure after 5,000 steps is hard to understand. Shrink it: try removing steps (or lowering STEPS, or fewer replicas) while the failure still happens, until you have the smallest failing case. Property testing libraries do this automatically.

---

## 5. Deterministic simulation testing in industry

- **FoundationDB** is famous for running its entire distributed database inside a deterministic simulator (simulated network, disks, and clocks) with fault injection, before ever running on real machines.
- **TigerBeetle** runs a simulator (the VOPR) that explores faults and orderings at high speed.
- **Antithesis** is a company offering deterministic simulation testing for arbitrary software.

The idea scales down perfectly to your project: your `sim/` package is the same technique.

---

## 6. Model checking (testing the design, not the code)

- **TLA+** lets you write a precise model of an algorithm (states and transitions) and have a model checker explore **every** reachable state for small configurations. AWS published *How Amazon Web Services Uses Formal Methods* (2015), describing TLA+ use on services such as S3 and DynamoDB.
- **P** is a language for modeling and testing distributed protocols as communicating state machines; AWS has written about using it for services including S3.
- Use model checking when the **protocol** is subtle (consensus, leases, failover) and bugs would be catastrophic.

---

## 7. Jepsen

Jepsen (Kyle Kingsbury) tests real databases by running them on a cluster, injecting partitions, clock skew, and crashes, and checking the recorded history against a consistency model. Its public analyses are a great way to learn how real systems fail. Not something you'll run for this project, but worth reading.

---

## 8. Testing real-time UIs

- **Two (or more) tabs** side by side, typing at the same place.
- **Artificial latency and loss** on your server (the knobs from Chapter 3's practice task) or browser devtools network throttling.
- **Kill the connection** mid-edit and check nothing is lost or duplicated after reconnect.
- **Bots:** scripted clients making random edits for minutes, then compare final documents.
- **Frame-rate checks:** run the cursor demo at different refresh rates and check motion looks the same.

---

## Check yourself

<details>
<summary>1. Why must all randomness in a simulation come from one seeded RNG?</summary>

So any failure can be reproduced exactly by re-running with the same seed. Unseeded randomness or real time makes failures impossible to replay.

</details>

<details>
<summary>2. Name three properties you'd test for a state-based CRDT.</summary>

Commutativity, associativity, and idempotence of merge. Also convergence: replicas that receive the same updates end up equal.

</details>

<details>
<summary>3. What does shrinking give you?</summary>

The smallest input or step sequence that still fails, which makes the bug much easier to understand.

</details>

---

## Practice

- Make your `sim/` network (Task 4) support: drop rate, duplicate rate, random delay, partitions (groups that can't talk), and `deliverAll`.
- Use it in: CRDT convergence tests (Tasks 8–11, 14), OT TP1 tests (Task 12), and delta sync (Task 16).
- Add one fuzz test (`go test -fuzz`) to your OT `Transform` with random documents and operations.

---

## Further reading
- Will Wilson, *Testing Distributed Systems w/ Deterministic Simulation* (FoundationDB talk).
- Newcombe et al., *How Amazon Web Services Uses Formal Methods* (Communications of the ACM, 2015).
- Hillel Wayne, *Learn TLA+* (learntla.com).
- Jepsen analyses at jepsen.io.
- Go documentation: *Tutorial: Getting started with fuzzing*.

---

[← 10. Server-Authoritative Sync & Real Systems](10-server-authoritative-real-systems.md) · [Index](README.md) · [12. System Design Interviews →](12-system-design-interviews.md)
