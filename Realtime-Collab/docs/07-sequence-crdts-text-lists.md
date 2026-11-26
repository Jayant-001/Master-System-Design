# 7. Sequence CRDTs: Text & Lists

> **In one line:** Give every character a permanent unique ID, describe positions by IDs instead of indexes, keep deleted characters as tombstones, and break ties the same way everywhere.
>
> **You'll learn:** why lists are the hardest CRDT, RGA in depth, the interleaving problem, YATA (Yjs) and Fugue, Logoot/LSEQ dense identifiers, fractional indexing for ordered lists, moving items in lists and trees, rich text, performance tricks used in production, undo, and the main libraries.
>
> **Before this:** [5. CRDT Fundamentals](05-crdt-fundamentals.md) and [6. OT](06-operational-transformation.md) (so you can compare).

**Summary:**
- Indexes shift when others edit; **IDs don't**. Each element gets an ID like `(lamportCounter, replicaID)`.
- **RGA:** "insert after element X". Concurrent inserts after the same X are ordered by ID (higher first). Deletes set a tombstone flag.
- **Interleaving:** a bad algorithm can mix two people's concurrently typed words letter by letter. RGA avoids the common case; **Fugue** is designed to minimize it.
- **Fractional indexing** (Figma): each item has a sortable position key; good for layer lists and kanban boards, not for text.
- Production tricks: **run-length encoding**, **B-trees/ropes** for index lookups, **compact binary encoding**, garbage-collecting deleted content.

---

## 1. Why lists are hard

Sets and counters don't care about order. Text does. And positions change whenever anyone inserts or deletes before them:

```
"ab": a is at 0, b is at 1
Someone inserts X at 0 → "Xab": now a is at 1, b is at 2
```
An instruction like "insert after index 1" means different things on replicas that have seen different edits. OT fixes indexes by transforming them. Sequence CRDTs avoid indexes entirely.

---

## 2. The key idea: stable unique IDs

Every inserted element gets an ID that **never changes** and is **unique across all replicas**:
```text
id = (counter, replicaID)       # counter: a Lamport clock (Chapter 1)
```
Edits refer to IDs:
- "insert `X` **after the element with ID (4, alice)**"
- "delete **the element with ID (7, bob)**"

These mean the same thing on every replica, whatever else has happened.

Two families:
1. **Reference-based** (RGA, YATA, Fugue): "insert after/before element X"; the structure is effectively a tree of "inserted after" links.
2. **Dense identifiers** (Logoot, LSEQ, fractional indexing): each element gets a sortable position value between its neighbors' values.

---

## 3. RGA (Replicated Growable Array)

### 3.1 Data model
A list of nodes, in document order, with a fixed invisible **head** at the start:
```text
node = { id, value, deleted (true/false) }
list = [ head, node, node, ... ]
```
Deleted nodes stay in the list as **tombstones** (`deleted = true`); they're just not shown. They must stay because later inserts may reference them.

### 3.2 Operations
```text
insert(afterID, value):           # done locally, then sent to others
    id = (clock.tick(), myReplicaID)
    integrate(afterID, id, value)
    broadcast Insert(afterID, id, value)

delete(id):
    find node with this id; node.deleted = true
    broadcast Delete(id)

integrate(afterID, id, value):    # used for local AND remote inserts
    pos = position of afterID in list
    pos = pos + 1
    # skip over nodes that were inserted after the same reference
    # concurrently but have a HIGHER id (they go first), and their descendants
    while pos < len(list) and list[pos].id > id:
        pos = pos + 1
    insert node {id, value, deleted: false} at pos
```
IDs are compared as Lamport timestamps: counter first, then replicaID.

Why does "skip while greater" work? Nodes inserted after a higher-ID sibling have even higher counters (they were created later, causally after their parent), so they get skipped too. The scan stops at the first node with a smaller ID, which belongs to an earlier or lower-priority branch.

### 3.3 Walkthrough: concurrent inserts at the same spot
```
Doc "ab":  head → a(1,A) → b(2,A)

Alice inserts X after a:  id (3,A)
Bob   inserts Y after a:  id (3,B)      concurrent.  (3,B) > (3,A) because B > A.

Replica 1 receives X then Y:
  X after a: next is b(2,A) < (3,A) → stop → a X b
  Y after a: next is X(3,A) < (3,B) → stop → a Y X b

Replica 2 receives Y then X:
  Y after a: next is b(2,A) < (3,B) → stop → a Y b
  X after a: next is Y(3,B) > (3,A) → skip; next b(2,A) < (3,A) → stop → a Y X b

Both: "aYXb" ✓  (arrival order didn't matter)
```

