package com.bjtu.simulation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TableSnapshot {
    private final int tableId;
    private final int capacity;
    private final int occupiedSeats;
    private final int emptySeats;
    private final boolean occupied;
    private final long occupiedSeconds;
    private final long occupiedSeatSeconds;
    private final double utilizationRate;
    private final List<String> occupiedGroupIds;

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds) {
        this(tableId, capacity, occupiedSeats, occupiedSeconds, occupiedSeatSeconds, List.of());
    }

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds,
                         List<String> occupiedGroupIds) {
        this.tableId = tableId;
        this.capacity = Math.max(0, capacity);
        this.occupiedSeats = Math.max(0, Math.min(this.capacity, occupiedSeats));
        this.emptySeats = Math.max(0, this.capacity - this.occupiedSeats);
        this.occupied = this.occupiedSeats > 0;
        this.occupiedSeconds = Math.max(0L, occupiedSeconds);
        this.occupiedSeatSeconds = Math.max(0L, occupiedSeatSeconds);
        this.utilizationRate = this.capacity == 0 ? 0.0 : round3((double) this.occupiedSeats / this.capacity);

        List<String> normalized = new ArrayList<>();
        if (occupiedGroupIds != null) {
            int limit = Math.min(this.occupiedSeats, occupiedGroupIds.size());
            for (int i = 0; i < limit; i++) {
                String value = occupiedGroupIds.get(i);
                normalized.add(value == null ? "" : value);
            }
        }
        this.occupiedGroupIds = Collections.unmodifiableList(normalized);
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

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
