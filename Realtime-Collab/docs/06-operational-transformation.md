# 6. Operational Transformation (OT)

> **In one line:** Keep simple position-based operations ("insert at 3"), and when two happen concurrently, **adjust ("transform")** one against the other so both sides end up with the same text.
>
> **You'll learn:** text operations, `apply`, the `transform` function case by case, the convergence properties TP1 and TP2, the central-server model used by Google Docs, the client state machine, compose, undo, cursor transformation, OT for JSON and rich text, and where OT is used.
>
> **Before this:** [0. The Big Picture](00-big-picture.md) (the "cat" example) and [2. Merging](02-replication-consistency-merging.md) (total order).

**Summary:**
- `transform(a, b)` returns `a'`: operation `a` rewritten so it can be applied **after** `b`, with the same intent.
- **TP1:** `apply(apply(S, a), T(b, a)) = apply(apply(S, b), T(a, b))`. Needed by every OT system.
- **TP2** (needed without a central server) is very hard; several published algorithms were wrong. So practical OT uses a **central server** that orders all operations.
- Client states: **Synchronized → AwaitingConfirm → AwaitingWithBuffer**.
- Used by Google Docs, Etherpad, ShareDB, CodeMirror 6 collab.

---

## 1. The idea

Remember the "cat" example (Chapter 0): Bob's "delete position 2" was correct on Bob's copy but wrong on Alice's copy, because Alice's insert shifted the text.

OT's fix: **before Alice applies Bob's operation, rewrite it** to account for her own concurrent insert. "Delete position 2" becomes "delete position 3".

**Analogy:** Bob says "the book is on the 3rd shelf". Alice, meanwhile, added a new shelf at the top. Before she goes looking, she adjusts Bob's instruction: "3rd shelf" → "4th shelf".

```
              S = "cat"
             /          \
      a = ins(1,"h")    b = del(2)
           /                \
     "chat"                  "ca"
          \                  /
   T(b,a) = del(3)     T(a,b) = ins(1,"h")
            \              /
              "cha"   =   "cha"      ✓ converge
```

---

## 2. Operations

### 2.1 Simple form (one character at a time)
```text
Insert(pos, char)     insert char before index pos
Delete(pos)           delete the character at index pos
```

### 2.2 The "retain / insert / delete" form (used by ot.js, Quill, many libraries)
An operation walks over the **whole** document from left to right:
```text
retain(n)    keep the next n characters unchanged
insert(s)    insert string s here
delete(n)    delete the next n characters

Document "Hello world" (11 chars)
op = [retain 5, insert ",", retain 6]          → "Hello, world"
op = [retain 6, delete 5, insert "there"]      → "Hello there"
```
The total of `retain + delete` must equal the document length. This form handles multi-character edits and makes `compose` (section 7) easy. The rest of this chapter uses the simple form because it's easier to reason about; the logic is the same.

---

## 3. apply

```text
function apply(doc, op):
    if op is Insert: return doc[0:op.pos] + op.char + doc[op.pos:]
    if op is Delete: return doc[0:op.pos] + doc[op.pos+1:]
    if op is NoOp:   return doc
```

---

## 4. transform: case by case

`transform(a, b)` = "`a`, rewritten to apply **after** `b`". Both `a` and `b` were made on the same document state.

```text
function transform(a, b):
    # 1. insert vs insert
    if a is Insert and b is Insert:
        if a.pos < b.pos: return a
        if a.pos > b.pos: return Insert(a.pos + 1, a.char)
        # same position: tie-break by site ID so every replica decides the same way
        if a.site < b.site: return a
        else:               return Insert(a.pos + 1, a.char)

    # 2. insert vs delete
    if a is Insert and b is Delete:
        if a.pos <= b.pos: return a
        else:              return Insert(a.pos - 1, a.char)

    # 3. delete vs insert
    if a is Delete and b is Insert:
        if a.pos < b.pos: return a
        else:             return Delete(a.pos + 1)

    # 4. delete vs delete
    if a is Delete and b is Delete:
        if a.pos < b.pos: return a
        if a.pos > b.pos: return Delete(a.pos - 1)
        return NoOp                     # both deleted the same character

    # anything vs NoOp: unchanged; NoOp vs anything: NoOp
```

