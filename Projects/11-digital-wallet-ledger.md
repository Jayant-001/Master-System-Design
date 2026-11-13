# 11: Digital Wallet and Ledger

> Ticket Drop (01) taught you not to oversell and not to double-charge. Here the stakes are higher. You're the system that **holds** the money. A bug doesn't just upset a customer. It creates or destroys money, and auditors will find it.

---

## The story

You're building **Paywise** (fictional), a wallet app. Users add money from their bank, send money to friends, pay merchants, and withdraw back to their bank. Merchants receive thousands of payments per second.

The rules:
- Money is **never created or destroyed** by a bug.
- A user's balance **never goes below zero**.
- Every change can be **explained** later: who, what, when, and why.
- Your numbers must **match the bank's numbers** every day.

## How it works (plain words)

- Every account (user, merchant, and internal accounts like "fees" or "bank clearing") has a list of **entries**.
- Every money movement is a **transfer** made of entries that **add up to zero**: take ₹100 from Alice (−100), give ₹100 to Bob (+100). This is **double-entry bookkeeping**, which accountants have used for 500 years.
- **Balance** = the sum of an account's entries. You can also keep a saved balance for speed, but it must always equal that sum.
- Entries are **never edited or deleted**. A mistake is fixed by adding a new correcting entry.

## What you'll learn

Double-entry bookkeeping · immutable ledgers · money types (no floats) · preventing negative balances under concurrency · deadlocks between accounts · holds (authorize, then capture) · hot accounts · sharding a ledger · transfers across shards (2PC vs sagas) · in-transit money · reconciliation with external files · reversals · currency conversion and rounding · point-in-time balances · tamper-evident logs.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Account** | A bucket of money: a user's wallet, a merchant, or an internal account. |
| **Entry (posting)** | One line: "account X, +₹100" or "account Y, −₹100". |
| **Transfer** | A group of entries that add up to zero. |
| **Double-entry** | Every transfer touches at least two accounts and sums to zero. |
| **Debit / credit** | The two sides of an entry. (You can just use − and + in this project.) |
| **Hold / authorization** | Reserving money now and taking it later, like a hotel deposit. |
| **Capture** | Actually taking held money. |
| **Settlement** | When the bank actually moves the money, often a day or more later. |
| **Reconciliation** | Comparing your records with someone else's (the bank's) and explaining every difference. |
| **Hot account** | One account with a huge number of transfers, like a big merchant. |
| **Saga** | A multi-step process where each step can be undone if a later step fails. |
| **2PC** | Two-Phase Commit: "everyone prepare, then everyone commit". |

## Scope

| In | Out |
|---|---|
| Accounts, transfers, holds, top-ups/withdrawals, sharding, reconciliation, FX | KYC/identity, real banks, cards |
| A **fake bank** that behaves like a real one (slow, late, sometimes wrong) | Mobile app UI |

**Tech:** Java or Go, Postgres (several instances for shards in L3), Docker Compose, k6.

---

## Base system spec

### Functional requirements
1. Create accounts: `user`, `merchant`, and `system` (bank clearing, fees, FX, in-transit...).
2. Transfer money between accounts, with an **idempotency key** (reuse what you learned in project 01 L3).
3. Get an account's balance and its statement (history of entries).
4. Top up from the bank, and withdraw to the bank.
5. Rules: user and merchant accounts can **never** go negative. System accounts may.

### API contract

```
POST /accounts                {type: "user"|"merchant"|"system", currency, ownerId}  -> {accountId}
POST /transfers               {from, to, amountMinor, currency, idempotencyKey, memo}
                                -> 201 {transferId, status} | 409 {error: "INSUFFICIENT_FUNDS"}
GET  /accounts/{id}/balance   -> {ledgerBalance, availableBalance}         (available from L1)
GET  /accounts/{id}/entries?cursor=                                         -> statement
POST /topups                  {accountId, amountMinor, bankRef}
POST /withdrawals             {accountId, amountMinor, bankAccount}
```

> `amountMinor` = the amount in the smallest unit (paise, cents), as an **integer**.

### Fake bank (build it first)

| Behavior | Knob |
|---|---|
| Payouts take time to settle | `SETTLE_DELAY` |
| Some payouts fail **days later** and the money comes back | `LATE_FAIL_RATE` |
| A daily **settlement file** (CSV) with the bank's view of every movement | always |
| The file sometimes has a duplicate line, a missing line, or a different amount | `FILE_ERROR_RATE` |
| Cutoff time: anything after 23:00 shows up in tomorrow's file | `CUTOFF` |

### Books checker (your referee)

| ID | Invariant | From |
|---|---|---|
| L1 | Sum of **all** entries = 0 (per currency) | L0 |
| L2 | Every account's saved balance = sum of its entries | L0 |
| L3 | No user/merchant account was **ever** negative (check every point in history, not just the end) | L0 |
| L4 | Every transfer's entries sum to 0 | L0 |
| L5 | No transfer applied twice (same idempotency key → one transfer) | L0 |
| L6 | Holds: available balance = ledger balance − active holds, never negative | L1 |
| L7 | After reconciliation, every difference with the bank file is matched or explained | L4 |

