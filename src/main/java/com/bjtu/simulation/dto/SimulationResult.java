package com.bjtu.simulation.dto;

import java.util.List;

import com.bjtu.simulation.model.TableSnapshot;

public class SimulationResult {
    private final long time;
    private final List<Integer> queueSizes;
    private final int totalQueueSize;
    private final int occupiedSeats;
    private final int emptySeats;
    private final String eventMessage;
    private final int arrivedCount;
    private final int abandonedCount;
    private final int abandonedByQueueCount;
    private final int servedCount;
    private final int dineInCount;
    private final int takeawayCount;
    private final int pendingSeatDecisionCount;
    private final int noSeatSwitchToTakeawayCount;
    private final int weatherDrivenTakeawayCount;
    private final int leaveCount;
    private final int normalArrivalCount;
    private final int classPeakArrivalCount;
    private final int rainPeakArrivalCount;
    private final int movementSampleCount;
    private final double totalMovementTimeMinutes;
    private final double avgMovementTimeMinutes;
    private final List<TableSnapshot> tableSnapshots;

    public SimulationResult(long time,
                            List<Integer> queueSizes,
                            int totalQueueSize,
                            int occupiedSeats,
                            int emptySeats,
                            String eventMessage,
                            int arrivedCount,
                            int abandonedCount,
                            int abandonedByQueueCount,
                            int servedCount,
                            int dineInCount,
                            int takeawayCount,
                            int pendingSeatDecisionCount,
                            int noSeatSwitchToTakeawayCount,
                            int weatherDrivenTakeawayCount,
                            int leaveCount,
                            int normalArrivalCount,
                            int classPeakArrivalCount,
                            int rainPeakArrivalCount,
                            int movementSampleCount,
                            double totalMovementTimeMinutes,
                            double avgMovementTimeMinutes,
                            List<TableSnapshot> tableSnapshots) {
        this.time = time;
        this.queueSizes = queueSizes;
        this.totalQueueSize = totalQueueSize;
        this.occupiedSeats = occupiedSeats;
        this.emptySeats = emptySeats;
        this.eventMessage = eventMessage;
        this.arrivedCount = arrivedCount;
        this.abandonedCount = abandonedCount;
        this.abandonedByQueueCount = abandonedByQueueCount;
        this.servedCount = servedCount;
        this.dineInCount = dineInCount;
        this.takeawayCount = takeawayCount;
        this.pendingSeatDecisionCount = pendingSeatDecisionCount;
        this.noSeatSwitchToTakeawayCount = noSeatSwitchToTakeawayCount;
        this.weatherDrivenTakeawayCount = weatherDrivenTakeawayCount;
        this.leaveCount = leaveCount;
        this.normalArrivalCount = normalArrivalCount;
        this.classPeakArrivalCount = classPeakArrivalCount;
        this.rainPeakArrivalCount = rainPeakArrivalCount;
        this.movementSampleCount = movementSampleCount;
        this.totalMovementTimeMinutes = round3(totalMovementTimeMinutes);
        this.avgMovementTimeMinutes = round3(avgMovementTimeMinutes);
        this.tableSnapshots = tableSnapshots;
    }

    public long getTime() {
        return time;
    }

    public List<Integer> getQueueSizes() {
        return queueSizes;
    }

    public int getTotalQueueSize() {
        return totalQueueSize;
    }

    public int getOccupiedSeats() {
        return occupiedSeats;
    }

    public int getEmptySeats() {
        return emptySeats;
    }

    public String getEventMessage() {
        return eventMessage;
    }

    public int getArrivedCount() {
        return arrivedCount;
    }

    public int getAbandonedCount() {
        return abandonedCount;
    }

    public int getAbandonedByQueueCount() {
        return abandonedByQueueCount;
    }

    public int getServedCount() {
        return servedCount;
    }

    public int getDineInCount() {
        return dineInCount;
    }

    public int getTakeawayCount() {
        return takeawayCount;
    }

    public int getPendingSeatDecisionCount() {
        return pendingSeatDecisionCount;
    }

    public int getNoSeatSwitchToTakeawayCount() {
        return noSeatSwitchToTakeawayCount;
    }

    public int getWeatherDrivenTakeawayCount() {
        return weatherDrivenTakeawayCount;
    }

    public int getLeaveCount() {
        return leaveCount;
    }

    public int getNormalArrivalCount() {
        return normalArrivalCount;
    }

    public int getClassPeakArrivalCount() {
        return classPeakArrivalCount;
    }

    public int getRainPeakArrivalCount() {
        return rainPeakArrivalCount;
    }

    public int getMovementSampleCount() {
        return movementSampleCount;
    }

    public double getTotalMovementTimeMinutes() {
        return totalMovementTimeMinutes;
    }

    public double getAvgMovementTimeMinutes() {
        return avgMovementTimeMinutes;
    }

    public List<TableSnapshot> getTableSnapshots() {
        return tableSnapshots;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
