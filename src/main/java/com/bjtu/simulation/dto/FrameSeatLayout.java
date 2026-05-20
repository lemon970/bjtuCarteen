package com.bjtu.simulation.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bjtu.simulation.model.TableSnapshot;

/**
 * 每帧的桌位紧凑布局,只保留前端时间轴回放需要的座位状态。
 * 与重量级 {@link TableSnapshot} 区分:不带 occupiedSeconds / utilizationRate 等冗余字段。
 */
public class FrameSeatLayout {
    private final int tableId;
    private final int capacity;
    private final int occupiedSeats;
    private final int reservedSeats;
    private final List<String> occupiedGroupIds;
    private final List<String> reservedGroupIds;

    public FrameSeatLayout(int tableId,
                           int capacity,
                           int occupiedSeats,
                           int reservedSeats,
                           List<String> occupiedGroupIds,
                           List<String> reservedGroupIds) {
        this.tableId = tableId;
        this.capacity = Math.max(0, capacity);
        this.occupiedSeats = Math.max(0, Math.min(this.capacity, occupiedSeats));
        int safeReserved = Math.max(0, reservedSeats);
        this.reservedSeats = Math.max(0, Math.min(this.capacity - this.occupiedSeats, safeReserved));
        this.occupiedGroupIds = normalize(occupiedGroupIds, this.occupiedSeats);
        this.reservedGroupIds = normalize(reservedGroupIds, this.reservedSeats);
    }

    public static FrameSeatLayout of(TableSnapshot snapshot) {
        if (snapshot == null) {
            return new FrameSeatLayout(-1, 0, 0, 0, List.of(), List.of());
        }
        return new FrameSeatLayout(
                snapshot.getTableId(),
                snapshot.getCapacity(),
                snapshot.getOccupiedSeats(),
                snapshot.getReservedSeats(),
                snapshot.getOccupiedGroupIds(),
                snapshot.getReservedGroupIds());
    }

    public static List<FrameSeatLayout> ofAll(List<TableSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<FrameSeatLayout> result = new ArrayList<>(snapshots.size());
        for (TableSnapshot snapshot : snapshots) {
            // 跳过完全空桌(occupied=0 && reserved=0)以减小 JSON 体积;
            // 前端缺失 tableId 时按"空桌"渲染。
            if (snapshot != null
                    && snapshot.getOccupiedSeats() == 0
                    && snapshot.getReservedSeats() == 0) {
                continue;
            }
            result.add(of(snapshot));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> normalize(List<String> source, int limitSeats) {
        if (source == null || source.isEmpty() || limitSeats <= 0) {
            return List.of();
        }
        int limit = Math.min(limitSeats, source.size());
        List<String> normalized = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String value = source.get(i);
            normalized.add(value == null ? "" : value);
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

    public List<String> getOccupiedGroupIds() {
        return occupiedGroupIds;
    }

    public List<String> getReservedGroupIds() {
        return reservedGroupIds;
    }
}