### 4.1 Why each case works
| Case | Reasoning |
|---|---|
| Insert before an insert | Unaffected. |
| Insert after an insert | The other insert added one character before me → shift right by 1. |
| Insert at the same spot | Both can't go "first". Pick by site ID; every replica uses the same rule. |
| Insert at/before a delete | Characters before the deleted one don't move. |
| Insert after a delete | One character before me disappeared → shift left by 1. |
| Delete before an insert | Unaffected. |
| Delete at/after an insert | A character was inserted before mine → shift right. |
| Two deletes of the same char | It's already gone → do nothing. |

### 4.2 Walkthrough: the "cat" example, fixed
```
S = "cat"     Alice: a = Insert(1, 'h')     Bob: b = Delete(2)

Alice has applied a → "chat". She receives b and applies T(b, a):
  b is Delete(2), a is Insert(1): 2 >= 1 → Delete(3)
  "chat" with index 3 deleted → "cha" ✓

Bob has applied b → "ca". He receives a and applies T(a, b):
  a is Insert(1), b is Delete(2): 1 <= 2 → Insert(1, 'h') unchanged
  "ca" → "cha" ✓
```

### 4.3 Walkthrough: concurrent inserts at the same spot
```
S = "ab"   Alice (site A): Insert(1,'X')    Bob (site B): Insert(1,'Y')

Alice: "aXb", then T(Bob's, Alice's): same pos, B > A → Insert(2,'Y') → "aXYb"
Bob:   "aYb", then T(Alice's, Bob's): same pos, A < B → Insert(1,'X') → "aXYb"
Both "aXYb" ✓
```

---

## 5. Convergence properties

### 5.1 TP1 (two operations)
```text
apply(apply(S, a), T(b, a))  ==  apply(apply(S, b), T(a, b))
```
"Going around the diamond either way gives the same document." Every OT system needs TP1. It's easy to test with random documents and random operation pairs.

### 5.2 TP2 (three operations)
With three concurrent operations `a`, `b`, `c`, transforming `c` past `a` then `b'` must equal transforming `c` past `b` then `a'`:
```text
T( T(c, a), T(b, a) )  ==  T( T(c, b), T(a, b) )
```
You need TP2 only if operations can be transformed **along different paths** on different replicas, which happens in **peer-to-peer** OT with no central order.

TP2 is notoriously hard to satisfy. The original 1989 OT algorithm (dOPT) had a known failure case (the "dOPT puzzle"), and several later published transformation functions were found to violate TP2. This is the main reason practical OT systems use a **central server**: with one total order, you only ever need TP1.

---

## 6. The central-server model (Jupiter / Google Wave / ot.js)

### 6.1 Server
```text
doc      = current document
history  = list of ops; history[i] is the op that produced revision i+1
revision = len(history)

on receive (op, baseRev) from client c:
    for each h in history[baseRev:]:        # ops c hadn't seen when it made op
        op = transform(op, h)
    doc = apply(doc, op)
    history.append(op)
    send ack to c
    send op to every other client
```

### 6.2 Client: three states
A client sends **one operation at a time** and waits for its ack. While waiting, new local edits are **buffered** (composed into one op).

```
              local edit                     local edit
 ┌──────────────┐ ───────► ┌─────────────────┐ ───────► ┌──────────────────────┐
 │ Synchronized │          │ AwaitingConfirm │          │ AwaitingWithBuffer   │
 │              │ ◄─────── │  (outstanding)  │ ◄─────── │ (outstanding, buffer)│
 └──────────────┘   ack    └─────────────────┘   ack:   └──────────────────────┘
                                                 send buffer,
                                                 it becomes outstanding
```

