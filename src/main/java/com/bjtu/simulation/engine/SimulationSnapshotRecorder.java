package com.bjtu.simulation.engine;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.SimulationResult;

class SimulationSnapshotRecorder {
    private long peakTime = 0L;
    private int peakWindowId = -1;
    private int maxQueueSizeEver = 0;
    private int maxTotalQueueSize = 0;
    private long totalPeakTime = 0L;
    private long totalQueueSizeSum = 0L;
    private int queueSizeSamples = 0;
    private int maxOccupiedSeats = 0;
    private long occupiedSeatsSum = 0L;
    private int occupiedSeatsSamples = 0;

    SimulationResult record(SimulationEngine engine, String message) {
        List<Integer> currentQueues = new ArrayList<>(engine.getCanteenState().getWindows());
        int totalQueueSize = currentQueues.stream().mapToInt(Integer::intValue).sum();
        totalQueueSizeSum += totalQueueSize;
        queueSizeSamples++;
        if (totalQueueSize > maxTotalQueueSize) {
            maxTotalQueueSize = totalQueueSize;
            totalPeakTime = engine.getCurrentTime();
        }

        int occupiedSeats = engine.getCanteenState().getOccupiedSeats();
        occupiedSeatsSum += occupiedSeats;
        occupiedSeatsSamples++;
        maxOccupiedSeats = Math.max(maxOccupiedSeats, occupiedSeats);
        updatePeak(engine.getCurrentTime(), currentQueues);

        int emptySeats = Math.max(0, engine.getCanteenState().getTotalSeats() - occupiedSeats);
        return new SimulationResult(
                engine.getCurrentTime(),
                currentQueues,
                totalQueueSize,
                occupiedSeats,
                emptySeats,
                message == null ? "" : message,
                engine.getArrivedCount(),
                engine.getAbandonedCount(),
                engine.getAbandonedByQueueCount(),
                engine.getServedCount(),
                engine.getDineInCount(),
                engine.getTakeawayCount(),
                engine.getPendingSeatDecisionCount(),
                engine.getNoSeatSwitchToTakeawayCount(),
                engine.getWeatherDrivenTakeawayCount(),
                engine.getLeaveCount(),
                engine.getNormalArrivalCount(),
                engine.getClassPeakArrivalCount(),
                engine.getRainPeakArrivalCount(),
                engine.getMovementSampleCount(),
                engine.getTotalMovementTimeMinutes(),
                engine.getAvgMovementTimeMinutes(),
                engine.getCanteenState().getTableSnapshots(engine.getCurrentTime()));
    }

    int getMaxQueueSizeEver() {
        return maxQueueSizeEver;
    }

    int getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    double getAvgTotalQueueSize() {
        return queueSizeSamples == 0 ? 0 : (double) totalQueueSizeSum / queueSizeSamples;
    }

    int getMaxOccupiedSeats() {
        return maxOccupiedSeats;
    }

    double getAvgOccupiedSeats() {
        return occupiedSeatsSamples == 0 ? 0 : (double) occupiedSeatsSum / occupiedSeatsSamples;
    }

    long getPeakTime() {
        return peakTime;
    }

    long getTotalPeakTime() {
        return totalPeakTime;
    }

    int getPeakWindowId() {
        return peakWindowId;
    }

    private void updatePeak(long currentTime, List<Integer> queues) {
        for (int i = 0; i < queues.size(); i++) {
            if (queues.get(i) > maxQueueSizeEver) {
                maxQueueSizeEver = queues.get(i);
                peakTime = currentTime;
                peakWindowId = i;
            }
        }
    }
}
