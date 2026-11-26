# 0. The Big Picture

> **In one line:** Real-time collaboration means many people change copies of the same data at once, and every copy must end up the same without losing anyone's work.
>
> **You'll learn:** what problems every collaborative app must solve, the main families of solutions, and how the rest of this guide fits together.

---

## 1. What "real-time collaboration" means

Several people (or several devices of one person) **see and change the same data**, see each other's changes within about **100 ms to 1 s**, and **nobody's work is lost**.

You use these every day:

| App | What is shared | What "conflict" looks like |
|---|---|---|
| Google Docs | Text | Two people type in the same sentence |
| Figma, Miro, Excalidraw | Shapes on a canvas | Two people drag the same rectangle |
| Notion | Blocks (paragraphs, lists, tables) | One person moves a block while another edits it |
| VS Code Live Share, Zed | Code files | Two people edit the same function |
| Multiplayer games | Positions, health, scores | Two players grab the same item |
| Shared shopping list | A set of items | One removes "milk" while another adds "milk" |
| Multi-region databases | Rows and counters | Two regions update the same row |

They look different, but under the hood they solve the **same few problems**.

---

## 2. The six core problems

### Problem 1: Latency (the speed of light is slow)
A round trip from India to a US server takes about **200–250 ms**. If every keystroke waited for the server, typing would feel broken. Humans notice delays above roughly **100 ms**, and a screen frame at 60 fps lasts **16.7 ms**.

**Consequence:** every collaborative app applies your change **locally first** ("optimistic update") and syncs it later. That immediately creates the next problem.

### Problem 2: Concurrency (copies drift apart)
Because everyone edits their **own copy** first, two copies can receive different edits at the same moment. When the edits are exchanged, both copies must end up **identical** (this is called **convergence**) and the result should still **make sense** to both users (this is called **intention preservation**).

### Problem 3: Ordering (there is no shared clock)
To decide "which edit came first", you'd want a clock everyone agrees on. Computers can't provide one. Their clocks disagree by milliseconds to seconds. → **Chapter 1**.

### Problem 4: Unreliable networks
Messages can be **delayed, reordered, duplicated, or lost**, and clients go **offline** (train tunnel, closed laptop). Your algorithm must still converge. → **Chapters 2, 4, 8**.

### Problem 5: Smoothness
Updates from other people arrive about **10–30 times per second**, but screens draw **60–120 times per second**. Without care, other people's cursors and objects jump around. → **Chapter 3**.

### Problem 6: Scale
Millions of documents, millions of open connections, one popular document with thousands of viewers, storage of every change for version history. → **Chapters 4, 8, 12**.

---

## 3. A conflict you can see with your own eyes

The document is `"cat"` (positions: `c`=0, `a`=1, `t`=2). Alice and Bob both have a copy.

```
Start:              "cat"                       "cat"
                    Alice                       Bob
Local edit:         insert "h" at 1             delete position 2 ("t")
Local result:       "chat"                      "ca"

They exchange edits and apply them exactly as received:

Alice applies Bob's  "delete position 2"  to "chat" → deletes "a" → "cht"   ✗
Bob   applies Alice's "insert h at 1"     to "ca"   → "cha"                 ✓

Final:              "cht"                       "cha"      ← DIVERGED
```

Bob's delete meant "delete the `t`". But on Alice's copy, position 2 now holds `a`, because her own insert shifted everything right. The positions were correct when each edit was made, but **wrong on the other copy**.

Every technique in this guide exists to solve problems like this one:
- **OT** (Chapter 6) fixes it by **adjusting the position** of Bob's delete before Alice applies it (2 → 3).
- **CRDTs** (Chapters 5 and 7) fix it by **not using positions at all**. Bob says "delete the character with ID `t#1`", which is unambiguous on every copy.

---

## 4. The families of solutions

| Approach | Idea | Used by | Main trade-off |
|---|---|---|---|
| **Locking / turn-taking** | Only one person can edit something at a time | Old file-checkout systems, some CMS tools | Simple, but bad experience |
| **Last-writer-wins (LWW)** | Keep the newest value; drop the other | Cassandra cells, many simple apps | Simple, but silently loses data |
| **Server-authoritative + reconciliation** | The server decides the order; clients predict and correct themselves | Figma, multiplayer games, Replicache | Needs a server; very practical |
| **Operational Transformation (OT)** | Adjust each operation against concurrent ones | Google Docs, Etherpad, ShareDB | Needs a central server in practice |
| **CRDTs** | Data types whose merge always converges, in any order | Yjs, Automerge, Redis Active-Active | More metadata; works offline and peer-to-peer |