### Results table: `docs/results.md`

| Level | Scenario | Transfers/sec | p50 | p99 | Conflicts/retries | L1 | L2 | L3 | L4 | L5 | L6 | L7 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: A double-entry ledger in one database

**Incident:** None. Build accounts, transfers, and balances on one Postgres.

**Done when:**
- [ ] Every transfer writes balanced entries. The books checker passes.
- [ ] **Concurrency test:** 1,000 virtual users sending random transfers between 100 accounts. L1-L5 all pass, 5 runs in a row.
- [ ] **Deadlock test:** many transfers A→B and B→A at the same time. No errors leak to users, and you can explain what happens.

**Predict first:** With 1,000 concurrent users and only 100 accounts, will you see deadlocks? How often?

**Design questions:**
1. Store a balance column, compute the balance from entries every time, or both? What does each cost for reads, writes, and correctness?
2. How do you stop a balance going negative when two transfers out of the same account run at the same time? (Project 01 L1. Which strategy fits here, and why?)
3. Transfer A→B locks A then B. Transfer B→A locks B then A. What happens? How do you prevent it? (Your `Concurrency/TicketMaster` sorted-lock trick: does it work in Postgres too?)
4. Why must money never be a floating-point number? Show a real example where floats go wrong.
5. Why should entries never be updated or deleted? How do you fix a mistake then?

**Search:** `double-entry bookkeeping for engineers`, `immutable ledger design`, `money as integer minor units`, `floating point money rounding`, `Postgres deadlock detection`, `lock ordering`, `SELECT FOR UPDATE`.

**Self-evaluation:**
- 🟢 L1-L5 pass under load, and you can explain your negative-balance protection.
- 🔴 You check the balance in one statement and write in another, without protection.

---

## Level 1: Holds (authorize now, capture later)

**Incident:** A ride app charges at the start of a ride, but the final price is only known at the end. The user starts a ride with ₹300, then quickly sends ₹300 to a friend before the ride ends. The ride can't be paid.

**Done when:**
- [ ] **Authorize** (hold an amount), **capture** (full or partial), **void** (release), and **expire** (auto-release after T).
- [ ] Two balances: **ledger balance** (settled) and **available balance** (minus active holds).
- [ ] L6 passes under concurrency: holds, captures, and transfers racing on the same account.
- [ ] Race test: capture arrives at exactly the moment the hold expires (project 01 L4 again).

**Predict first:** Where will the hardest race be?

**Design questions:**
1. Is a hold an entry in the ledger, or a separate record? What does each choice mean for L1 (sum = 0)?
2. How do you compute available balance quickly for an account with 50 active holds?
3. Partial capture: hold ₹300, capture ₹220. What entries are written? What happens to the remaining ₹80?
4. Capture after expiry: allow it, reject it, or something else? What do card networks do?
5. Can the same hold be captured twice? How do you prevent it?

**Search:** `authorization and capture payments`, `pending vs posted transactions`, `available balance vs ledger balance`, `hold expiry`.

**Self-evaluation:**
- 🟢 The race test runs 1,000 times with 0 L6 violations.
- 🔴 Available balance is computed by scanning all entries ever.

---

## Level 2: The hot account

**Incident:** **MegaMart** runs a sale and receives **10,000 payments per second**. Every payment updates MegaMart's balance row. Transfers queue up behind that one row, p99 latency goes to seconds, and payments start timing out.

**Done when:**
- [ ] MegaMart receives payments at the target rate. p99 is under your target.
- [ ] All invariants still pass. You define **when** MegaMart's balance must be exact (instantly? within 1s?) and prove it.
- [ ] Before/after numbers, with **evidence** of where time went before the fix (lock waits).

**Predict first:** With one balance row, what's the max payments/sec into one account?

**Design questions:**
1. Does **receiving** money need the same protection as **sending** it? Can a credit ever make a balance negative?
2. Split the account into sub-accounts (sharded balance, as in project 01 L2). How do you read the total? How do you **pay out** from a split account without going negative?
3. Write entries right away but update the saved balance in batches (every 100 ms). What does MegaMart see in between? Is that OK?
4. Which accounts truly need strict, one-at-a-time updates, and which don't?
5. What if the hot account is one that **sends** a lot, such as a payroll account paying 50,000 salaries?

**Search:** `hot account problem ledger`, `sharded counters`, `write batching`, `append-only entries async balance`, `lock contention`, `pg_stat_activity wait events`.

**Self-evaluation:**
- 🟢 You can say exactly how stale MegaMart's balance can be, and why that's safe.
- 🔴 The fix allows a sending account to go negative in rare races.

---

## Level 3: Sharding and transfers across shards

**Incident:** 50M accounts. One Postgres can't keep up, so you split accounts across 4 databases. Now a transfer from an account on shard 1 to an account on shard 3 **can't be one database transaction**. A crash in the middle leaves money **missing** (taken from Alice, never given to Bob) or **duplicated**.

