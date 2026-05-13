package com.bjtu.simulation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class DiningArea {
    private static final long CLEANING_SECONDS = 90L;
    private static final int SEATS_PER_GRID_ROW = 12;

    private final List<DiningTable> tables;

    public DiningArea(int totalSeats) {
        this(totalSeats, 0, 0, 0.8);
    }

    public DiningArea(int totalSeats, int numFourSeatTables, int numTwoSeatTables, double largeTableRatio) {
        this.tables = buildTables(
                Math.max(0, totalSeats),
                Math.max(0, numFourSeatTables),
                Math.max(0, numTwoSeatTables),
                clamp(largeTableRatio, 0.0, 1.0));
    }

    public boolean tryOccupySeat() {
        return tryOccupySeats(1, 0L) != null;
    }

    public SeatAllocation tryOccupySeats(int requestedSeats, long currentTime) {
        int seats = Math.max(1, requestedSeats);
        Optional<DiningTable> table = findTableForParty(seats, currentTime);
        if (table.isEmpty()) {
            return null;
        }

        DiningTable selected = table.get();
        selected.updateDurations(currentTime);
        selected.cleaningUntilTime = 0L;
        selected.occupiedSeats += seats;
        return new SeatAllocation(selected.tableId, seats);
    }

    public void releaseSeat() {
        for (DiningTable table : tables) {
            if (table.occupiedSeats > 0) {
                releaseSeats(new SeatAllocation(table.tableId, 1), 0L);
                return;
            }
        }
    }

    public void releaseSeats(SeatAllocation allocation, long currentTime) {
        if (allocation == null) {
            return;
        }
        for (DiningTable table : tables) {
            if (table.tableId == allocation.tableId()) {
                table.updateDurations(currentTime);
                table.occupiedSeats = Math.max(0, table.occupiedSeats - Math.max(1, allocation.seatCount()));
                if (table.occupiedSeats == 0) {
                    table.cleaningUntilTime = Math.max(currentTime, 0L) + CLEANING_SECONDS;
                }
                return;
            }
        }
    }

    public int getTotalSeats() {
        int total = 0;
        for (DiningTable table : tables) {
            total += table.capacity;
        }
        return total;
    }

    public int getOccupiedSeats() {
        int occupied = 0;
        for (DiningTable table : tables) {
            occupied += table.occupiedSeats;
        }
        return occupied;
    }

    public int getEmptySeats() {
        return Math.max(0, getTotalSeats() - getOccupiedSeats());
    }

    public List<TableSnapshot> getTableSnapshots(long currentTime) {
        List<TableSnapshot> snapshots = new ArrayList<>();
        for (DiningTable table : tables) {
            long elapsed = Math.max(0L, currentTime - table.lastUpdatedTime);
            long occupiedSeconds = table.occupiedSeconds + (table.occupiedSeats > 0 ? elapsed : 0L);
            long occupiedSeatSeconds = table.occupiedSeatSeconds + elapsed * table.occupiedSeats;
            snapshots.add(new TableSnapshot(
                    table.tableId,
                    table.capacity,
                    table.occupiedSeats,
                    occupiedSeconds,
                    occupiedSeatSeconds));
        }
        return Collections.unmodifiableList(snapshots);
    }

    public long getOccupiedSeatSeconds(long currentTime) {
        long total = 0L;
        for (DiningTable table : tables) {
            long elapsed = Math.max(0L, currentTime - table.lastUpdatedTime);
            total += table.occupiedSeatSeconds + elapsed * table.occupiedSeats;
        }
        return total;
    }

    public List<SeatCellSnapshot> getSeatCells(long currentTime) {
        List<SeatCellSnapshot> cells = new ArrayList<>();
        int seatId = 0;
        for (DiningTable table : tables) {
            for (int seatIndex = 0; seatIndex < table.capacity; seatIndex++) {
                int row = seatId / SEATS_PER_GRID_ROW;
                int column = seatId % SEATS_PER_GRID_ROW;
                String status = seatStatus(table, seatIndex, currentTime);
                cells.add(new SeatCellSnapshot(
                        seatId,
                        table.tableId,
                        row,
                        column,
                        areaForTable(table.tableId),
                        status));
                seatId++;
            }
        }
        return Collections.unmodifiableList(cells);
    }

    private Optional<DiningTable> findTableForParty(int seats, long currentTime) {
        return tables.stream()
                .filter(table -> table.cleaningUntilTime <= Math.max(0L, currentTime))
                .filter(table -> table.getEmptySeats() >= seats)
                .min(Comparator
                        .comparingInt((DiningTable table) -> table.capacity >= seats ? table.capacity : Integer.MAX_VALUE)
                        .thenComparingInt(table -> table.getEmptySeats())
                        .thenComparingInt(table -> table.tableId));
    }

    private String seatStatus(DiningTable table, int seatIndex, long currentTime) {
        if (seatIndex < table.occupiedSeats) {
            return "OCCUPIED";
        }
        if (table.occupiedSeats == 0 && table.cleaningUntilTime > Math.max(0L, currentTime)) {
            return "CLEANING";
        }
        return "FREE";
    }

    private String areaForTable(int tableId) {
        int normalized = Math.max(0, tableId);
        if (normalized % 3 == 0) {
            return "A";
        }
        if (normalized % 3 == 1) {
            return "B";
        }
        return "C";
    }

    private List<DiningTable> buildTables(int totalSeats,
                                          int numFourSeatTables,
                                          int numTwoSeatTables,
                                          double largeTableRatio) {
        List<DiningTable> built = new ArrayList<>();
        int tableId = 0;

        if (numFourSeatTables > 0 || numTwoSeatTables > 0) {
            for (int i = 0; i < numFourSeatTables; i++) {
                built.add(new DiningTable(tableId++, 4));
            }
            for (int i = 0; i < numTwoSeatTables; i++) {
                built.add(new DiningTable(tableId++, 2));
            }
            return built;
        }

        int remaining = totalSeats;
        int largeSeatBudget = (int) Math.floor(totalSeats * largeTableRatio);
        largeSeatBudget -= largeSeatBudget % 4;
        while (remaining >= 4 && largeSeatBudget >= 4) {
            built.add(new DiningTable(tableId++, 4));
            remaining -= 4;
            largeSeatBudget -= 4;
        }
        while (remaining >= 2) {
            built.add(new DiningTable(tableId++, 2));
            remaining -= 2;
        }
        if (remaining == 1) {
            built.add(new DiningTable(tableId, 1));
        }
        return built;
    }

    private double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public record SeatAllocation(int tableId, int seatCount) {
    }

    private static class DiningTable {
        private final int tableId;
        private final int capacity;
        private int occupiedSeats;
        private long lastUpdatedTime;
        private long occupiedSeconds;
        private long occupiedSeatSeconds;
        private long cleaningUntilTime;

        private DiningTable(int tableId, int capacity) {
            this.tableId = tableId;
            this.capacity = Math.max(0, capacity);
            this.occupiedSeats = 0;
            this.lastUpdatedTime = 0L;
            this.occupiedSeconds = 0L;
            this.occupiedSeatSeconds = 0L;
            this.cleaningUntilTime = 0L;
        }

        private int getEmptySeats() {
            return Math.max(0, capacity - occupiedSeats);
        }

        private void updateDurations(long currentTime) {
            long safeTime = Math.max(lastUpdatedTime, currentTime);
            long elapsed = safeTime - lastUpdatedTime;
            if (occupiedSeats > 0) {
                occupiedSeconds += elapsed;
                occupiedSeatSeconds += elapsed * occupiedSeats;
            }
            lastUpdatedTime = safeTime;
        }
    }
}
