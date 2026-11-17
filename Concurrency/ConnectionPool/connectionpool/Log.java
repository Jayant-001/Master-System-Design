package connectionpool;

/** Prints: ms since the first log, thread name, message. */
public class Log {
    static final long START = System.currentTimeMillis();

    static void log(String msg) {
        System.out.printf("%5d ms [%-16s] %s%n",
            System.currentTimeMillis() - START, Thread.currentThread().getName(), msg);
    }
}
