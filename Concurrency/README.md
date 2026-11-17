1. Thread Pool
2. Connection Pool
3. Blocking Queue
4. Concurrent Queue
5. LRU Cache
6. TTL Cache
7. Rate Limiter
8. HTTP Server
9. TCP Server
10. WebSocket Server
11. Pub/Sub
12. Message Queue
13. In-memory KV Store
14. Consistent Hashing
15. Distributed Lock
16. Object Pool
17. Task Scheduler
18. Circuit Breaker
19. Load Balancer
20. Mini Redis

# Java topics - 

1. ExecutorService
2. CompletableFuture
3. Thread
4. synchronized
5. ReentrantLock
ReadWriteLock
Semaphore
CountDownLatch
CyclicBarrier
BlockingQueue
ConcurrentHashMap
AtomicInteger / AtomicReference
volatile
java.nio
Socket / ServerSocket

# C++ topics - 

stack vs heap
pointers/references
RAII
memory ownership
mutexes
atomics
condition variables
threads
sockets
OS-level I/O

```
             High-level architecture
                       ↑
                       │
       Distributed systems / scalability
                       ↑
                       │
          Service/API/data design
                       ↑
                       │
             Concurrency primitives
                       ↑
                       │
              OS / networking basics
```




Got it—you mean the actual interview-question patterns around Thread Pools.

### LLD interview questions

Typical prompts include:

1. **Implement a thread pool from scratch**
   - Create worker threads, a blocking task queue, `submit()`, and shutdown logic.

2. **Design a custom `ThreadPoolExecutor`**
   - Support core threads, maximum threads, queue capacity, keep-alive time, and rejection policies.

3. **Implement `submit()` with a `Future`**
   - Return task results asynchronously and propagate exceptions.

4. **Design graceful shutdown**
   - Finish queued tasks, reject new tasks, and terminate workers safely.

5. **Implement immediate shutdown**
   - Interrupt running tasks and return tasks that were never executed.

6. **Design a bounded thread pool**
   - Decide what happens when all workers and the queue are busy.

7. **Implement a priority thread pool**
   - Execute high-priority tasks first while preventing starvation.

8. **Design a scheduled thread pool**
   - Support delayed and periodic task execution.

9. **Add dynamic resizing**
   - Increase or decrease worker count based on queue size or workload.

10. **Handle worker failure**
    - Restart a worker if it dies because of an uncaught exception.

11. **Implement task cancellation and timeout**
    - Cancel queued tasks and interrupt long-running tasks.

12. **Design a thread pool with metrics**
    - Track active threads, completed tasks, queue size, rejection count, and latency.

### HLD interview questions

Typical system-design prompts include:

1. **Design a distributed thread-pool service**
   - Clients submit jobs; worker machines execute them asynchronously.

2. **Design an asynchronous task-processing system**
   - Examples: email sending, video processing, report generation, or image resizing.

3. **Design a job scheduler using worker pools**
   - Support delayed jobs, recurring jobs, priorities, and retries.

4. **Design a scalable worker system**
   - Explain how workers are added or removed based on queue depth and traffic.

5. **Design a reliable task-execution platform**
   - Handle worker crashes, retries, duplicate execution, and idempotency.

6. **Design a multi-tenant thread-pool system**
   - Prevent one customer from consuming all workers or queue capacity.

7. **Design separate pools for CPU-bound and I/O-bound tasks**
   - Explain sizing, isolation, and protection from resource exhaustion.

8. **Design a high-throughput task queue**
   - Discuss partitioning, ordering, backpressure, persistence, and load balancing.

9. **Design a task system with exactly-once-like behavior**
   - Cover deduplication, idempotency keys, acknowledgements, and retries.

10. **Design monitoring for a thread-pool-based service**
    - Include queue lag, execution latency, saturation, failures, and worker health.

### Common theory follow-ups

Interviewers often ask:

- Why use a thread pool instead of creating a thread per task?
- How do you choose pool size for CPU-bound versus I/O-bound work?
- What happens when the queue is full?
- What are the risks of an unbounded queue?
- How can a thread pool cause deadlock or starvation?
- What is the difference between fixed, cached, scheduled, and work-stealing pools?
- How does graceful shutdown work?
- How do you prevent task starvation?
- Should rejected tasks be retried?
- How would you test a thread pool for race conditions?

The most common coding prompt is:

> Implement a fixed-size thread pool with `submit()`, a blocking queue, worker threads, task exception handling, and graceful shutdown.