```text
state = Synchronized; rev = 0

on local edit op:
    doc = apply(doc, op)
    Synchronized:               send(op, rev);  state = AwaitingConfirm(op)
    AwaitingConfirm(o):         state = AwaitingWithBuffer(o, op)
    AwaitingWithBuffer(o, buf): state = AwaitingWithBuffer(o, compose(buf, op))

on server op s (someone else's edit):
    rev = rev + 1
    Synchronized:
        doc = apply(doc, s)
    AwaitingConfirm(o):
        o' = T(o, s);  s' = T(s, o)
        doc = apply(doc, s');  state = AwaitingConfirm(o')
    AwaitingWithBuffer(o, buf):
        o'   = T(o, s);    s1 = T(s, o)
        buf' = T(buf, s1); s2 = T(s1, buf)
        doc = apply(doc, s2);  state = AwaitingWithBuffer(o', buf')

on ack:
    rev = rev + 1
    AwaitingConfirm(o):         state = Synchronized
    AwaitingWithBuffer(o, buf): send(buf, rev);  state = AwaitingConfirm(buf)
```

Why transform the **outstanding** op too? Because the server will apply it **after** `s`, so the client's record of "what I sent" must match what the server will end up applying. (The server does its own transform; the client is just keeping its local view consistent.)

### 6.3 Walkthrough with revisions
```
Server: "abc" rev 0.   Clients A and B: "abc" rev 0.

A types X at 0 → "Xabc", sends (Insert(0,X), base 0), state AwaitingConfirm
B types Y at 3 → "abcY", sends (Insert(3,Y), base 0), state AwaitingConfirm

Server receives A's first:
  base 0 == rev 0, nothing to transform → "Xabc", rev 1
  ack → A;  Insert(0,X) → B

Server receives B's (base 0):
  transform against history[0:] = [Insert(0,X)]: 3 > 0 → Insert(4,Y)
  → "XabcY", rev 2;  ack → B;  Insert(4,Y) → A

Client B (AwaitingConfirm(Insert(3,Y))) receives Insert(0,X):
  s' = T(Insert(0,X), Insert(3,Y)) = Insert(0,X)   → "XabcY"
  o' = T(Insert(3,Y), Insert(0,X)) = Insert(4,Y)   (matches what the server applied)
  then ack → Synchronized

Client A receives ack → Synchronized; then Insert(4,Y) → "XabcY"

All three: "XabcY" ✓
```

### 6.4 Why one op in flight at a time?
It keeps the server's transform simple: each client op is based on a known revision, and the client always knows exactly which of its ops the server has. ot.js and Google Wave's protocol work this way.

---

## 7. compose

`compose(a, b)` = one operation with the same effect as applying `a` then `b`. Used to merge buffered edits into one before sending.
```
doc "abc":  a = [retain 3, insert "d"]   →  "abcd"
            b = [retain 4, insert "e"]   →  "abcde"
compose(a, b) = [retain 3, insert "de"]
```
This is much easier in the retain/insert/delete form than with single-character ops.

---

## 8. Undo and redo

- Undo of `op` = apply its **inverse** (`Insert` ↔ `Delete`; deletes must remember the deleted text).
- In a shared document, you usually want **"undo my last change"**, not "undo the last change by anyone". Your op may have been followed by others' ops, so the inverse must be **transformed through every op that came after it**, then applied as a **new** operation.
- The server sees undo as just another edit.

---

## 9. Cursors and selections

A cursor is a position, so it must be transformed just like an insert of zero length:
```text
function transformCursor(pos, op):
    if op is Insert and op.pos <= pos: return pos + 1
    if op is Delete and op.pos <  pos: return pos - 1
    return pos
```
When Bob types before your cursor, your cursor moves right with the text. Remote users' cursors (presence, Chapter 9) are transformed the same way.

---

## 10. OT beyond plain text

| Type | Operations | Library |
|---|---|---|
| Rich text | Retain/insert/delete **with attributes** (bold, link) | Quill's Delta format; ShareDB `rich-text` type |
| JSON | Path-based ops: list insert/delete/move, object insert/delete, number add | ShareDB `json0` |
| Trees (HTML/XML/outlines) | Insert/delete/move nodes | Possible but complex (moves are hard) |

Each new operation type multiplies the number of transform cases (every pair of op types needs rules), which is one reason OT gets complicated for rich data.

---

## 11. Where OT is used

- **Google Docs** (OT with a central server per document); Google Wave published its OT protocol.
- **Etherpad** (its "Easysync" changeset format).
- **ShareDB** (Node.js; `json0` and `rich-text` types).
- **CodeMirror 6** collaborative editing: a central authority with OT-style mapping of changes and positions.
- **Firepad** (Firebase + CodeMirror/Ace; now archived).

