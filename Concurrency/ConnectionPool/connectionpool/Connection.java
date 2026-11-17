package connectionpool;

/**
 * Fake DB connection. Opening a real one is slow, so the pool creates
 * these once and reuses them. process() stands in for running a query.
 *
 * Not thread-safe by design: the pool guarantees one user at a time.
 */
public class Connection {

    private final int id;
    private final Service service;

    public Connection(int id, Service service) {
        this.id = id;
        this.service = service;
    }

    public String process(int requestId) throws InterruptedException {
        return service.process(requestId);
    }

    public int getId() { return this.id; }
}
