package com.bjtu.simulation.model;

public class TableSnapshot {
    private final int tableId;
    private final int capacity;
    private final int occupiedSeats;
    private final int emptySeats;
    private final boolean occupied;
    private final long occupiedSeconds;
    private final long occupiedSeatSeconds;
    private final double utilizationRate;

    public TableSnapshot(int tableId,
                         int capacity,
                         int occupiedSeats,
                         long occupiedSeconds,
                         long occupiedSeatSeconds) {
        this.tableId = tableId;
        this.capacity = Math.max(0, capacity);
        this.occupiedSeats = Math.max(0, Math.min(this.capacity, occupiedSeats));
        this.emptySeats = Math.max(0, this.capacity - this.occupiedSeats);
        this.occupied = this.occupiedSeats > 0;
        this.occupiedSeconds = Math.max(0L, occupiedSeconds);
        this.occupiedSeatSeconds = Math.max(0L, occupiedSeatSeconds);
        this.utilizationRate = this.capacity == 0 ? 0.0 : round3((double) this.occupiedSeats / this.capacity);
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

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
