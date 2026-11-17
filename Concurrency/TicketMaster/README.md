# Ticket Booking (Flash Sale): Concurrency Notes

Read this before an interview. The code has the final version. This file has the ideas, options and traps.

## Problem

We're building the booking engine for a stadium concert. Tickets go on sale at 10:00 AM. The stadium has 50,000 seats, and at 10:00:00 about 200,000 users hit "Book" at the same moment.

Functional requirements:

1. The stadium has sections, rows and seats. Every seat has an ID like A-12-7 (section A, row 12, seat 7).
2. Each seat is AVAILABLE, BOOKED, or HELD. HELD is not built yet.
3. Users can:
    - `getAvailableSeats(section)`: browse the free seats in a section.
    - `bookSeat(userId, seatId)`: book one seat.
    - `bookSeats(userId, List<seatId>)`: book several seats together. All of them or none.
    - `cancelBooking(userId, seatId)`: release a seat the user booked.
4. Everything runs in memory in one JVM. No database.

Non-functional requirements:

1. A seat must never be sold to two people. This is the hard rule.
2. Booking must be fast under heavy load.
3. Browsing traffic is far larger than booking traffic.

## How to run

- `./script.sh` compiles and runs `Main`.
- `Seat.book()` sleeps 2s (demo only). The timings show who waited for whom.
- Each test in `Main` says what it proves. The expected output is next to each print.

| File | Job |
|---|---|
| `model/Seat.java` | Seat state and its own lock. `book()` and `release()` are the only writers. |
| `SeatManager.java` | Read-only map: seat ID -> Seat. |
| `BookingService.java` | Public API. `bookSeats` locks many seats in sorted order. |
| `Main.java` | 7 tests. |

## 1. The ladder: what broke, what fixed it

| # | Version | What went wrong | Fix |
|---|---|---|---|
| 1 | Naive: check, then book | Two users both pass the check. Both get `true`. Seat sold twice. | Put a lock around check + book. |
| 2 | `synchronized` on every method | Correct, but one lock for all seats. A-1-2 waits for A-1-3. Test 2 took ~4s. | One lock per seat. |
| 3 | `synchronized(seat)`, check OUTSIDE the block | Still sold twice. 10 threads booked A-1-1 ten times. Took 20s. | Move the check inside the lock. |
| 4 | `synchronized(seat)`, check INSIDE | Correct and parallel. Test 2 took ~2s. | - |
| 5 | Browse reads status with no lock | Reader may never see the new status (visibility bug). | Make `status` `volatile`. |
| 6 | `bookSeats` locks in the order the user asked | Deadlock. Alice holds A-2-1, wants A-2-2. Bob holds A-2-2, wants A-2-1. | Sort IDs. Lock in that order. |
| 7 | `bookSeats` used `synchronized`, `bookSeat` used `ReentrantLock` | Two different locks. Both users got A-2-1. | One lock type for every method. |
| 8 | Lock taken in `BookingService` | Anyone calling `seat.book()` directly skips the lock. | Move the lock into `Seat`. |
| 9 | A write fails halfway | `bookedBy` is set, status is not. Unlock does not undo writes. | Save old values. Restore in catch. Rethrow. |

## 2. Rules to remember

1. Check and act inside the SAME lock.
2. A lock only protects code that takes it. Every writer must take the same lock.
3. The lock object decides WHO waits. The code inside the lock decides HOW LONG they wait.
4. Data that changes together lives together (`status` + `bookedBy` in `Seat`). Then there is one thing to protect.
5. Don't hand out objects callers can change. Return IDs. Keep setters private.
6. Write the "done" flag last (`status = BOOKED`). If an earlier write fails, the seat is still sellable.
7. Unlock does not undo writes. Restore the state yourself.
8. Stale reads are fine if the write path checks again under the lock.

## 3. Why the check must be inside the lock

Check INSIDE the lock (correct):

