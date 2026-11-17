package connectionpool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demo: 20 requests share 5 connections.
 *
 * Each query takes 500 ms and a borrower waits at most 700 ms.
 * Expected: 5 served at once, 5 more after ~500 ms, the other 10 time out at ~700 ms.
 */
public class Main {

    public static void main(String[] args) {

        final int MAX_CONNECTIONS = 5;
        final int acquireTimeoutMs = 700;
        Service service = new Service();
        ConnectionManager manager = new ConnectionManager(MAX_CONNECTIONS, acquireTimeoutMs, service);

        // One thread per request, so all 20 compete for the 5 connections at once.
        int numberOfRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfRequests);

        for (int i = 0; i < numberOfRequests; i++) {
            final int requestId = i;

            executor.submit(() -> {
                Connection conn = null;   // stays null if acquire fails -> nothing to release

                try {
                    conn = manager.acquire();
                    Log.log("Request " + requestId + " acquired " + conn.getId()
                            + " by " + Thread.currentThread().getName());

                    String response = conn.process(requestId);
                    Log.log("Request " + requestId + " response: " + response);

                } catch (PoolTimeoutException e) {
                    // Pool was busy for the whole timeout. A real service would return "try again".
                    Log.log("Request " + requestId + " TIMED OUT: " + e);
                } catch (InterruptedException e) {
                    Log.log("[ERROR] = " + e);
                    Thread.currentThread().interrupt();   // restore the flag so the executor sees it
                } catch (Exception e) {
                    Log.log("[ERROR] = " + e);
                } finally {
                    // Always give it back, even if process() threw. Otherwise the connection leaks.
                    if (conn != null) {
                        try {
                            manager.release(conn);
                        } catch (Exception e) {
                            Log.log("[ERROR] = " + e);
                        }
                    }
                }
            });
        }

        // Stop taking new tasks, wait for running ones, force-stop after 5s.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();   // interrupts the threads still running
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.log("------- Finished -------");
    }
}
