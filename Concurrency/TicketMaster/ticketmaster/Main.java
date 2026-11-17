package ticketmaster;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ticketmaster.model.Seat;

/**
 * Concurrency tests. Seat.book() sleeps 2s, so the timings show who waited for whom.
 * The expected result is in the comment next to each print.
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        SeatManager seatManager = new SeatManager(List.of(
                new Seat("A-1-1"), new Seat("A-1-2"), new Seat("A-1-3"),
                new Seat("A-2-1"), new Seat("A-2-2"), new Seat("A-2-3"),
                new Seat("B-1-1"), new Seat("B-1-2"), new Seat("B-1-3"),
                new Seat("B-2-1"), new Seat("B-2-2"), new Seat("B-2-3")));
        BookingService service = new BookingService(seatManager);

        sameSeatRace(service);
        differentSeatsInParallel(service);
        singleSeatRevertAndCancel(service, seatManager);
        bookSeatsOppositeOrder(service, seatManager);
        bookSeatsVsBookSeat(service, seatManager);
        bookSeatsRevert(service, seatManager);
        bookSeatsBadInput(service);
    }

    // 1. Ten threads race for A-1-1, then B-1-1. Proves: one winner per seat.
    private static void sameSeatRace(BookingService service) throws InterruptedException {
        header("1. same seat race");
        AtomicInteger bookings = new AtomicInteger(0);
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            String userId = "user-" + i;
            threads[i] = new Thread(() -> {
                try {
                    boolean r1 = service.bookSeat(userId, "A-1-1");
                    boolean r2 = service.bookSeat(userId, "B-1-1");
                    if (r1) bookings.incrementAndGet();
                    if (r2) bookings.incrementAndGet();
                    System.out.println(userId + " A-1-1 = " + r1 + ", B-1-1 = " + r2);
                } catch (Exception e) {
                    System.out.println(e);
                }
            });
        }
        long start = System.currentTimeMillis();
        runAll(threads);
        System.out.println("bookings = " + bookings.get()
                + ", took " + (System.currentTimeMillis() - start) + "ms");   // 2, ~4000ms
    }

    // 2. One thread books A-1-2, nine race for A-1-3.
    //    Proves: per-seat locks, different seats do not wait for each other.
    private static void differentSeatsInParallel(BookingService service) throws InterruptedException {
        header("2. different seats in parallel");
        AtomicInteger bookings = new AtomicInteger(0);
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            String userId = "user-" + i;
            String seatId = (i == 0) ? "A-1-2" : "A-1-3";
            threads[i] = new Thread(() -> {
                try {
                    boolean r = service.bookSeat(userId, seatId);
                    if (r) bookings.incrementAndGet();
                    System.out.println(userId + " " + seatId + " = " + r);
                } catch (Exception e) {
                    System.out.println(e);
                }
            });
        }
        long start = System.currentTimeMillis();
        runAll(threads);
        System.out.println("bookings = " + bookings.get()
                + ", took " + (System.currentTimeMillis() - start) + "ms");   // 2, ~2000ms (one lock: ~4000ms)
    }

    // 3. A write fails halfway. Proves: the seat is restored. Then the cancel rules.
    private static void singleSeatRevertAndCancel(BookingService service, SeatManager seatManager)
            throws InterruptedException {
        header("3. single seat revert + cancel");
        try {
            service.bookSeat("bad", "B-2-2");
        } catch (RuntimeException e) {
            System.out.println("bad failed: " + e.getMessage());
        }
        Seat seat = seatManager.getSeat("B-2-2").get();
        System.out.println("after bad: " + seat.getStatus() + ", bookedBy = " + seat.getBookedBy()); // AVAILABLE, null
        System.out.println("good books it = " + service.bookSeat("good", "B-2-2"));          // true
        System.out.println("cancel wrong user = " + service.cancelBooking("bad", "B-2-2"));  // false
        System.out.println("cancel owner = " + service.cancelBooking("good", "B-2-2"));      // true
        System.out.println("cancel unbooked = " + service.cancelBooking("good", "B-2-3"));   // false
    }

    // 4. Alice and Bob ask for the same 2 seats in opposite order.
    //    Proves: sorting prevents deadlock, and only one of them wins.
    private static void bookSeatsOppositeOrder(BookingService service, SeatManager seatManager)
            throws InterruptedException {
        header("4. bookSeats, opposite order");
        AtomicInteger wins = new AtomicInteger(0);
        Thread alice = new Thread(() -> {
            try {
                if (service.bookSeats("alice", List.of("A-2-1", "A-2-2"))) wins.incrementAndGet();
            } catch (Exception e) {
                System.out.println(e);
            }
        });
        Thread bob = new Thread(() -> {
            try {
                if (service.bookSeats("bob", List.of("A-2-2", "A-2-1"))) wins.incrementAndGet();
            } catch (Exception e) {
                System.out.println(e);
            }
        });
        runAll(alice, bob);                                                     // must finish, not hang
        System.out.println("wins = " + wins.get()
                + ", A-2-1 = " + seatManager.getSeat("A-2-1").get().getBookedBy()
                + ", A-2-2 = " + seatManager.getSeat("A-2-2").get().getBookedBy());  // 1, same user on both
    }

    // 5. bookSeats and bookSeat want B-1-2 at the same time.
    //    Proves: both methods use the same seat lock, so B-1-2 is sold once.
    private static void bookSeatsVsBookSeat(BookingService service, SeatManager seatManager)
            throws InterruptedException {
        header("5. bookSeats vs bookSeat");
        Thread carol = new Thread(() -> {
            try {
                System.out.println("carol bookSeats = " + service.bookSeats("carol", List.of("A-2-3", "B-1-2")));
            } catch (Exception e) {
                System.out.println(e);
            }
        });
        Thread dave = new Thread(() -> {
            try {
                System.out.println("dave bookSeat = " + service.bookSeat("dave", "B-1-2"));
            } catch (Exception e) {
                System.out.println(e);
            }
        });
        runAll(carol, dave);
        // exactly one of them has B-1-2; if dave won, A-2-3 must still be free (null)
        System.out.println("A-2-3 = " + seatManager.getSeat("A-2-3").get().getBookedBy()
                + ", B-1-2 = " + seatManager.getSeat("B-1-2").get().getBookedBy());
    }

    // 6. B-2-1 books, then B-2-2 fails. Proves: B-2-1 is undone (all or none).
    private static void bookSeatsRevert(BookingService service, SeatManager seatManager)
            throws InterruptedException {
        header("6. bookSeats revert");
        try {
            service.bookSeats("bad", List.of("B-2-1", "B-2-2"));
        } catch (RuntimeException e) {
            System.out.println("bad bookSeats failed: " + e.getMessage());
        }
        System.out.println("B-2-1 = " + seatManager.getSeat("B-2-1").get().getStatus()
                + ", B-2-2 = " + seatManager.getSeat("B-2-2").get().getStatus());   // AVAILABLE, AVAILABLE
    }

    // 7. Bad input is rejected before any lock is taken.
    private static void bookSeatsBadInput(BookingService service) throws InterruptedException {
        header("7. bookSeats bad input");
        System.out.println("empty = " + service.bookSeats("x", List.of()));                      // false
        System.out.println("missing = " + service.bookSeats("x", List.of("B-2-1", "Z-9-9")));    // false
        System.out.println("duplicate = " + service.bookSeats("x", List.of("B-2-1", "B-2-1")));  // false
    }

    /* ---------- helpers ---------- */

    private static void runAll(Thread... threads) throws InterruptedException {
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();          // join: wait for all, and see their writes
    }

    private static void header(String title) {
        System.out.println("\n---------------- " + title + " ----------------");
    }
}