```
0s   10 threads reach the lock. 1 gets in.
     winner: check -> AVAILABLE -> sleep -> book
2s   winner leaves.
     next:   check -> BOOKED -> false (fast)
     the other 8 do the same, a few ms each
     Result: 1 booking, ~2s
```

Check OUTSIDE the lock (broken):

```
0s   all 10 check -> AVAILABLE
     (nothing is booked yet)
     all 10 wait at the lock,
     each has already decided "book"
2s   1st books
4s   2nd books, overwrites bookedBy
...
20s  10th books
     Result: 10 bookings, 20s
```

The lock made them take turns. It did not stop the wrong decision.

## 4. Options and tradeoffs

### Which lock?

| Option | Good | Bad | Use when |
|---|---|---|---|
| `synchronized` method | Simplest. Cannot forget to unlock. | Locks `this`. All seats share one lock. | Low traffic. |
| `synchronized(seat)` block | Per seat. Cannot leak. | Waits forever. No timeout. Holding N locks needs nesting. | One object at a time. |
| `ReentrantLock` per seat (chosen) | `tryLock(timeout)`, `lockInterruptibly()`, fairness, lock/unlock in a loop. | Must unlock in `finally`. Forget once = seat locked forever. | Need timeout or N locks. |
| `AtomicReference<SeatState>` + `compareAndSet` | No lock. Check + swap in one CPU step (CAS). | One object per CAS. All-or-none across seats is hard. Retries under contention. | Single-key state change. |
| `ConcurrentHashMap.replace(key, old, new)` | CAS per key, built in. | Works only if the state IS the map value. | State lives in the map. |

Notes:

- "Reentrant" is not an advantage over `synchronized`. Both are reentrant.
- Fairness: `new ReentrantLock(true)` gives the lock to the longest waiter. It is slower. The default is unfair, so the winner changes from run to run.

### Visibility for browsing (read-heavy)

| Option | Good | Bad |
|---|---|---|
| No lock, no `volatile` | Fast | Bug: the reader may never see the new value. |
| `synchronized` getter | Correct | Every browse waits behind bookings. Browsing is most of the traffic. |
| `volatile` field (chosen) | Latest value. Nobody waits. | Visibility only, not atomicity. Two volatile fields are read separately, so a reader can see them out of step. |
| `ReentrantReadWriteLock` | Many readers at once. Consistent multi-field read. | Readers block writers. More code. Writers can starve. |
| One immutable `SeatState` in a `volatile` / `AtomicReference` | One read gives status and owner together. | New object on every change. |

Trap: `volatile` does NOT make check-then-act safe. `book()` still needs the lock.

### Seat storage

| Option | Note |
|---|---|
| `List` | Scans every seat on each lookup. Bad for 50K seats. |
| `HashMap` (chosen) | O(1). Safe ONLY because it is built once, the field is `final`, and `Map.copyOf` makes it read-only. |
| `ConcurrentHashMap` | Needed if seats are added or removed at runtime. |
| A second map just for locks | Not needed. Two maps to keep in sync. Put the lock inside `Seat`. |

The seats inside the map change. The map itself does not. Each seat's own lock protects its fields.

### Locking many seats (`bookSeats`)

| Option | Good | Bad |
|---|---|---|
| One global lock | Simple. No deadlock. | No parallelism. |
| Nested `synchronized` via recursion (Option B, commented in `BookingService`) | Works with plain monitors. | No timeout. Hard to read. Recursion depth = seat count. |
| `ReentrantLock` loop + `locked` list (chosen) | Plain loop. Unlock only what you hold. | Must sort. Must unlock in `finally`. |
| `tryLock(timeout)` loop (not built) | No long waits. No deadlock even without sorting. | Caller must retry. Threads that back off and retry together can keep failing (livelock). |

### Failure in the middle of a write