### 3.4 Walkthrough: two people type words at the same spot
```
Doc "ab". Alice types "Hi" after a; Bob types "Yo" after a, concurrently.

Alice: H(3,A) after a, then i(4,A) after H
Bob:   Y(3,B) after a, then o(4,B) after Y

A replica that has "a H i b" receives Bob's ops:
  Y(3,B) after a: next H(3,A) < (3,B) → stop → a Y H i b
  o(4,B) after Y: next H(3,A) < (4,B) → stop → a Y o H i b

Result: "aYoHib" → "Yo" and "Hi" stay whole ✓ (no interleaving)
```
Each word is a chain of "insert after my previous character", so each word stays together.

### 3.5 Deletes
```
"aYoHib": Bob deletes H (id (3,A)) → H.deleted = true → shows "aYoib"
Alice concurrently inserts "!" after H → still works: H is still in the list as a tombstone
```

### 3.6 Bridging to a real editor (indexes ↔ IDs)
Editors work with indexes ("user typed at index 4"). Convert:
- **index → ID:** walk the list, counting only visible nodes, until the 4th.
- **ID → index:** walk until you find the ID, counting visible nodes.

Walking is O(n). Real libraries keep a **tree with counts** (B-tree or rope) so both directions are O(log n) (section 11).

---

## 4. The interleaving problem

If two users type at the same spot, a bad algorithm can produce:
```
Alice types " Alice", Bob types " Bob" at the end of "Hello", concurrently.
Interleaved result:  "Hello AB lobice"     ✗ unreadable
Wanted:              "Hello Alice Bob"  or  "Hello Bob Alice"   ✓
```
- **Logoot/LSEQ** (dense identifiers, section 7) are prone to this.
- **RGA** keeps forward-typed words together (section 3.4), but has edge cases (for example, text typed by moving the cursor backward).
- **YATA (Yjs)** handles common cases well.
- **Fugue** (Weidner & Kleppmann, 2023) was designed to minimize interleaving and proves it for both forward and backward typing.

Kleppmann et al. documented these anomalies in *Interleaving anomalies in collaborative text editors* (2019).

---

## 5. YATA (used by Yjs)

Each inserted item remembers **two neighbors at the time of insertion**:
```text
item = {
  id:          (clientID, clock)
  origin:      ID of the item to my LEFT when I was inserted
  rightOrigin: ID of the item to my RIGHT when I was inserted
  content, deleted
}
```
When integrating an item, the algorithm scans the items between `origin` and `rightOrigin`, and uses a set of rules (comparing origins, and client IDs for ties) to decide the position. Knowing **both** neighbors gives it more information than RGA's single reference, which helps keep runs of text together.

Yjs also **merges consecutive items** typed by the same client into one item (run-length encoding): typing "hello" creates one item with content "hello", not five.

You don't need to implement YATA. Knowing that Yjs = YATA, with left and right origins, plus run-length items, is enough for interviews and reading Yjs internals.

---

## 6. Fugue (brief)

Fugue treats the document as a **tree**: each new character becomes a **left child or right child** of an existing character, depending on where it was typed. Reading the tree in order gives the text. The rules are designed so that concurrently typed runs never interleave, whether typed forward or backward. Loro's text type is based on Fugue.

---

## 7. Dense identifiers: Logoot and LSEQ

### 7.1 The idea
Give each element a **position number** from a dense space (you can always find a number between any two). Sort by position.
```
a at 0.2, b at 0.4
insert between a and b → pick 0.3
insert between a and 0.3 → pick 0.25
```
Real implementations use **lists of integers** (like digits) plus a replica ID, so positions never run out and ties are broken.

### 7.2 Walkthrough of the interleaving issue
```
a at 0.2, b at 0.4. Alice types "Hi" between them; Bob types "Yo" between them.
Both pick the midpoint for their first letter, and the next midpoint for their second:

  H = (0.3, A)   i = (0.35, A)
  Y = (0.3, B)   o = (0.35, B)

Sorted: (0.3,A) H, (0.3,B) Y, (0.35,A) i, (0.35,B) o  →  "aHYiob"   ✗ interleaved
```

### 7.3 Pros and cons
| | |
|---|---|
| ✓ | No tombstones needed for ordering (delete just removes the element) |
| ✓ | Simple to understand: sort by position |
| ✗ | Identifiers **grow** when many inserts happen at the same spot (0.3, 0.35, 0.375, ...). LSEQ uses a clever allocation strategy to slow this growth. |
| ✗ | Interleaving |

