package model;

public class OutboxEvent {
    private Long eventId;       // assigned by InMemoryDb on insert: unique and ordered
    private String eventType;   // Enum, String for simplicity : ORDER_CREATED | ORDER | CANCELLED
    private OrderEvent payload;

    public OutboxEvent(String eventType, OrderEvent payload) {
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getEventId() {
        return this.eventId;
    }

    public String getEventType() {
        return this.eventType;
    }

    public OrderEvent getPayload() {
        return this.payload;
    }

    public void assignEventId(Long eventId) {
        this.eventId = eventId;
    }
}