| Option | Good | Bad |
|---|---|---|
| Order the writes, "done" flag last | No extra code. | Can leave a stale field. Harmless if the next write overwrites it. |
| Revert in catch + rethrow (chosen) | Seat returns to its exact old state. | The revert can fail too. Must run under the lock. |
| Immutable state swap (one write) | Readers see all old or all new. | Still needs a lock or CAS for check-then-act. |
| DB transaction | All or nothing for free. | Not in memory. |

## 5. Deadlock

- It needs a cycle: each thread holds one lock and waits for a lock the other holds.
- Fix: one global order. Sort seat IDs. Lock in that order.
- Why it works: the loser waits at the FIRST lock while holding nothing. No cycle can form.
- Proof in this repo: remove the sort and test 4 hangs. `jstack` prints "Found one Java-level deadlock".
- A test can pass without the sort by luck, if the IDs happen to come back in the same order. Keep the sort.
- Other fix: `tryLock(timeout)` on each lock. On failure, release every lock you hold and return false. This breaks "hold one, wait for another".

## 6. ReentrantLock rules

1. One lock: `lock()` just before `try`. `unlock()` in `finally`.
2. Why before `try`: if `lock()` is inside and fails, `finally` unlocks a lock you never got. That throws `IllegalMonitorStateException`.
3. Many locks: add each seat to a `locked` list right after `lock()`. In `finally`, unlock only that list. Reverse order is tidy, not required.
4. Reentrant means a hold count. The same thread can lock again: count 1 -> 2. Each `unlock()` drops one. Free at 0. That is why `bookSeats` can hold a lock and still call `book()`.
5. `tryLock(time, unit)`: always check the return value. If false, you do not hold it. Do not touch the seat. Do not unlock. It throws `InterruptedException`.
6. Every method that writes a seat must use the same lock. `synchronized(seat)` and `seat.getLock()` are two different locks.

## 7. Interview questions, short answers

| Question | Answer |
|---|---|
| Why not one `synchronized` on the service? | Correct but serial. Unrelated seats wait for each other. |
| Why lock the seat, not the service? | Only users of the same seat wait. |
| Why `volatile` on status? | Browsing reads without a lock. Without `volatile` it may never see the update. |
| The browse list can be stale. Is that a bug? | No. `book()` checks again under the lock. The user sees "already taken". |
| `HashMap` with many threads. Safe? | Yes. Built in the constructor, `final`, read-only. |
| Why sort in `bookSeats`? | Same lock order for every thread. No cycle, so no deadlock. |
| Alice holds both seats. Is Bob holding a lock while he waits? | No. He waits at the first lock with nothing held. |
| Exception in the middle of a booking? | `finally` unlocks. Writes stay. Restore old values in catch and rethrow. |
| Interrupted during the sleep? | Nothing is written yet. Nothing to undo. |
| `synchronized` or `ReentrantLock`? | `ReentrantLock` only for tryLock, interruptible lock, fairness, or N locks in a loop. Otherwise `synchronized`, because it cannot leak. |
| A lock-free option? | `AtomicReference<SeatState>.compareAndSet(old, new)`. |

## 8. Not built: likely follow-ups

| Topic | Idea | Catch |
|---|---|---|
| Timeout | `tryLock(500ms)`. User gets "try again". | A plain `false` cannot tell "busy" from "taken". Return a reason. |
| HELD for payment | AVAILABLE -> HELD (with expiry) -> BOOKED. | Needs a timer to free expired holds. Taking a hold is check-then-act too, so it needs the lock. |
| Seats created at runtime | Get locks with `ConcurrentHashMap.computeIfAbsent(id, ...)`. | Locks are never removed, so memory grows. Removing them safely is hard. |
| Lock striping | N locks. A seat uses `locks[hash(id) % N]`. | Fewer lock objects. Unrelated seats can share a lock and wait. For many seats, sort by stripe index. |
| Many servers | JVM locks do not work across machines. | Use a DB row lock, a conditional write (for example a DynamoDB condition expression), or a distributed lock. |
