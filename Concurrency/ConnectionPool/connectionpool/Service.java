package connectionpool;

/**
 * Fake database. The sleep is the query time.
 * No fields, so one instance is safely shared by every connection.
 */
public class Service {

    public String process(int requestId) throws InterruptedException {
        Thread.sleep(500);
        return requestId + " processed";
    }
}