Real systems often **mix** them. For example, Figma uses a central server **and** CRDT-inspired per-property LWW **and** fractional indexing for ordering (Chapter 10).

---

## 5. The layers of a collaborative app

Each layer is a chapter in this guide:

```
┌───────────────────────────────────────────────────────────┐
│ UI / rendering        smooth motion, lerp        (Ch 3)   │
├───────────────────────────────────────────────────────────┤
│ Local document state  CRDT or OT client          (Ch 5-7) │
│ Presence              cursors, who's online      (Ch 9)   │
├───────────────────────────────────────────────────────────┤
│ Sync protocol         what to send, catch up     (Ch 8)   │
├───────────────────────────────────────────────────────────┤
│ Transport             WebSocket, WebRTC          (Ch 4)   │
├───────────────────────────────────────────────────────────┤
│ Server                ordering, fan-out, storage (Ch 4,8,10)│
└───────────────────────────────────────────────────────────┘
 Foundation for every layer: time, causality, consistency (Ch 1-2)
 How to know it works:       testing distributed code     (Ch 11)
 Putting it together:        system design interviews     (Ch 12)
```

---

## 6. The mental model: replicas

From now on, think of every client (browser tab, phone, server) as holding a **replica**: a full or partial copy of the shared data.

```
          ┌──────────┐
          │ replica  │  Alice's laptop
          └────┬─────┘
               │  messages (edits) travel
   ┌───────────┼────────────┐  with delay, maybe out of order,
   ▼           ▼            ▼  maybe twice, maybe never
┌──────┐   ┌──────┐     ┌──────┐
│replica│  │replica│    │replica│
└──────┘   └──────┘     └──────┘
 Bob's      server       Carol's
 phone                   tablet
```

Two goals for replicas:
1. **Convergence:** once every replica has received the same set of edits, they are identical.
2. **Intention preservation:** the merged result reflects what each user meant (Bob deleted `t`, so `t` should be gone; Alice added `h` after `c`, so `h` should be after `c`).

Convergence is a math property you can test. Intention is about users, which is why different apps choose different rules (for example, "add wins over remove" in a shopping list, but "remove wins over add" for access permissions).

---

## 7. Words you'll see everywhere

| Word | Meaning |
|---|---|
| **Replica** | One copy of the shared data |
| **Operation (op)** | A description of one change, like "insert `h` after `c`" |
| **State** | The full current value of a replica |
| **Merge** | Combining two replicas' states (or applying a remote op) |
| **Converge** | Become identical |
| **Concurrent** | Two edits where neither knew about the other (Chapter 1) |
| **Causality** | "Edit B was made after seeing edit A" |
| **Idempotent** | Applying something twice has the same effect as once |
| **Tombstone** | A marker that says "this item was deleted", kept so the deletion can be merged correctly |
| **Presence** | Temporary info about who's here: cursors, selections, online status |

A full glossary is in [Chapter 13](13-glossary-and-resources.md).

---

## Check yourself

<details>
<summary>1. Why do collaborative apps apply your edit locally before the server confirms it?</summary>

Because a round trip to the server can take 100–250 ms or more, and users notice delays above about 100 ms. Applying locally makes typing feel instant. The cost is that copies can temporarily differ, so the app needs a way to make them converge.

</details>

<details>
<summary>2. In the "cat" example, why did Alice end up with the wrong text?</summary>

Bob's delete used a **position** (2) that was correct on Bob's copy. On Alice's copy, her own insert had shifted the characters, so position 2 pointed to a different character (`a` instead of `t`). Positions are relative to a specific version of the document.

</details>

<details>
<summary>3. What is the difference between convergence and intention preservation?</summary>

Convergence: all replicas end up identical. Intention preservation: the identical result is also what users meant. "Delete everything on conflict" converges but doesn't preserve intention.

</details>

<details>
<summary>4. Name two ways to fix the "cat" problem.</summary>

OT: transform Bob's delete position from 2 to 3 before Alice applies it. CRDT: give each character a unique ID and delete by ID instead of by position.

</details>

---

[Index](README.md) · [1. Time, Clocks & Causality →](01-time-clocks-causality.md)
