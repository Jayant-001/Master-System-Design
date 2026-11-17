package ticketmaster;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import ticketmaster.model.Seat;
import ticketmaster.model.SeatStatus;

/**
 * Holds all seats by ID.
 * The map is built once in the constructor and never changes,
 * so a plain HashMap is safe to share between threads.
 */
public class SeatManager {

    private final Map<String, Seat> seatsById;      // final + read-only copy = safe to share

    public SeatManager(List<Seat> seats) {
        Map<String, Seat> map = new HashMap<>();
        for (Seat seat : seats) {
            map.put(seat.getId(), seat);
        }
        this.seatsById = Map.copyOf(map);           // read-only: any put() throws
    }

    // Free seats in a section ("A" matches "A-1-1", not "AB-1-1").
    // The result can be stale by the time the user clicks; book() checks again.
    public List<Seat> getAvailableSeats(String section) {
        return seatsById.values().stream()
                .filter(seat -> seat.getId().startsWith(section + '-'))
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .sorted(Comparator.comparing(Seat::getId))
                .toList();
    }

    // O(1) lookup.
    public Optional<Seat> getSeat(String seatId) {
        return Optional.ofNullable(seatsById.get(seatId));
    }

    // Drops duplicate and unknown IDs. The caller compares sizes to detect them.
    public List<Seat> getSeats(List<String> seatIds) {
        return seatIds.stream()
                .distinct()
                .map(seatsById::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