---

## 12. OT vs CRDT (first look)

| | OT | CRDT |
|---|---|---|
| Positions | Indexes, transformed | Unique IDs per element |
| Needs central server | In practice yes | No |
| Metadata | Small (just ops + revision) | Larger (IDs, tombstones) |
| Offline for a long time | Painful: huge transform chains, server must keep long history | Natural |
| Complexity lives in | Transform functions + server protocol | The data structure |
| Mature rich-text support | Yes (Quill, Google Docs) | Yes now (Yjs, Peritext), newer |

**Pick OT when** you already have a central server, need small metadata, and mostly do online editing. **Pick CRDT when** you need offline or peer-to-peer, or don't want to depend on one server per document. Full comparison in [Chapter 12](12-system-design-interviews.md).

---

## 13. Common mistakes

- Off-by-one in `<` vs `<=` in the insert/delete cases. Test with random TP1 checks.
- Tie-breaking differently on client and server (both must use the same rule, for example site ID).
- Transforming against the wrong slice of history (must be ops **after** the client's base revision).
- Forgetting to transform the outstanding/buffered ops when a server op arrives.
- Applying an ack out of order with remote ops (process messages from the server strictly in order).

---

## Check yourself

<details>
<summary>1. S = "abcd". a = Delete(1), b = Delete(3). What are T(a, b) and T(b, a)? Final text?</summary>

T(a, b): 1 < 3 → Delete(1). T(b, a): 3 > 1 → Delete(2). Path 1: "abcd" → del 1 → "acd" → del 2 → "ac". Path 2: "abcd" → del 3 → "abc" → del 1 → "ac". Both "ac" ✓.

</details>

<details>
<summary>2. Both users delete the same character. What does transform return?</summary>

NoOp. The character is already gone; deleting again would delete the wrong character.

</details>

<details>
<summary>3. Why does Google Docs-style OT need a central server?</summary>

With one server deciding a total order, clients only need TP1. Without it, operations get transformed along different paths on different peers, which requires TP2, and correct TP2 transforms are very hard to design.

</details>

<details>
<summary>4. The client is AwaitingConfirm(o) and receives server op s. What does it apply locally?</summary>

T(s, o), because its local document already includes o. It also replaces its outstanding op with T(o, s).

</details>

<details>
<summary>5. Your cursor is at 5. Someone inserts at 2, then deletes at 7. Where is your cursor?</summary>

Insert at 2 ≤ 5 → cursor 6. Delete at 7 is not < 6 → stays 6.

</details>

---

## Practice

- **Task 12 — OT core** (`ot/`):
  - `Insert`, `Delete`, `NoOp`, `Apply`, `Transform` (simple single-character form first).
  - Scenario tests: every walkthrough in this chapter.
  - **Random TP1 test:** random document, two random valid ops, check both paths give the same text. Run it thousands of times with different seeds.
  - Optional: switch to retain/insert/delete form and add `Compose`.
- **Task 13 — OT editor** (`cmd/editor/` + `web/editor.html`), server on `127.0.0.1`:
  - Server keeps doc + history + revision; implements section 6.1.
  - Browser client with a `<textarea>` implements the state machine in 6.2. (Turning textarea changes into ops: compare old and new text and find the changed range.)
  - Open two tabs, add artificial delay, type at the same spot in both.

---

## Further reading
- Daniel Spiewak, *Understanding and Applying Operational Transformation*.
- Tim Baumann's **ot.js** source code and its interactive visualization of client/server states.
- Ellis & Gibbs, *Concurrency Control in Groupware Systems* (1989), the original OT paper.
- Nichols et al., *High-Latency, Low-Bandwidth Windowing in the Jupiter Collaboration System* (1995), the client-server model.
- Google Wave Federation Protocol OT whitepaper.
- Marijn Haverbeke, *Collaborative Editing in CodeMirror* (blog post).

---

[← 5. CRDT Fundamentals](05-crdt-fundamentals.md) · [Index](README.md) · [7. Sequence CRDTs: Text & Lists →](07-sequence-crdts-text-lists.md)
