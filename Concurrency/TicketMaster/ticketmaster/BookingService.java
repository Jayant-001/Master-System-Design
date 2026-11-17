package ticketmaster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import ticketmaster.model.Seat;

/**
 * Public API. Finds seats and calls Seat.book() / Seat.release(),
 * which take the seat's own lock. bookSeats holds several seat locks at once.
 */
public class BookingService {

    private final SeatManager seatManager;

    public BookingService(SeatManager seatManager) {
        this.seatManager = seatManager;
    }

    // Seat IDs only: callers never get a Seat they could change without the lock.
    public List<String> getAvailableSeats(String section) {
        return seatManager.getAvailableSeats(section).stream()
                .map(Seat::getId)
                .collect(Collectors.toList());
    }

    public boolean bookSeat(String userId, String seatId) throws InterruptedException {
        Optional<Seat> maybeSeat = seatManager.getSeat(seatId);
        if (maybeSeat.isEmpty()) return false;
        return maybeSeat.get().book(userId);        // Seat takes its own lock
    }

    public boolean cancelBooking(String userId, String seatId) {
        Optional<Seat> maybeSeat = seatManager.getSeat(seatId);
        if (maybeSeat.isEmpty()) return false;
        return maybeSeat.get().release(userId);
    }

    /**
     * Books all seats, or none of them.
     * Returns false if any seat is missing, duplicated, or taken.
     */
    public boolean bookSeats(String userId, List<String> seatIds) throws InterruptedException {
        if (seatIds.isEmpty()) return false;

        List<Seat> seats = seatManager.getSeats(seatIds);
        if (seats.size() != seatIds.size()) return false;   // missing or duplicate ID

        // Every thread locks in the same order -> no deadlock.
        List<Seat> ordered = seats.stream()
                .sorted(Comparator.comparing(Seat::getId))
                .toList();

        List<Seat> locked = new ArrayList<>();
        try {
            // 1. lock all, in sorted order
            for (Seat seat : ordered) {
                seat.getLock().lock();
                locked.add(seat);                   // track it, so finally unlocks only what we hold
            }

            // 2. check all
            for (Seat seat : ordered) {
                if (!seat.isAvailable()) return false;      // nothing written yet
            }

            // 3. book all; if one fails, undo the ones already booked
            List<Seat> booked = new ArrayList<>();
            try {
                for (Seat seat : ordered) {
                    seat.book(userId);              // locks again: reentrant, hold count 1 -> 2, no wait
                    booked.add(seat);
                }
            } catch (RuntimeException | InterruptedException e) {
                for (Seat seat : booked) {
                    seat.release(userId);
                }
                throw e;
            }
            return true;
        } finally {
            // 4. unlock only what we locked, in reverse order
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).getLock().unlock();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Option B (earlier version, kept for revision; does not compile as-is).
    // Holds N locks with nested synchronized blocks, using recursion.
    // Correct alone, but it locks the seat's monitor, not seat.getLock().
    // Mixed with the ReentrantLock methods above, both lock types can be held
    // by different threads at once, and a seat gets sold twice.
    //
    // private boolean lockThenBook(String userId, List<Seat> ordered, int i) {
    //     if (i == ordered.size()) {                  // all locks held now
    //         for (Seat s : ordered) if (!s.isAvailable()) return false;
    //         for (Seat s : ordered) { s.setBookedBy(userId); s.markBooked(); }
    //         return true;
    //     }
    //     synchronized (ordered.get(i)) {             // released at the closing brace
    //         return lockThenBook(userId, ordered, i + 1);
    //     }
    // }
    // -------------------------------------------------------------------------
}
