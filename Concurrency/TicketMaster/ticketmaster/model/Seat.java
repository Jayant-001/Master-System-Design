package ticketmaster.model;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One seat. It owns its state and the lock that protects that state.
 * All writes go through book() or release(), which take the lock.
 */
public class Seat {
    private final String ID;
    private volatile SeatStatus status;     // volatile: browsing reads it without the lock
    private volatile String bookedBy;       // volatile: getBookedBy() reads it without the lock
    private final ReentrantLock lock = new ReentrantLock();

    public Seat(String id) {
        this.ID = id;
        this.status = SeatStatus.AVAILABLE;
        this.bookedBy = null;
    }

    /* ---------- reads: no lock, fields are volatile ---------- */

    public String getId() {
        return this.ID;
    }

    public SeatStatus getStatus() {
        return this.status;
    }

    public String getBookedBy() {
        return this.bookedBy;
    }

    public boolean isAvailable() {
        return this.status == SeatStatus.AVAILABLE;
    }

    // Public only so bookSeats can hold several seat locks at the same time.
    public ReentrantLock getLock() {
        return this.lock;
    }

    /* ---------- writes: private, so nobody can skip the lock ---------- */

    private void setBookedBy(String bookedBy) {
        this.bookedBy = bookedBy;
    }

    private void markBooked() {
        this.status = SeatStatus.BOOKED;
    }

    private void markAvailable() {
        this.status = SeatStatus.AVAILABLE;
    }

    /* ---------- operations ---------- */

    /**
     * Books the seat for userId.
     * Returns false if the seat is taken.
     * Throws if a write fails; the seat is restored first.
     */
    public boolean book(String userId) throws InterruptedException {
        lock.lock();                                // before try: never unlock a lock we did not get
        try {
            if (!isAvailable()) return false;       // check and act under the SAME lock

            Thread.sleep(2000);                     // DEMO ONLY: makes waiting visible in timings

            SeatStatus oldStatus = status;
            String oldBookedBy = bookedBy;
            try {
                setBookedBy(userId);
                if ("bad".equals(userId) && ID.endsWith("-2")) {   // TEST ONLY: fail between the two writes
                    throw new RuntimeException("simulated failure");
                }
                markBooked();                       // "done" flag, written last
            } catch (RuntimeException e) {
                status = oldStatus;                 // unlock does not undo writes, so restore both
                bookedBy = oldBookedBy;
                throw e;                            // caller must see the failure
            }
            return true;
        } finally {
            lock.unlock();                          // runs on true, false, or exception
        }
    }

    /**
     * Frees the seat. Only the user who booked it can do this.
     * Returns false if the seat is free or booked by someone else.
     */
    public boolean release(String userId) {
        lock.lock();
        try {
            if (isAvailable() || !userId.equals(bookedBy)) return false;

            SeatStatus oldStatus = status;
            String oldBookedBy = bookedBy;
            try {
                markAvailable();
                setBookedBy(null);
            } catch (RuntimeException e) {
                status = oldStatus;
                bookedBy = oldBookedBy;
                throw e;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
