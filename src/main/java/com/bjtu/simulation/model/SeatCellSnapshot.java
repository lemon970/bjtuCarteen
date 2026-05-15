package com.bjtu.simulation.model;

public class SeatCellSnapshot {
    private final int seatId;
    private final int tableId;
    private final int row;
    private final int column;
    private final String area;
    private final String status;
    private final boolean occupied;
    private final String groupId;

    public SeatCellSnapshot(int seatId,
                            int tableId,
                            int row,
                            int column,
                            String area,
                            String status) {
        this(seatId, tableId, row, column, area, status, "");
    }

    public SeatCellSnapshot(int seatId,
                            int tableId,
                            int row,
                            int column,
                            String area,
                            String status,
                            String groupId) {
        this.seatId = Math.max(0, seatId);
        this.tableId = Math.max(0, tableId);
        this.row = Math.max(0, row);
        this.column = Math.max(0, column);
        this.area = area == null || area.isBlank() ? "A" : area;
        this.status = normalizeStatus(status);
        this.occupied = "OCCUPIED".equals(this.status);
        this.groupId = groupId == null ? "" : groupId;
    }

    public int getSeatId() {
        return seatId;
    }

    public int getTableId() {
        return tableId;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    public String getArea() {
        return area;
    }

    public String getStatus() {
        return status;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public String getGroupId() {
        return groupId;
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return "FREE";
        }
        String normalized = value.trim().toUpperCase();
        if ("OCCUPIED".equals(normalized) || "CLEANING".equals(normalized)) {
            return normalized;
        }
        return "FREE";
    }
}
