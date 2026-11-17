# Mini Server — learning how backend frameworks work

Goal: build a tiny HTTP server in plain Java (no libraries) and implement all four
threading models myself, so I understand what Express / Spring Boot / Fastify / Coral
do under the hood.

---

## 1. What every backend framework does (the 7 layers)

```
1. LISTEN     open a port (8080) and wait for clients
2. ACCEPT     a client connects → you get a socket (a two-way byte pipe)
3. PARSE      read raw bytes → turn into Request {method, path, headers, body}
4. ROUTE      "GET /users" → which function handles it?
5. MIDDLEWARE run the shared steps: logging, auth, timing   (Express middleware = Spring filters = Coral handler chain)
6. HANDLER    your business logic                         (Express route = Spring controller = Coral Activity)
7. RESPOND    turn the Response into bytes → write to socket → keep the connection open or close it
```

Layers 3–7 are plain code. The real engineering is **which thread runs which layer**.

---

## 2. Connection vs request (the most important idea)

- **Connection** = an open TCP socket between client and server. It can stay open
  for many requests (HTTP/1.1 **keep-alive**, the default) and sit idle in between.
- **Request** = one `GET /users` → one response.
- One connection can carry many requests over its lifetime.
- Keep-alive exists because opening a connection is expensive (TCP handshake, plus TLS).
- Servers close idle connections after an **idle timeout**, after **max requests per
  connection**, or when either side sends `Connection: close`.

The key question for every threading model: **does an idle open connection hold a thread?**

---

## 3. The four threading models

### A. Thread per connection (oldest)
Each connected client gets its own thread for as long as it stays connected.
- Simple to write and read.
- Problem: 1000 idle keep-alive browsers = 1000 threads doing nothing.

### B. Thread per request from a pool (Tomcat, Spring MVC, Coral/Bobcat)
```
 [Acceptor thread]  accepts new connections
 [Poller thread]    watches ALL open connections cheaply (OS events, java.nio Selector)
        │  "connection 42 has a full request ready"
        ▼
 [worker pool: N threads] + [bounded queue]  → full? reject (503)
        │ worker runs parse → route → middleware → handler → respond,
        │ then returns to the pool; the connection goes back to the poller
```
- An idle connection costs only a little memory. A thread is borrowed **only while a
  request is actually being processed**.
- It's the same idea as a connection pool, applied to threads: a limit, a bounded
  queue, then fail fast.
- Tomcat thread names show this: `http-nio-8080-Acceptor`, `http-nio-8080-Poller`,
  `http-nio-8080-exec-N`.

### C. Event loop (Node.js → Express/Fastify; Netty)
**One** thread handles all connections and never waits on anything. When it needs a
database result, it registers a callback and moves on to the next request.
- Rule: **never block the event loop.** A CPU-heavy loop or a `sleep` freezes every
  request on the server.
- Heavy work goes to separate worker threads.
- Fastify isn't faster than Express because of a different threading model. Both run
  on the same Node event loop. Fastify is faster because of quicker routing and JSON
  handling.
- Netty uses N event loops (about 1 per core) instead of 1.

### D. Virtual threads (Java 21+)
You write code in model A or B style (one thread per request, ordinary blocking
calls), but the JVM makes each thread extremely cheap, so 10,000+ threads are fine.
- You get model C's scalability with model B's simplicity.
- Java 24+ fixed the old "pinning" problem with `synchronized`.

---

## 4. Request threads vs worker threads

| | Request thread (Bobcat / Tomcat worker) | Your own worker pools |
|---|---|---|
| Job | Owns one request from start to finish | Parallel calls, heavy CPU work, background tasks |
| Count means | How many requests can run at once | How much parallel work can run |
| Sizing | Based on concurrency (Little's Law: concurrency = throughput × latency) | **CPU-bound** work: about the number of cores. **I/O-bound** work: many more (threads mostly wait) |

- Example: a request needs data from 3 downstream services. The request thread submits
  the 3 calls to a worker pool, waits for all 3 results, then builds the response.
  That takes about 1 call's time instead of 3.
- A thread waiting on network or disk uses **no CPU**, so an I/O-bound pool can have far
  more threads than cores.
- Adding threads to CPU-bound work beyond the core count just makes them fight over the
  CPU (context switching).
- "CPU threads" are not a separate kind of thread. All are ordinary OS threads; what
  differs is the *job* each pool is given.

### How Coral / Bobcat maps to this
- **Coral** is the service framework. **Bobcat** is Coral's HTTP server, and Bobcat3 is
  built on Tomcat connectors. So it uses model B, thread per request.
- `numThreads` = how many requests run at the same time. Each in-flight request holds
  one thread for its whole duration.
- The queue depth defaults to 2 × `numThreads`. Requests beyond that are **refused**:
  wait up to a limit, then fail fast.
- Some Coral services use 3 pools:
  - **Bobcat pool:** reads the request and writes the response
  - **Orchestrator pool:** runs the handler chain
  - **Executor pool:** runs the Activity
- Bobcat can run on virtual threads with
  `Bobcat3EndpointConfig.setExecutorService(Executors.newVirtualThreadPerTaskExecutor())`.
- Sources:
  - [Coral on ECS: autoscaling and load shedding](https://w.amazon.com/bin/view/Pharmacy/FulfillmentTech/WaC/PharmacyFulfillmentOrchestration/Architecture/CoralEcsAutoscalingAndLoadShedding/)
  - [KB: 100% CPU in Coral server thread](https://w.amazon.com/bin/view/Users/liuzhiy/Quip/KB100CPUinCoralServerThread/)
  - [Sage: Bobcat and virtual threads](https://sage.amazon.dev/posts/1859463)

---

## 5. Build plan

The step-by-step plan, with what to expect at each step and the current progress, is in
[PLAN.md](PLAN.md).
