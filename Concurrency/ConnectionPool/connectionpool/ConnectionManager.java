package connectionpool;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-size connection pool.
 *
 * All connections are created once, in the constructor. Never more, never fewer.
 * Free connections wait in `queue`. Borrowed ones are tracked in `inUse`.
 */
public class ConnectionManager {

    private final int acquireTimeoutMs;

    // Free connections. Thread-safe, bounded, and poll(timeout) lets a
    // borrower sleep until one is returned (no busy loop, no wait/notify).
    private final BlockingQueue<Connection> queue;

    // Borrowed connections. Used only to reject a double release or a
    // connection that never came from this pool.
    private final Set<Connection> inUse;

    public ConnectionManager(int maxConnections, int acquireTimeoutMs, Service service) {
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.queue = new ArrayBlockingQueue<>(maxConnections);  // capacity = pool size
        this.inUse = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < maxConnections; i++) {
            queue.add(new Connection(i, service));
        }
    }

    /**
     * Borrow a free connection. Waits up to acquireTimeoutMs.
     *
     * @throws PoolTimeoutException no connection came back in time
     * @throws InterruptedException the thread was interrupted while waiting; nothing was taken
     */
    public Connection acquire() throws InterruptedException {
        Connection conn = queue.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);  // null = timed out
        if (conn == null) {
            throw new PoolTimeoutException("No connection available within " + acquireTimeoutMs + " ms");
        }
        // Safe to do after poll: once out of the queue, only this thread has it.
        inUse.add(conn);
        return conn;
    }

    /**
     * Return a borrowed connection.
     *
     * Order matters: remove from inUse FIRST, then put back in the queue.
     * Reversed, another thread could borrow it in between, and our remove
     * would then wipe out THEIR inUse entry.
     *
     * Not handled yet: a caller that keeps the reference after release can
     * still use it, or release it again after someone else borrowed it.
     */
    public void release(Connection conn) {
        if (!inUse.remove(conn)) {
            throw new IllegalStateException("Connection " + conn.getId() + " is not borrowed from this pool");
        }
        // Cannot fail: only borrowed connections get here, so the queue has room.
        queue.offer(conn);
    }
}
