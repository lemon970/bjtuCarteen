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
        return tryOccupySeats(requestedSeats, currentTime, null);
    }

    /**
     * 一步式占座(reserve+confirm),供向后兼容旧测试和"立即落座"路径使用。
     * 现代调用流先 tryReserveSeats 再 confirmReservation。
     */
    public SeatAllocation tryOccupySeats(int requestedSeats, long currentTime, String groupId) {
        SeatAllocation reservation = tryReserveSeats(requestedSeats, currentTime, groupId, true);
        if (reservation == null) {
            return null;
        }
        confirmReservation(reservation, currentTime);
        return reservation;
    }

    /**
     * 预定座位:写入 reservedSeats / reservedGroupIds,但不计入 occupiedSeats。
     * 允许跨 2 张相邻桌(tableId 连续)拆分,失败返回 null。
     */
    public SeatAllocation tryReserveSeats(int requestedSeats, long currentTime, String groupId, boolean allowSplit) {
        int seats = Math.max(1, requestedSeats);

        Optional<DiningTable> single = findTableForParty(seats, currentTime);
        if (single.isPresent()) {
            DiningTable table = single.get();
            table.updateDurations(currentTime);
            table.cleaningUntilTime = 0L;
            table.addReservation(seats, groupId);
            return new SeatAllocation(
                    table.tableId,
                    seats,
                    groupId,
                    false,
                    List.of(new TablePart(table.tableId, seats)));
        }

        if (allowSplit && seats >= 2) {
            SeatAllocation split = trySplitAcrossPair(seats, currentTime, groupId);
            if (split != null) {
                return split;
            }
        }
        return null;
    }

    /**
     * 把已 reserve 的座位转 occupied。reserved 计数 -= seats,occupied 计数 += seats。
     */
    public void confirmReservation(SeatAllocation allocation, long currentTime) {
        if (allocation == null) {
            return;
        }
        for (TablePart part : allocation.parts()) {
            DiningTable table = findTableById(part.tableId());
            if (table == null) {
                continue;
            }
            table.updateDurations(currentTime);
            table.cleaningUntilTime = 0L;
            table.confirmReservation(part.seatCount(), allocation.groupId());
        }
    }

    /**
     * 取消预定:学生在未落座前离开(放弃排队、耐心耗尽),归还 reserved 计数。
     */
    public void cancelReservation(SeatAllocation allocation) {
        if (allocation == null) {
            return;
        }
        for (TablePart part : allocation.parts()) {
            DiningTable table = findTableById(part.tableId());
            if (table == null) {
                continue;
            }
            table.cancelReservation(part.seatCount(), allocation.groupId());
        }
    }

    public boolean canReserve(int requestedSeats, long currentTime) {
        int seats = Math.max(1, requestedSeats);
        if (findTableForParty(seats, currentTime).isPresent()) {
            return true;
        }
        if (seats >= 2) {
            return findSplitPair(seats, currentTime) != null;
        }
        return false;
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
        for (TablePart part : allocation.parts()) {
            DiningTable table = findTableById(part.tableId());
            if (table == null) {
                continue;
            }
            table.updateDurations(currentTime);
            int beforeOccupied = table.occupiedSeats;
            table.removeOccupancy(part.seatCount(), allocation.groupId());
            int actuallyRemoved = beforeOccupied - table.occupiedSeats;
            if (actuallyRemoved < part.seatCount()) {
                // 余量仍在 reserved 队列(学生未走到座位就离开),按 cancelReservation 回收
                table.cancelReservation(part.seatCount() - actuallyRemoved, allocation.groupId());
            }
            if (table.occupiedSeats == 0 && table.reservedSeats == 0) {
                table.cleaningUntilTime = Math.max(currentTime, 0L) + CLEANING_SECONDS;
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

    public int getReservedSeats() {
        int reserved = 0;
        for (DiningTable table : tables) {
            reserved += table.reservedSeats;
        }
        return reserved;
    }

    public int getEmptySeats() {
        return Math.max(0, getTotalSeats() - getOccupiedSeats() - getReservedSeats());
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
                    occupiedSeatSeconds,
                    new ArrayList<>(table.occupiedGroupIds),
                    table.reservedSeats,
                    new ArrayList<>(table.reservedGroupIds)));
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
                        status,
                        table.groupIdAt(seatIndex)));
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

    /**
     * 跨 2 张相邻桌拆分(tableId 连续):优先选两桌空位之和最大的对,标 splitGroup=true。
     */
    private SeatAllocation trySplitAcrossPair(int seats, long currentTime, String groupId) {
        SplitPair pair = findSplitPair(seats, currentTime);
        if (pair == null) {
            return null;
        }
        DiningTable first = pair.first;
        DiningTable second = pair.second;

        int firstSeats = Math.min(first.getEmptySeats(), seats);
        int secondSeats = seats - firstSeats;

        first.updateDurations(currentTime);
        first.cleaningUntilTime = 0L;
        first.addReservation(firstSeats, groupId);

        second.updateDurations(currentTime);
        second.cleaningUntilTime = 0L;
        second.addReservation(secondSeats, groupId);

        List<TablePart> parts = List.of(
                new TablePart(first.tableId, firstSeats),
                new TablePart(second.tableId, secondSeats));
        return new SeatAllocation(first.tableId, seats, groupId, true, parts);
    }

    private SplitPair findSplitPair(int seats, long currentTime) {
        long safeTime = Math.max(0L, currentTime);
        SplitPair best = null;
        int bestCombined = -1;
        for (int i = 0; i < tables.size() - 1; i++) {
            DiningTable a = tables.get(i);
            DiningTable b = tables.get(i + 1);
            if (a.cleaningUntilTime > safeTime || b.cleaningUntilTime > safeTime) {
                continue;
            }
            int combined = a.getEmptySeats() + b.getEmptySeats();
            if (combined < seats) {
                continue;
            }
            // 不能完全靠单桌(那走 findTableForParty 的路)。
            if (a.getEmptySeats() >= seats || b.getEmptySeats() >= seats) {
                continue;
            }
            if (combined > bestCombined) {
                bestCombined = combined;
                best = new SplitPair(a, b);
            }
        }
        return best;
    }

    private DiningTable findTableById(int tableId) {
        for (DiningTable table : tables) {
            if (table.tableId == tableId) {
                return table;
            }
        }
        return null;
    }

    private String seatStatus(DiningTable table, int seatIndex, long currentTime) {
        if (seatIndex < table.occupiedSeats) {
            return "OCCUPIED";
        }
        if (seatIndex < table.occupiedSeats + table.reservedSeats) {
            return "RESERVED";
        }
        if (table.occupiedSeats == 0
                && table.reservedSeats == 0
                && table.cleaningUntilTime > Math.max(0L, currentTime)) {
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

    public record TablePart(int tableId, int seatCount) {
    }

    /**
     * 座位分配/预定记录。可能跨多张桌(splitGroup=true 时 parts.size() == 2)。
     */
    public record SeatAllocation(int tableId,
                                 int seatCount,
                                 String groupId,
                                 boolean splitGroup,
                                 List<TablePart> parts) {
        public SeatAllocation(int tableId, int seatCount) {
            this(tableId, seatCount, null, false, List.of(new TablePart(tableId, seatCount)));
        }

        public SeatAllocation(int tableId, int seatCount, String groupId, boolean splitGroup) {
            this(tableId, seatCount, groupId, splitGroup,
                    List.of(new TablePart(tableId, seatCount)));
        }

        public SeatAllocation {
            if (parts == null || parts.isEmpty()) {
                parts = List.of(new TablePart(tableId, seatCount));
            } else {
                parts = List.copyOf(parts);
            }
        }
    }

    private record SplitPair(DiningTable first, DiningTable second) {
    }

    private static class DiningTable {
        private final int tableId;
        private final int capacity;
        private int occupiedSeats;
        private int reservedSeats;
        private long lastUpdatedTime;
        private long occupiedSeconds;
        private long occupiedSeatSeconds;
        private long cleaningUntilTime;
        private final List<String> occupiedGroupIds;
        private final List<String> reservedGroupIds;

        private DiningTable(int tableId, int capacity) {
            this.tableId = tableId;
            this.capacity = Math.max(0, capacity);
            this.occupiedSeats = 0;
            this.reservedSeats = 0;
            this.lastUpdatedTime = 0L;
            this.occupiedSeconds = 0L;
            this.occupiedSeatSeconds = 0L;
            this.cleaningUntilTime = 0L;
            this.occupiedGroupIds = new ArrayList<>();
            this.reservedGroupIds = new ArrayList<>();
        }

        private int getEmptySeats() {
            return Math.max(0, capacity - occupiedSeats - reservedSeats);
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

        private void addReservation(int seats, String groupId) {
            String normalized = groupId == null || groupId.isBlank() ? "" : groupId;
            for (int i = 0; i < Math.max(1, seats); i++) {
                reservedGroupIds.add(normalized);
            }
            reservedSeats = reservedGroupIds.size();
        }

        private void confirmReservation(int seats, String groupId) {
            String normalized = groupId == null || groupId.isBlank() ? "" : groupId;
            int moved = 0;
            // 优先匹配同 groupId
            for (int i = reservedGroupIds.size() - 1; i >= 0 && moved < seats; i--) {
                if (normalized.equals(reservedGroupIds.get(i))) {
                    reservedGroupIds.remove(i);
                    occupiedGroupIds.add(normalized);
                    moved++;
                }
            }
            // 如果还没补齐(异常路径),从尾部继续吃 reserved
            while (moved < seats && !reservedGroupIds.isEmpty()) {
                reservedGroupIds.remove(reservedGroupIds.size() - 1);
                occupiedGroupIds.add(normalized);
                moved++;
            }
            reservedSeats = reservedGroupIds.size();
            occupiedSeats = occupiedGroupIds.size();
        }

        private void cancelReservation(int seats, String groupId) {
            String normalized = groupId == null || groupId.isBlank() ? "" : groupId;
            int removed = 0;
            for (int i = reservedGroupIds.size() - 1; i >= 0 && removed < seats; i--) {
                if (normalized.equals(reservedGroupIds.get(i))) {
                    reservedGroupIds.remove(i);
                    removed++;
                }
            }
            while (removed < seats && !reservedGroupIds.isEmpty()) {
                reservedGroupIds.remove(reservedGroupIds.size() - 1);
                removed++;
            }
            reservedSeats = reservedGroupIds.size();
        }

        private void removeOccupancy(int seats, String groupId) {
            String normalized = groupId == null || groupId.isBlank() ? "" : groupId;
            int removed = 0;
            for (int i = occupiedGroupIds.size() - 1; i >= 0 && removed < seats; i--) {
                if (normalized.equals(occupiedGroupIds.get(i))) {
                    occupiedGroupIds.remove(i);
                    removed++;
                }
            }
            while (removed < seats && !occupiedGroupIds.isEmpty()) {
                occupiedGroupIds.remove(occupiedGroupIds.size() - 1);
                removed++;
            }
            occupiedSeats = occupiedGroupIds.size();
        }

        private String groupIdAt(int seatIndex) {
            if (seatIndex < 0) {
                return "";
            }
            if (seatIndex < occupiedGroupIds.size()) {
                return occupiedGroupIds.get(seatIndex);
            }
            int reservedIndex = seatIndex - occupiedGroupIds.size();
            if (reservedIndex < reservedGroupIds.size()) {
                return reservedGroupIds.get(reservedIndex);
            }
            return "";
        }
    }
}