---

## 8. Fractional indexing for ordered lists (not text)

For lists of **objects** (Figma layers, kanban cards, todo items, Excalidraw z-order), each item stores a **position key**, and the list is sorted by key. Figma described this in *Realtime editing of ordered sequences*.

```text
keyBetween(a, b): return a key that sorts strictly between a and b
                  (keys are strings, compared character by character)

list:  "a0" Task A
       "a1" Task B
move Task C between A and B → C.position = keyBetween("a0", "a1") = "a0V"
```
- **A move is one write** to one property (`position`), so it can be an LWW-Register. Concurrent moves don't duplicate the item (unlike delete + insert).
- **Ties:** two users might generate the same key at the same spot. Add a small random suffix or the client ID to make keys unique.
- **Your Excalidraw file uses this:** every element has an `"index"` field like `"a0"`, `"a1"`, `"aCV"`. That's a fractional index for z-order (which element is drawn on top).
- **Not for text:** no interleaving guarantees, and keys grow with repeated inserts at the same spot.

---

## 9. Moving items

### 9.1 In lists
Implementing "move" as delete + insert breaks under concurrency: if two users move the same item to different places at once, you get **two copies**.

Fix: keep the item's identity and store its **position as an LWW-Register** (a fractional index, or a reference to a list position). Concurrent moves → one wins, no duplicates. (Kleppmann, *Moving elements in list CRDTs*, 2020.)

### 9.2 In trees (folders, outlines, Notion blocks, Figma frames)
```
root
 ├── A
 └── B
Alice moves A under B.  Bob moves B under A.   (concurrent)
Apply both naively:  A → B → A   a cycle, detached from root ✗
```
Fixes:
- **Server rejects** moves that would create a cycle (Figma does this).
- **CRDT approach** (Kleppmann et al., *A highly-available move operation for replicated trees*, 2021): order all moves by timestamp; apply them in that order, **skipping** any move that would create a cycle. When a move with an earlier timestamp arrives late, undo later moves, apply it, and redo the later ones.

---

## 10. Rich text

Formatting (bold, italic, links, comments) is a **span** over characters. Store spans with **anchors to character IDs**, not indexes, so they survive concurrent edits.

Hard questions rich text must answer:
```
"The fox" with "fox" in bold. Alice types "es" right after "fox".
  Should "es" be bold?  For bold: yes (formatting expands at the end).
  For a link: no (links shouldn't grow when you type after them).

Alice bolds "The fox"; Bob concurrently italicizes "fox jumps".
  Result: "fox" is bold AND italic; "The " only bold; " jumps" only italic.
```
**Peritext** (Ink & Switch, 2022) is a rich-text CRDT that answers these with per-mark expansion rules. Yjs represents formatting as special marker items inside `Y.Text`.

---

## 11. Performance at scale

Naively, every character carries an ID (often 16+ bytes) plus pointers. A 100-page document would carry megabytes of metadata. Production libraries use:

