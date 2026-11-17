# Mini Server — Build Plan

Concepts are in [README.md](README.md). This file covers **what to do next, and what you
should see when you do it**.

**Current step:** Phase 1 → 1.1

How to use this file:
- Do one step at a time. Get it reviewed, tick the box, move on.
- **Predict** the result before every experiment, then compare with what really happens.
- **Keywords** at each phase are things to search if you get stuck.

---

## Setup (once)

```
server/
  script.sh                 build + run   (./script.sh <model>)
  miniserver/
    Main.java               picks the Server implementation from the args
    http/                   Request, Response, HttpParser, ResponseWriter
    routing/                Router, Handler, Middleware
    server/                 Server interface + one class per threading model
```

- **Rule:** layers 3–7 (parse → respond) are written once. Each threading model only
  changes *which thread calls them*.
- **Name every thread** you create (`acceptor`, `worker-3`, `poller`) so `jstack` and
  your logs are easy to read.
- **Tools:**
  - `curl -v http://localhost:8080/`
  - `nc localhost 8080` (type raw HTTP, or just hold an idle connection)
  - `ab -n 1000 -c 50 [-k] http://localhost:8080/`
  - `jstack <pid>` (find the pid with `jps`)

---

## Phase 1 — Single-threaded server (all 7 layers)

**Goal:** a working HTTP server on one thread. No concurrency yet.
**Keywords:** `ServerSocket`, `Socket`, `InputStream`, HTTP/1.1 message format, CRLF.

- [ ] **1.1 Listen + accept + echo**
  - Build: `ServerSocket(8080)`, loop on `accept()`, read one line, write it back, close.
  - Expect: `nc` works. While client A is connected and silent, **client B is stuck**.
  - Done when: you can explain *why* B is stuck.

- [ ] **1.2 HTTP parse + respond**
  - Build: parse the request line (`GET /path HTTP/1.1`), the headers (until an empty
    line), and the body (read exactly `Content-Length` bytes). Then write the status
    line, the headers (`Content-Length`, `Content-Type`), a blank line and the body.
    Close after each response.
  - Expect: `curl -v` shows your headers. A browser shows your page, and also requests
    `/favicon.ico`.
  - Done when: `curl -X POST -d 'hello' localhost:8080/echo` returns `hello`.

- [ ] **1.3 Router**
  - Build: `router.get("/users", handler)`. Unknown path → 404, wrong method → 405,
    handler throws → 500.
  - Done when: all four status codes can be triggered with `curl`.

- [ ] **1.4 Middleware chain**
  - Build: `use(middleware)`, where each middleware calls `next()`. Add a logging
    middleware and a timing middleware.
  - Expect: each request logs method, path, status and duration.
  - Done when: one middleware can stop the chain early (e.g. return 401 if a header is
    missing).

- [ ] **1.5 Limits and bad input**
  - Build: max header size (8 KB) → 431, malformed request line → 400.
  - Done when: `printf 'garbage\r\n\r\n' | nc localhost 8080` gets 400, and the
    server doesn't crash.

---

## Phase 2 — Model A: thread per connection + keep-alive

**Goal:** handle many clients at once, and keep connections open.
**Keywords:** keep-alive, `Connection: close`, `setSoTimeout`, `SocketTimeoutException`.

- [ ] **2.1 Thread per connection**
  - Build: `accept()` → new named thread → handle the connection.
  - Expect: the stuck-client-B problem from 1.1 is gone.

- [ ] **2.2 Keep-alive**
  - Build: loop over requests on the same socket. Close when:
    - the client sends `Connection: close`
    - the request is HTTP/1.0 without keep-alive
    - the idle timeout is hit (e.g. 5 s)
    - max requests per connection is reached (e.g. 100)
  - Expect: `curl -v url url` shows `Re-using existing connection`.
  - Done when: an idle `nc` gets disconnected after 5 s, and your log says why.

- [ ] **2.3 Experiments**
  - `ab -n 5000 -c 500` → count threads with `jstack | grep -c conn-`.
  - Open 50 idle `nc` clients → 50 threads, all sleeping.
  - Expect: thread count = connection count. Write down memory use and the number of
    threads.

---

## Phase 3 — Model B, step 1: bounded worker pool (blocking I/O)

**Goal:** a Bobcat-style limit — N threads, a bounded queue, reject when full.
**Keywords:** `ThreadPoolExecutor`, `ArrayBlockingQueue`, `RejectedExecutionHandler`, 503.

- [ ] **3.1 Pool + queue + reject**
  - Build: N = 10 threads, queue = 20. When a connection is rejected, write 503 and close.
  - Expect: with `ab -c 100` and a slow handler (sleep 200 ms), some requests get 503.

