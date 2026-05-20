package com.bjtu.simulation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TableSnapshot {
    private final int tableId;
    private final int capacity;
    private final int occupiedSeats;
    private final int reservedSeats;
    private final int emptySeats;
    private final boolean occupied;
    private final long occupiedSeconds;
    private final long occupiedSeatSeconds;
    private final double utilizationRate;
    private final List<String> occupiedGroupIds;
    private final List<String> reservedGroupIds;

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds) {
        this(tableId, capacity, occupiedSeats, occupiedSeconds, occupiedSeatSeconds, List.of(), 0, List.of());
    }

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds,
                         List<String> occupiedGroupIds) {
        this(tableId, capacity, occupiedSeats, occupiedSeconds, occupiedSeatSeconds, occupiedGroupIds, 0, List.of());
    }

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds,
                         List<String> occupiedGroupIds,
                         int reservedSeats,
                         List<String> reservedGroupIds) {
        this.tableId = tableId;
        this.capacity = Math.max(0, capacity);
        this.occupiedSeats = Math.max(0, Math.min(this.capacity, occupiedSeats));
        int safeReserved = Math.max(0, reservedSeats);
        // occupied + reserved 不能超过 capacity
        this.reservedSeats = Math.max(0, Math.min(this.capacity - this.occupiedSeats, safeReserved));
        this.emptySeats = Math.max(0, this.capacity - this.occupiedSeats - this.reservedSeats);
        this.occupied = this.occupiedSeats > 0;
        this.occupiedSeconds = Math.max(0L, occupiedSeconds);
        this.occupiedSeatSeconds = Math.max(0L, occupiedSeatSeconds);
        this.utilizationRate = this.capacity == 0 ? 0.0 : round3((double) this.occupiedSeats / this.capacity);

        this.occupiedGroupIds = normalize(occupiedGroupIds, this.occupiedSeats);
        this.reservedGroupIds = normalize(reservedGroupIds, this.reservedSeats);
    }

    private static List<String> normalize(List<String> source, int limitSeats) {
        List<String> normalized = new ArrayList<>();
        if (source != null) {
            int limit = Math.min(limitSeats, source.size());
            for (int i = 0; i < limit; i++) {
                String value = source.get(i);
                normalized.add(value == null ? "" : value);
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    public int getTableId() {
        return tableId;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getOccupiedSeats() {
        return occupiedSeats;
    }

    public int getReservedSeats() {
        return reservedSeats;
    }

    public int getEmptySeats() {
        return emptySeats;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public long getOccupiedSeconds() {
        return occupiedSeconds;
    }

    public long getOccupiedSeatSeconds() {
        return occupiedSeatSeconds;
    }

    public double getUtilizationRate() {
        return utilizationRate;
    }

    public List<String> getOccupiedGroupIds() {
        return occupiedGroupIds;
    }

    public List<String> getReservedGroupIds() {
        return reservedGroupIds;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
