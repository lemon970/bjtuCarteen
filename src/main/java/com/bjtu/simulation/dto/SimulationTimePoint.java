package com.bjtu.simulation.dto;

import java.util.List;

import com.bjtu.simulation.model.TableSnapshot;

public class SimulationTimePoint {
    private final long timeSeconds;
    private final long minute;
    private final List<Integer> windowQueueSizes;
    private final List<String> windowTypes;
    private final int windowCount;
    private final int normalWindowCount;
    private final int takeawayWindowCount;
    private final int totalQueueSize;
    private final int normalWindowQueueSize;
    private final int takeawayWindowQueueSize;
    private final int queueingStudentCount;
    private final int busiestWindowId;
    private final int busiestWindowQueueSize;
    private final int totalSeats;
    private final int occupiedSeats;
    private final int diningStudentCount;
    private final int emptySeats;
    private final double seatUtilizationRate;
    private final String eventMessage;
    private final int cumulativeArrivedCount;
    private final int cumulativeNormalArrivalCount;
    private final int cumulativeClassPeakArrivalCount;
    private final int cumulativeRainPeakArrivalCount;
    private final int cumulativeAbandonedCount;
    private final int cumulativeAbandonedByQueueCount;
    private final int cumulativeServedCount;
    private final int cumulativeDineInCount;
    private final int cumulativeTakeawayCount;
    private final int cumulativePendingSeatDecisionCount;
    private final int cumulativeNoSeatSwitchToTakeawayCount;
    private final int cumulativeWeatherDrivenTakeawayCount;
    private final int cumulativeLeaveCount;
    private final int movementSampleCount;
    private final double totalMovementTimeMinutes;
    private final double avgMovementTimeMinutes;
    private final List<TableSnapshot> tableSnapshots;

    public SimulationTimePoint(long timeSeconds,
                               long minute,
                               List<Integer> windowQueueSizes,
                               List<String> windowTypes,
                               int windowCount,
                               int normalWindowCount,
                               int takeawayWindowCount,
                               int totalQueueSize,
                               int normalWindowQueueSize,
                               int takeawayWindowQueueSize,
                               int queueingStudentCount,
                               int busiestWindowId,
                               int busiestWindowQueueSize,
                               int totalSeats,
                               int occupiedSeats,
                               int diningStudentCount,
                               int emptySeats,
                               double seatUtilizationRate,
                               String eventMessage,
                               int cumulativeArrivedCount,
                               int cumulativeNormalArrivalCount,
                               int cumulativeClassPeakArrivalCount,
                               int cumulativeRainPeakArrivalCount,
                               int cumulativeAbandonedCount,
                               int cumulativeAbandonedByQueueCount,
                               int cumulativeServedCount,
                               int cumulativeDineInCount,
                               int cumulativeTakeawayCount,
                               int cumulativePendingSeatDecisionCount,
                               int cumulativeNoSeatSwitchToTakeawayCount,
                               int cumulativeWeatherDrivenTakeawayCount,
                               int cumulativeLeaveCount,
                               int movementSampleCount,
                               double totalMovementTimeMinutes,
                               double avgMovementTimeMinutes,
                               List<TableSnapshot> tableSnapshots) {
        this.timeSeconds = timeSeconds;
        this.minute = minute;
        this.windowQueueSizes = windowQueueSizes;
        this.windowTypes = windowTypes;
        this.windowCount = windowCount;
        this.normalWindowCount = normalWindowCount;
        this.takeawayWindowCount = takeawayWindowCount;
        this.totalQueueSize = totalQueueSize;
        this.normalWindowQueueSize = normalWindowQueueSize;
        this.takeawayWindowQueueSize = takeawayWindowQueueSize;
        this.queueingStudentCount = queueingStudentCount;
        this.busiestWindowId = busiestWindowId;
        this.busiestWindowQueueSize = busiestWindowQueueSize;
        this.totalSeats = totalSeats;
        this.occupiedSeats = occupiedSeats;
        this.diningStudentCount = diningStudentCount;
        this.emptySeats = emptySeats;
        this.seatUtilizationRate = seatUtilizationRate;
        this.eventMessage = eventMessage;
        this.cumulativeArrivedCount = cumulativeArrivedCount;
        this.cumulativeNormalArrivalCount = cumulativeNormalArrivalCount;
        this.cumulativeClassPeakArrivalCount = cumulativeClassPeakArrivalCount;
        this.cumulativeRainPeakArrivalCount = cumulativeRainPeakArrivalCount;
        this.cumulativeAbandonedCount = cumulativeAbandonedCount;
        this.cumulativeAbandonedByQueueCount = cumulativeAbandonedByQueueCount;
        this.cumulativeServedCount = cumulativeServedCount;
        this.cumulativeDineInCount = cumulativeDineInCount;
        this.cumulativeTakeawayCount = cumulativeTakeawayCount;
        this.cumulativePendingSeatDecisionCount = cumulativePendingSeatDecisionCount;
        this.cumulativeNoSeatSwitchToTakeawayCount = cumulativeNoSeatSwitchToTakeawayCount;
        this.cumulativeWeatherDrivenTakeawayCount = cumulativeWeatherDrivenTakeawayCount;
        this.cumulativeLeaveCount = cumulativeLeaveCount;
        this.movementSampleCount = movementSampleCount;
        this.totalMovementTimeMinutes = totalMovementTimeMinutes;
        this.avgMovementTimeMinutes = avgMovementTimeMinutes;
        this.tableSnapshots = tableSnapshots;
    }