- [ ] **3.2 Metrics**
  - Build: a scheduled task that logs active threads, queue size and rejected count
    every second.
  - Done when: you can watch the queue fill up and drain under load.

- [ ] **3.3 Experiment: idle connections fill the pool**
  - Open 10 idle keep-alive `nc` clients, then `curl`.
  - Expect: curl waits in the queue, or gets 503, **even though the server is doing nothing**.
  - Done when: you can explain why. This is the problem Phase 5 solves.

---

## Phase 4 — Request threads vs worker threads

**Goal:** see the difference between the request thread and helper pools.
**Keywords:** `CompletableFuture.allOf`, fan-out, CPU-bound vs I/O-bound, Little's Law.

- [ ] **4.1 Parallel downstream calls**
  - Build: a `/dashboard` handler calls 3 fake services (300 ms each) in parallel on a
    separate I/O pool. Optionally go through your `ConnectionPool`.
  - Expect: about 300 ms total instead of 900 ms. Logs show the request thread waiting
    while 3 worker threads do the calls.

- [ ] **4.2 CPU-bound pool sizing**
  - Build: a `/compute` handler with a heavy loop. Try pool size = cores, 2× cores, 10× cores.
  - Expect: going past the core count doesn't improve throughput and can make it worse.
  - Done when: you can explain why I/O pools can be large but CPU pools can't.

---

## Phase 5 — Model B, step 2: NIO poller + worker pool (real Tomcat/Bobcat model)

**Goal:** idle connections cost no thread. The hardest phase, so take it slowly.
**Keywords:** `Selector`, `SelectionKey`, `OP_ACCEPT`/`OP_READ`/`OP_WRITE`,
`ByteBuffer` flip/compact, `selector.wakeup()`.

- [ ] **5.1 NIO echo server (single thread)**
  - Build: a non-blocking `ServerSocketChannel` + `Selector`, echoing bytes back.
  - Expect: one thread serves many `nc` clients at once.

- [ ] **5.2 Incremental parser**
  - Build: a per-connection buffer. Feed it bytes as they arrive, and it returns a
    `Request` only when the request is complete.
  - Expect: requests split across several reads still parse correctly. Test by typing
    slowly in `nc`.

- [ ] **5.3 Acceptor + poller + worker pool**
  - Build: the poller detects a full request → hands it to a worker → the worker writes
    the response → the connection goes back to the poller.
  - Watch out: only the poller thread touches the selector. Workers put connections on
    a queue and call `selector.wakeup()`.

- [ ] **5.4 Idle timeout in the poller**
  - Build: track each connection's last-activity time, and close idle ones periodically.

- [ ] **5.5 Experiment: repeat 3.3**
  - Open 1000 idle connections with 10 workers, then `curl`.
  - Expect: curl is served instantly. Compare with Phase 3.

---

## Phase 6 — Model C: event loop (Node.js style)

**Goal:** one thread does everything and never blocks.
**Keywords:** event loop, non-blocking I/O, callbacks, task queue, Netty `EventLoop`.

- [ ] **6.1 Single-threaded loop**
  - Build: accept, read, parse, route, handle and write, all on one thread. Partial
    writes use `OP_WRITE`.
- [ ] **6.2 Async handlers**
  - Build: handlers return `CompletableFuture<Response>`. Simulate a downstream with a
    `ScheduledExecutorService`. The result is posted back onto the loop's task queue.
  - Expect: 1000 concurrent slow requests are all served by 1 thread.
- [ ] **6.3 Experiment: block the loop**
  - Put `Thread.sleep(2000)` in one handler.
  - Expect: **every** request freezes for 2 s. Move that work to a worker pool and the
    freeze goes away.
- [ ] **6.4 (Optional) N event loops**, one per core, with connections spread across them.

---

## Phase 7 — Model D: virtual threads

**Goal:** blocking-style code that still handles a huge number of connections.
**Keywords:** `Executors.newVirtualThreadPerTaskExecutor()`, carrier threads, pinning.

- [ ] **7.1 Swap the executor**
  - Build: the Phase 2 server, but each connection runs on a virtual thread.
    Almost no code changes.
- [ ] **7.2 Experiment**
  - 10,000 connections.
  - Expect: it works where Phase 2 struggled. `jstack` shows only a few *carrier*
    (platform) threads.

---

## Phase 8 — Compare everything

- [ ] Same handlers, every model, at 100 / 1,000 / 10,000 connections.
      Fill in the table:

| Model | Req/s | p99 latency | Threads | Memory | Breaks at |
|---|---|---|---|---|---|
| A: thread per connection | | | | | |
| B1: pool (blocking) | | | | | |
| B2: NIO + pool | | | | | |
| C: event loop | | | | | |
| D: virtual threads | | | | | |
