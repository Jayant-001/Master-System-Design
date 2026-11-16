# Order Checkout: LLD (Java)

Low-level design for Problem 1. The high-level design and the answers to the interviewer follow-ups are in [../README.md](../README.md).

Everything runs in one process and in memory, with no external dependencies.

## How to run

From this `lld` folder:

```bash
javac -d bin $(find src -name '*.java')
java -cp bin App
```

`java -cp bin` runs the compiled classes in `bin`, so recompile after every change. On Java 22+ you can skip that step and run straight from source with `java src/App.java`.

The relay's scheduler keeps the program running. Stop it with Ctrl+C.

Expected output:

```
[relay] pending events: 0
Purchase order order-1 created.
Purchase order order-2 created.
[relay] pending events: 2
[relay] event = ...
[relay] event = ...
```

## Structure

```
src/
├── App.java                   wires the classes together and runs a demo
├── db/
│   └── InMemoryDb.java        orders and outbox "tables"; one lock acts as one transaction
├── handler/
│   ├── OrderHandler.java      API: purchase (cancel and getStatus not done yet)
│   ├── OutboxHandler.java     relay: polls the outbox, publishes, deletes sent events
│   └── KafkaHandler.java      stand-in for Kafka (a BlockingQueue)
└── model/
    ├── Order.java, OrderItem.java, OrderStatus.java
    ├── OrderEvent.java        event payload: a snapshot of the order
    └── OutboxEvent.java       outbox row: eventId, eventType, payload
```

## Flow

```
OrderHandler.purchase(orderId, customerId, items)
  └─ InMemoryDb.placeOrder          synchronized = one transaction
       ├─ orderId already exists?   → return the stored order (idempotent retry)
       ├─ insert the order
       └─ insert the outbox event   eventId = next sequence number
  └─ stored order belongs to another customer? → reject (409)

OutboxHandler, every 5 seconds
  └─ findPending → publish each event in order → markSent (deletes it)
       └─ publish fails → stop the batch and retry on the next run
```

## Design decisions

| Decision | Why |
|---|---|
| The client sends `orderId`, and it is the idempotency key | A retry with the same `orderId` gets the same order back instead of creating a second one. |
| The duplicate check, order insert and outbox insert happen in one `synchronized` method | This simulates one database transaction: there is never an order without its event (the dual-write problem), and two identical requests can't both pass the check. |
| The customer check is in the handler, using the order `placeOrder` returns | The business rule stays in the handler, while duplicate detection stays atomic in the database. |
| `InMemoryDb` assigns `eventId` inside the lock | Ids are unique, and their order matches insert order, like an auto-increment column. |
| The outbox is a `LinkedHashMap`, and sent events are deleted | The relay reads in insert order, and the outbox only holds pending events, so `findPending` doesn't scan old ones. |
| The payload is an `OrderEvent` snapshot, not the `Order` | `Order` keeps changing after the event is created, but an event records what happened at that moment. Consumers also don't depend on the internal model. |
| One payload class for every event type, with `eventType` saying what happened | Simpler than one class per event, and every consumer still gets the data it needs. |
| An event is marked sent only after publishing succeeds | At-least-once delivery: a crash between publishing and `markSent` sends the event again, and consumers deduplicate on `eventId`. |
| The relay stops the batch when a publish fails | Skipping ahead could publish a later event for the same order first. |
| Dependencies are passed in through constructors and wired in `App` | The handler, the relay and (later) the consumers share the same database and broker instances. |

## Simplifications

- The in-memory database uses a lock instead of real transactions and unique constraints.
- `KafkaHandler` is a single `BlockingQueue`: there are no partitions, offsets or consumer groups, and each message reaches only one consumer.
- Only one relay instance runs, which ordering requires anyway.
- Event types and outbox statuses are plain strings, money is a `double`, and `OrderItem` has no quantity.

## Pending

- [ ] **Cancel flow:** in one locked database call, set the status to CANCELLED and add an `ORDER_CANCELLED` outbox event. Reject the cancel if the order is already cancelled. (Follow-up 3: Placed must be processed before Cancelled.)
- [ ] **Idempotent consumer (Loyalty):** keep a `Set<Long>` of processed eventIds and skip any it has already seen. Test it by publishing the same event twice. (Requirement 3: no double points.)
- [ ] **Retry and DLQ in the Email consumer:** retry a few times, then move the event to a `deadLetter` list so the rest keep flowing. (Follow-up 2.)
- [ ] **One queue per consumer service:** `take()` removes the message, so `publish` must add each event to every service's queue.
- [ ] **`getStatus(orderId)`.**
- [ ] **Minor clean-ups:**
  - enums for the event type and outbox status
  - status names that match the HLD (PENDING, REJECTED)
  - a quantity field on `OrderItem`
  - `BigDecimal` or `long` for money
  - a `toString()` on `OutboxEvent` so relay logs show the event instead of `model.OutboxEvent@3893dd27`
