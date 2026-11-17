package connectionpool;

/**
 * Thrown by acquire() when no connection came back within the timeout.
 * Unchecked, and separate from InterruptedException: "pool busy" and
 * "thread interrupted" need different handling.
 */
public class PoolTimeoutException extends RuntimeException {

    public PoolTimeoutException(String msg) {
        super(msg);
    }
}