    public long getTimeSeconds() {
        return timeSeconds;
    }

    public long getMinute() {
        return minute;
    }

    public List<Integer> getWindowQueueSizes() {
        return windowQueueSizes;
    }

    public List<String> getWindowTypes() {
        return windowTypes;
    }

    public int getWindowCount() {
        return windowCount;
    }

    public int getNormalWindowCount() {
        return normalWindowCount;
    }

    public int getTakeawayWindowCount() {
        return takeawayWindowCount;
    }

    public int getTotalQueueSize() {
        return totalQueueSize;
    }

    public int getNormalWindowQueueSize() {
        return normalWindowQueueSize;
    }

    public int getTakeawayWindowQueueSize() {
        return takeawayWindowQueueSize;
    }

    public int getQueueingStudentCount() {
        return queueingStudentCount;
    }

    public int getBusiestWindowId() {
        return busiestWindowId;
    }

    public int getBusiestWindowQueueSize() {
        return busiestWindowQueueSize;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public int getOccupiedSeats() {
        return occupiedSeats;
    }

    public int getDiningStudentCount() {
        return diningStudentCount;
    }

    public int getEmptySeats() {
        return emptySeats;
    }

    public double getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public String getEventMessage() {
        return eventMessage;
    }

    public int getCumulativeArrivedCount() {
        return cumulativeArrivedCount;
    }

    public int getCumulativeNormalArrivalCount() {
        return cumulativeNormalArrivalCount;
    }

    public int getCumulativeClassPeakArrivalCount() {
        return cumulativeClassPeakArrivalCount;
    }

    public int getCumulativeRainPeakArrivalCount() {
        return cumulativeRainPeakArrivalCount;
    }

    public int getCumulativeAbandonedCount() {
        return cumulativeAbandonedCount;
    }

    public int getCumulativeAbandonedByQueueCount() {
        return cumulativeAbandonedByQueueCount;
    }

    public int getCumulativeServedCount() {
        return cumulativeServedCount;
    }

    public int getCumulativeDineInCount() {
        return cumulativeDineInCount;
    }

    public int getCumulativeTakeawayCount() {
        return cumulativeTakeawayCount;
    }

    public int getCumulativePendingSeatDecisionCount() {
        return cumulativePendingSeatDecisionCount;
    }

    public int getCumulativeNoSeatSwitchToTakeawayCount() {
        return cumulativeNoSeatSwitchToTakeawayCount;
    }

    public int getCumulativeWeatherDrivenTakeawayCount() {
        return cumulativeWeatherDrivenTakeawayCount;
    }

    public int getCumulativeLeaveCount() {
        return cumulativeLeaveCount;
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
}
