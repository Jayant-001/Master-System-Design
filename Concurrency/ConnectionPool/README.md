# Connection Pool: Concurrency Notes

Read this before an interview. The code has the final version. This file has the ideas, options and traps.

## Problem

Our service talks to a database. Opening a DB connection is slow, and the database allows only a few open connections. Many request threads need to run queries.

So we keep a pool: N connections, opened once, then reused.

Requirements:

1. Create the pool with a max size.
2. `acquire()` gives the caller a free connection.
3. `release(conn)` gives it back so someone else can use it.
4. `Connection` is fake. A query is a 500 ms sleep.

Rules:

1. Never more than N connections exist.
2. One connection is used by one thread at a time.
3. If none is free, the caller waits, but not forever.
4. A released connection is reused. We never open a new one in its place.

## How to run

- `./script.sh` compiles and runs `Main`.
- `Main` setup: 5 connections, 700 ms max wait, 20 requests on 20 threads, 500 ms per query.

| File | Job |
|---|---|
| `ConnectionManager.java` | The pool. Free connections in a `BlockingQueue`, borrowed ones in a concurrent set. |
| `Connection.java` | Fake connection with an ID. |
| `Service.java` | Fake database. Sleep = query time. |
| `PoolTimeoutException.java` | Thrown when the wait runs out. |
| `Log.java` | Prints ms since start + thread name. |
| `Main.java` | The demo. |

## 1. What one run of `Main` showed

| Time | What happened |
|---|---|
| 0 ms | Requests 0-4 got the 5 connections. The other 15 started waiting. |
| ~520 ms | The first 5 finished and released. Requests 5-9 got those connections. |
| ~700 ms | The last 10 had waited 700 ms. All 10 timed out. |
| ~1030 ms | Requests 5-9 finished. Program ended. |

Result: 10 served, 10 timed out. That is the expected result for this setup, not a bug. The next free-up after ~520 ms comes at ~1020 ms, and by then the 700 ms wait has run out.

## 2. Extra checks done during review (scratch copy)

| # | Check | Result |
|---|---|---|
| 1 | 200 requests, 50 threads, pool of 5, long timeout | All 200 served. Max 5 in use at once. No connection held by 2 threads. Same in 3 runs. |
| 2 | Release the same connection twice | Second release rejected. Pool size stays correct. |
| 3 | Release a connection not from this pool | Rejected. |
| 4 | Borrow everything, never release (leak) | Pool is empty forever. Every later `acquire()` times out. |
| 5 | Interrupt a thread while it waits | It stops waiting at once with `InterruptedException`. The pool still works. |
| 6 | Keep the reference after release, then use it | Works. Nothing stops it. Gap, see section 7. |
| 7 | Release again after someone else borrowed it | Accepted. Two later borrowers got the same connection. Gap, see section 7. |

## 3. Rules to remember

1. Release in `finally`. Only if `acquire()` worked (`conn != null`).
2. Never wait forever. Use a timeout and fail fast.
3. In `release`: remove from `inUse` FIRST, then put back in the queue. Reversed, another thread can borrow it in between, and your remove deletes THEIR entry.
4. In `acquire`: take from the queue first, then add to `inUse`. Once out of the queue, only you have it.
5. Queue capacity = pool size. Then the queue can never hold more than N.
6. Catch `InterruptedException`: either rethrow it, or restore the flag with `Thread.currentThread().interrupt()`. Never swallow it.
7. "Pool busy" and "thread interrupted" are different errors. Keep them as different exceptions.
8. After release, the reference you still hold is not yours. The pool cannot stop you from using it.

## 4. Options and tradeoffs

### How to hold free connections and make callers wait

| Option | Good | Bad |
|---|---|---|
| Busy loop: check a list again and again | Simple | Burns CPU. |
| Loop with `sleep(10)` | Less CPU | Adds delay. Wakes up for nothing. |
| `List` + `synchronized` + `wait()` / `notifyAll()` | No library needed | Easy to get wrong. Must wait in a `while` loop (spurious wakeups). Must notify on every release. |
| `ReentrantLock` + `Condition` | Full control. Timeout with `await(time)`. | Same traps as wait/notify, more code. |
| `Semaphore(N)` + a concurrent queue | Semaphore counts free slots, has `tryAcquire(timeout)`. | Two things to keep in step: permits and objects. |
| `BlockingQueue` (chosen) | One object does the count, the wait, the timeout and the handoff. | Less control over order (no LIFO in `ArrayBlockingQueue`). |

### Which BlockingQueue

| Option | Note |
|---|---|
| `ArrayBlockingQueue` (chosen) | Fixed size, set at creation. One lock inside. Optional fairness flag. |
| `LinkedBlockingQueue` | Unbounded unless you give a size. Creates a node on every insert. |
| `LinkedBlockingDeque` | Can reuse the most recently returned connection (LIFO), so idle ones can be found and closed. |
| `SynchronousQueue` | Holds nothing. Hands off directly. Wrong fit for a pool. |

