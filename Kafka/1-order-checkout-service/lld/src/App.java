import java.util.List;

import db.InMemoryDb;
import handler.KafkaHandler;
import handler.OrderHandler;
import handler.OutboxHandler;
import model.OrderItem;

public class App {
    public static void main(String[] args) throws Exception {
        
        // Declaration
        InMemoryDb db = InMemoryDb.getInstance();
        KafkaHandler kafka = new KafkaHandler();
        OrderHandler handler = new OrderHandler(db);

        OutboxHandler relay = new OutboxHandler(db, kafka);
        relay.start();

        OrderItem item1 = new OrderItem("item1", "mobile", 10000);
        OrderItem item2 = new OrderItem("item2", "laptop", 100000);
        OrderItem item3 = new OrderItem("item3", "bike", 200000);


        String orderId1 = handler.purchase("order-1", "user-1", List.of(item1, item2));
        String orderId2 = handler.purchase("order-2", "user-1", List.of(item2, item3));


        System.out.println("Purchase order " + orderId1 + " created.");
        System.out.println("Purchase order " + orderId2 + " created.");


    }
}