| Technique | Idea |
|---|---|
| **Run-length encoding** | Consecutive characters typed by one user get consecutive IDs → store one item: `{startID, "hello"}` |
| **Tree indexes (B-tree / rope)** | Each internal node stores the visible length of its subtree → index ↔ ID in O(log n) |
| **Binary columnar encoding** | Store IDs, origins, and content in separate compressed columns (Automerge's file format) |
| **Garbage-collecting content** | Deleted items keep their ID and length but drop their text (Yjs does this unless you need full version history) |
| **Lazy loading** | Load only what the user is viewing |

Seph Gentle's *5000x faster CRDTs: An Adventure in Optimization* walks through these improvements on a real editing trace (a recording of every keystroke while writing an academic paper), going from minutes to milliseconds.

---

## 12. Undo and redo in CRDTs

- History is never rewritten. **Undo = a new operation** that reverses the effect: undo an insert by deleting those items; undo a delete by re-inserting the content.
- Users expect **"undo my own changes"**. Track which operations came from the local user and undo only those.
- Yjs provides an `UndoManager` that tracks changes from chosen origins (for example, "only local edits").

---

## 13. Libraries you'll meet

| Library | Algorithm | Language | Notes |
|---|---|---|---|
| **Yjs** | YATA | JavaScript (Rust port: y-crdt / yrs, with bindings) | Most widely used; editor bindings for ProseMirror/Tiptap, CodeMirror, Monaco, Quill |
| **Automerge** | RGA-based lists/text, JSON document model | Rust core, JS/WASM and other bindings | Full history, hash-based change graph |
| **Loro** | Fugue (text) and others | Rust core, JS/WASM | Also supports movable lists and trees |
| **Diamond Types** | Optimized sequence CRDT | Rust | By Seph Gentle; research into speed |

---

## 14. Interview angle

- **"How does a text CRDT handle concurrent typing at the same position?"** Unique IDs per character; insert-after-reference; deterministic tie-break by ID; deletes are tombstones.
- **"What's the cost compared to OT?"** Per-character metadata and tombstones; mitigated by run-length encoding, tree indexes, compression, and GC.
- **"How would you implement drag-to-reorder in a shared kanban board?"** Fractional index per card stored as an LWW property; tie-break with client ID; occasionally rebalance keys if they get long.
- **"Moving folders concurrently?"** Detect cycles: server rejects, or timestamp-ordered moves skipping cycle-creating ones.

---

## 15. Common mistakes

- Physically removing deleted nodes (later inserts referencing them break).
- Comparing IDs inconsistently between local and remote integration.
- Implementing move as delete + insert.
- Using fractional indexing for text.
- O(n) index lookups on large documents.

---

## Check yourself

<details>
<summary>1. Why can't RGA physically remove a deleted character?</summary>

Another replica may concurrently insert something "after" that character. If it's gone, the reference can't be resolved. Keeping a tombstone keeps every reference valid.

</details>

<details>
<summary>2. Doc "ab" = a(1,A) b(2,A). Concurrent: X(5,A) after a, Y(5,C) after a, Z(4,B) after a. Final text?</summary>

Siblings after a are ordered by ID, highest first: (5,C) Y, (5,A) X, (4,B) Z. Then b. Result: "aYXZb".

</details>

<details>
<summary>3. Why does fractional indexing make "move" safe?</summary>

A move changes one property (the position key) of the same item. With LWW, concurrent moves resolve to one winner. Delete + insert would create two copies when two people move the same item concurrently.

</details>

<details>
<summary>4. What is the interleaving anomaly?</summary>

When two users type text at the same position concurrently, some algorithms mix their characters together ("HYio" instead of "HiYo"), making both texts unreadable.

</details>

<details>
<summary>5. How do production libraries avoid per-character metadata blowing up?</summary>

Run-length encoding of consecutive characters from the same user, tree indexes for fast lookup, compact binary columnar encoding, and dropping deleted content while keeping minimal tombstone metadata.

</details>

---

## Practice

- **Task 14 — RGA** (`crdt/rga.go`):
  - `Insert(afterID, value)` and `Delete(id)` producing ops; `Integrate(op)` for remote ops; `String()` for visible text; `IndexToID` / `IDToIndex`.
  - Scenario tests: sections 3.3, 3.4, 3.5.
  - Random convergence test: 3 replicas, random inserts/deletes at random visible positions, random delivery through `sim/` (op-based, so deliver each op once and in causal order, or add a hold-back queue).
- **Task 15 — CRDT editor:** a second version of your editor using RGA. Either compile the Go package to WebAssembly (`GOOS=js GOARCH=wasm`) for the browser, or reimplement RGA in JS for the client. The server just relays and stores ops.
- **Optional:** fractional index `KeyBetween(a, b)` with tests (between, at start, at end, many inserts at the same spot).

---

## Further reading
- Martin Kleppmann, *CRDTs: The Hard Parts* (talk).
- Seph Gentle, *5000x faster CRDTs: An Adventure in Optimization*; also *I was wrong. CRDTs are the future*.
- Weidner & Kleppmann, *The Art of the Fugue: Minimizing Interleaving in Collaborative Text Editing* (2023).
- Roh et al., *Replicated abstract data types: Building blocks for collaborative applications* (2011), RGA.
- Nicolaescu et al., *Near Real-Time Peer-to-Peer Shared Editing on Extensible Data Types* (2016), YATA.
- Kleppmann et al., *Interleaving anomalies in collaborative text editors* (2019).
- Figma blog, *Realtime editing of ordered sequences*.
- Ink & Switch, *Peritext: A CRDT for Rich-Text Collaboration* (2022).
- Kleppmann et al., *A highly-available move operation for replicated trees* (2021).

---

[← 6. Operational Transformation](06-operational-transformation.md) · [Index](README.md) · [8. Sync, Storage & Offline →](08-sync-storage-offline.md)