### What to do when the pool is empty

| Option | Good | Bad |
|---|---|---|
| `take()`: wait forever | Never fails | One slow DB query backs up every thread. Requests hang. |
| `poll()`: no wait | Fast answer | Fails on every tiny spike. |
| `poll(timeout)` (chosen) | Short spikes are absorbed. Long ones fail fast. | Must pick the timeout. Too short: needless failures. Too long: threads pile up. |
| Open a new connection | Caller never waits | Breaks the DB limit. Opening is slow. |

### Tracking borrowed connections

| Option | Catches | Misses |
|---|---|---|
| No tracking | Nothing | A double release can put a duplicate in the queue, so 2 threads get the same connection. |
| `ConcurrentHashMap.newKeySet()` (chosen) | Double release while idle. Foreign connection. | A stale release after someone else borrowed it. Use after release. |
| `Collections.synchronizedSet` | Same as above | One lock for every call. |
| Map: connection -> owner thread | Release by a different thread | Breaks async code, where one thread borrows and another releases. |
| New wrapper ("lease") per borrow (not built) | All of the above. A used lease refuses both `process()` and `release()`. | One more class. An extra object per borrow. |

### Interrupts

| Option | Result |
|---|---|
| Catch and ignore | Bad. The flag is lost. `shutdownNow()` cannot stop the thread. |
| Rethrow (`acquire` does this) | Caller decides. Nothing was taken from the pool. |
| Catch, log, restore the flag (`Main` does this) | Thread pool sees the interrupt and can stop the thread. |

### Stopping the thread pool

| Call | What it does |
|---|---|
| `shutdown()` | No new tasks. Running tasks finish. |
| `awaitTermination(t)` | Waits up to `t` for them to finish. Returns false on timeout. |
| `shutdownNow()` | Interrupts running tasks. Drops queued tasks. |

Usual order: `shutdown`, then `awaitTermination`, then `shutdownNow` if it timed out.

## 5. Leaks

- A leak is a connection borrowed and never released.
- Each leak makes the pool one smaller, for good.
- After N leaks, every `acquire()` times out. The service looks "down" with no error in the DB.
- Prevent: release in `finally`. Later: `AutoCloseable` lease with try-with-resources.
- Detect (not built): record when each connection was borrowed. Warn if one is held longer than X seconds.

## 6. Interview questions, short answers

| Question | Answer |
|---|---|
| Why a pool at all? | Opening a connection is slow, and the DB has a hard limit. |
| Why `BlockingQueue`? | It counts, waits, times out and hands off, all thread-safe, in one object. |
| All N busy and thread N+1 calls `acquire()`? | It sleeps up to the timeout. Then `PoolTimeoutException`. |
| Why a timeout? | Without it, a slow DB makes every thread wait forever. With it, callers fail fast and can retry. |
| Caller forgets to release? | Connection leaks. After N leaks the pool is empty for good. |
| Double release? | `inUse` rejects it. |
| Can a caller still break rule 2? | Yes. Keep the reference after release and use it, or release it again after someone re-borrowed it. Fix with a lease object. |
| Why remove from `inUse` before putting back? | Reversed, a new borrower can get it in between, and your remove deletes their entry. |
| Why capacity = pool size? | The queue can never hold more than N, so a valid release always has room. |
| 20 threads, 5 connections. Is that wrong? | No. More threads than connections is normal. The timeout limits how long they wait. |
| Fair or unfair queue? | Fair (`new ArrayBlockingQueue<>(n, true)`) serves the longest waiter first. Slower. Default is unfair. |
| Connection breaks (DB restart)? | Not handled. Check it on borrow or in the background, and replace bad ones. |
| Many servers? | Each JVM has its own pool. Total connections = servers x pool size. Must stay under the DB limit. |

## 7. Not built yet: next steps when I come back

| Topic | Idea | Catch |
|---|---|---|
| Lease object | `acquire()` returns a new wrapper each time with a "released" flag. `release()` uses compareAndSet on it, so it works once. | Fixes checks 6 and 7 from section 2. |
| try-with-resources | Make the lease `AutoCloseable`. `close()` releases. | Caller can no longer forget `finally`. |
| Health check | Test a connection on borrow. Replace it if broken. | Adds time to every borrow. A background check is cheaper. |
| Lazy creation | Start with a few. Open more up to N when busy. | Count + create must be atomic. Use a lock or a `Semaphore`. |
| Idle timeout / max lifetime | Close connections unused for X min, or older than Y. | Must not close one that is borrowed. |
| Leak detection | Record borrow time. Warn after X seconds. | Needs a background timer. |
| Pool shutdown | Close all connections. Reject new `acquire()`. | Borrowers still running must finish first. |
| Metrics | Wait time, number in use, timeouts. | - |
