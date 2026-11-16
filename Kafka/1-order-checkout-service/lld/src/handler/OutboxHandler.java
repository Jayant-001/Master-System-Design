package handler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import db.InMemoryDb;
import model.OutboxEvent;

public class OutboxHandler {

    private final InMemoryDb db;
    private final KafkaHandler kafkaHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public OutboxHandler(InMemoryDb db, KafkaHandler kafka) {
        this.db = db;
        this.kafkaHandler = kafka;
    }

    public void start() {

        scheduler.scheduleAtFixedRate(() -> {
            processOutbox();
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void processOutbox() {
        System.out.println("[relay] pending events: " + db.findPending(2).size());

        for (OutboxEvent e : db.findPending(2)) {
            try {
                System.out.println("[relay] event = " + e.toString());
                kafkaHandler.publish(e);
                db.markSent(e.getEventId());
            } catch (Exception ex) {
                break;
            }
        }

    }
    
    // Schedule run
    // reads from outbox table 
    // write to kafka
    // idempotent with event id

    // OutboxHandler (relay)
    // for (OutboxEvent e : db.findPending(100)) {
    //     KafkaMessage msg = new KafkaMessage(
    //         e.getAggregateId(),     // key = orderId → partition
    //         "evt-" + e.getSeq(),    // eventId → consumers deduplicate on this
    //         e.getEventType(),       // copied from the outbox row
    //         e.getPayload());        // business data
    //     publisher.publish("orders", msg);
    //     db.markSent(e.getSeq());
    // }

    // Envelope: the value is { eventId, eventType, occurredAt, data: {...payload} }. 
    // This is simpler to read and debug, and the one I'd use here.

}
