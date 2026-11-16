package handler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import model.OutboxEvent;

public class KafkaHandler {

    private final BlockingQueue<OutboxEvent> queue = new LinkedBlockingQueue<>();

    public void publish(OutboxEvent event) {
        queue.offer(event);
    }

    public OutboxEvent consume() throws InterruptedException {
        return queue.take();  // blocks until an event is available
    }
}
