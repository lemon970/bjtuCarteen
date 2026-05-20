package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.model.DiningArea;

import org.junit.jupiter.api.Test;

class SeatReservationTest {

    @Test
    void singleTableReservationLocksSeats() {
        DiningArea area = new DiningArea(8, 2, 0, 1.0);
        DiningArea.SeatAllocation reservation = area.tryReserveSeats(4, 0L, "g1", true);

        assertNotNull(reservation);
        assertFalse(reservation.splitGroup());
        assertEquals(4, reservation.seatCount());
        assertEquals(1, reservation.parts().size());
        // 4 reserved + 0 occupied -> 8 - 4 = 4 empty
        assertEquals(0, area.getOccupiedSeats());
        assertEquals(4, area.getReservedSeats());
        assertEquals(4, area.getEmptySeats());
    }

    @Test
    void splitGroupAcrossTwoAdjacentTablesWhenSingleTableNotEnough() {
        // 2 个 4 座桌,在桌 0 先占 1 reserved,然后请求 5 人 → 必然跨桌
        DiningArea area = new DiningArea(8, 2, 0, 1.0);
        area.tryReserveSeats(1, 0L, "filler", true);

        DiningArea.SeatAllocation reservation = area.tryReserveSeats(5, 0L, "big-group", true);

        assertNotNull(reservation);
        assertTrue(reservation.splitGroup());
        assertEquals(5, reservation.seatCount());
        assertEquals(2, reservation.parts().size());
        int total = reservation.parts().stream().mapToInt(DiningArea.TablePart::seatCount).sum();
        assertEquals(5, total);
    }

    @Test
    void confirmReservationMovesSeatsFromReservedToOccupied() {
        DiningArea area = new DiningArea(8, 2, 0, 1.0);
        DiningArea.SeatAllocation reservation = area.tryReserveSeats(4, 0L, "g1", true);
        assertNotNull(reservation);

        area.confirmReservation(reservation, 10L);

        assertEquals(4, area.getOccupiedSeats());
        assertEquals(0, area.getReservedSeats());
        assertEquals(4, area.getEmptySeats());
    }

    @Test
    void cancelReservationFreesSeats() {
        DiningArea area = new DiningArea(8, 2, 0, 1.0);
        DiningArea.SeatAllocation reservation = area.tryReserveSeats(4, 0L, "g1", true);
        assertNotNull(reservation);
        assertEquals(4, area.getReservedSeats());

        area.cancelReservation(reservation);

        assertEquals(0, area.getReservedSeats());
        assertEquals(8, area.getEmptySeats());
    }

    @Test
    void reservationBlocksOtherStudentsFromOccupyingSameSeats() {
        DiningArea area = new DiningArea(4, 1, 0, 1.0);
        DiningArea.SeatAllocation first = area.tryReserveSeats(4, 0L, "g1", true);
        assertNotNull(first);

        // 第二个学生再请求时,不应抢到 reserved 的座位
        DiningArea.SeatAllocation second = area.tryReserveSeats(1, 0L, "individual", true);
        assertNull(second);
    }

    @Test
    void canReserveDoesNotMutateState() {
        DiningArea area = new DiningArea(8, 2, 0, 1.0);
        boolean canReserve = area.canReserve(5, 0L);
        assertTrue(canReserve);
        // 没有副作用
        assertEquals(0, area.getReservedSeats());
        assertEquals(0, area.getOccupiedSeats());
    }
}