**Done when:**
- [ ] 4 shards. Accounts are routed by ID.
- [ ] Cross-shard transfers work.
- [ ] **Chaos test:** randomly `kill -9` the transfer coordinator mid-transfer. After recovery, **L1 holds globally** (sum of all entries across all shards = 0).
- [ ] Money "on the way" is always visible somewhere: an in-transit account.
- [ ] Half-finished transfers are finished or undone **automatically** after a crash.

**Predict first:** Which crash point loses money in a naive "debit shard 1, then credit shard 3" design?

**Design questions:**
1. **2PC:** how does it work? What happens if the coordinator dies after everyone said "prepared"? Why is that called "blocking"?
2. **Saga:** debit shard 1, then credit shard 3. If step 2 fails, undo step 1. Where is the money between the two steps? What does Alice see? What does Bob see?
3. Every step will be retried after a crash. How is each step made safe to repeat?
4. Who notices a half-finished transfer after a crash and continues it? (Project 12, the workflow engine, is built for exactly this.)
5. Can a saga's "undo" step itself fail? Then what?
6. How do you check L1 across 4 databases at one moment, when transfers are always in flight?

**Search:** `two-phase commit blocking problem`, `saga pattern orchestration vs choreography`, `suspense account in-transit account`, `idempotent saga steps`, `transactional outbox`, `distributed snapshot consistency`.

**Self-evaluation:**
- 🟢 Chaos test, 5 runs: L1 holds globally, and nothing is stuck.
- 🔴 Recovery needs a human to look at logs.

---

## Level 4: Reconciliation with the bank

**Incident:** Finance: "The bank's file says we paid out **₹12,03,442.18** yesterday. Our ledger says **₹12,04,017.18**." Three payouts were reported as failed by the bank **two days later**, one line in the bank file is a duplicate, and one payout at 23:05 isn't in the file at all.

**Done when:**
- [ ] A daily job imports the bank's CSV file.
- [ ] It matches bank lines to your transfers, and every line ends up in one of: **matched**, **missing in bank**, **missing in ledger**, **amount mismatch**, **duplicate**.
- [ ] Late failures are handled automatically: the money goes back to the user with **reversal entries**.
- [ ] Importing the same file twice gives the same result (no double reversals).
- [ ] A daily report that a finance person could read.

**Predict first:** Which category will be the hardest to match automatically?

**Design questions:**
1. What's your **matching key**? What if the bank's line has no reference ID, only amount and date?
2. **Cutoff and time zones:** a payout at 23:05 appears in tomorrow's file. How do you avoid flagging it as missing?
3. When your ledger and the bank disagree, who is right? Does it depend on the case?
4. How do you correct a mistake in an immutable ledger?
5. How do you make the file import safe to run twice?

**Search:** `payment reconciliation`, `bank settlement file`, `three-way reconciliation`, `reversal entries accounting`, `cutoff time settlement`, `idempotent file import`.

**Self-evaluation:**
- 🟢 Every rupee of difference in your test file is explained in the report.
- 🔴 Mismatches are "fixed" by editing entries.

---

## Level 5: Currencies, rounding, and the auditor

**Incident:** (a) Users send USD to EUR wallets. After 1,000 small conversions, the books are off by a few cents, and L1 fails. (b) An auditor asks: "Show me account X's balance on 3 March at 14:00, and every change since. Prove nobody edited history."

**Done when:**
- [ ] Currency conversion goes through **FX accounts** with an explicit rate, recorded on the transfer.
- [ ] A written **rounding rule**. Rounding leftovers go to a dedicated account, and L1 holds **per currency**.
- [ ] **Point-in-time balance** for any account at any time, fast even with 10M entries.
- [ ] A **tamper-evident** log: if anyone edits an old entry directly in the database, a check detects it.

**Predict first:** After 1,000 conversions of $0.01 each, how many cents off will a naive design be?

**Design questions:**
1. A cross-currency transfer: which entries in which currencies? How does L1 stay true **per currency**?
2. Where does a rounding difference go? Who "owns" it?
3. Which exchange rate do you use, captured when? What if the rate changes between quote and execution?
4. Point-in-time balance: scanning 10M entries is slow. What could you store to make it fast?
5. How can a chain of hashes make history tamper-evident? What can it **not** protect against?

**Search:** `currency conversion rounding`, `banker's rounding`, `FX account ledger`, `point-in-time balance query`, `balance snapshots`, `hash chain tamper-evident log`, `Merkle tree audit log`.

**Self-evaluation:**
- 🟢 You edited an old entry by hand in SQL, and your check caught it.
- 🔴 Amounts are converted with floating point "just for the calculation".

---

## Graduation: answer out loud
1. Design a wallet ledger. Why double-entry?
2. How do you prevent negative balances under heavy concurrency?
3. A merchant gets 10k payments/sec. What's the problem, and what are your options?
4. Transfer across shards: 2PC or saga? Where is the money in between?
5. Walk me through daily reconciliation with a bank.
6. How do you prove to an auditor that history wasn't changed?

## Taking it further
A P2P payments app with a simple UI · merchant dashboard with payouts · card-style authorization flows · scheduled and recurring payments (using project 12) · an open-source ledger library with your invariant checker built in.
