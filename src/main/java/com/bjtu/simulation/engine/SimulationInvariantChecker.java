package com.bjtu.simulation.engine;

class SimulationInvariantChecker {

    void validate(SimulationEngine engine) {
        for (Integer queueSize : engine.getCanteenState().getWindowQueues()) {
            if (queueSize == null || queueSize < 0) {
                throw new IllegalStateException("queue size must be >= 0");
            }
        }
        if (engine.getCanteenState().getOccupiedSeats() < 0) {
            throw new IllegalStateException("occupiedSeats must be >= 0");
        }
        if (engine.getCanteenState().getOccupiedSeats() > engine.getCanteenState().getTotalSeats()) {
            throw new IllegalStateException("occupiedSeats must be <= totalSeats");
        }
        if (engine.getServedCount() > engine.getArrivedCount()) {
            throw new IllegalStateException("servedCount cannot exceed arrivedCount");
        }
        if (engine.getLeaveCount() > engine.getServedCount()) {
            throw new IllegalStateException("leaveCount cannot exceed servedCount");
        }
        if (engine.getPendingSeatDecisionCount() < 0) {
            throw new IllegalStateException("pendingSeatDecisionCount must be >= 0");
        }
        if (engine.getDineInCount() + engine.getTakeawayCount()
                + engine.getPostServiceNoSeatCount()
                + engine.getPendingSeatDecisionCount() != engine.getServedCount()) {
            throw new IllegalStateException(
                    "dineInCount + takeawayCount + postServiceNoSeatCount + pendingSeatDecisionCount must equal servedCount");
        }
        if (engine.getAbandonedByQueueCount() > engine.getAbandonedCount()) {
            throw new IllegalStateException("abandonedByQueueCount cannot exceed abandonedCount");
        }
        if (engine.getNoSeatSwitchToTakeawayCount() > engine.getTakeawayCount()) {
            throw new IllegalStateException("noSeatSwitchToTakeawayCount cannot exceed takeawayCount");
        }
        if (engine.getNormalArrivalCount() + engine.getClassPeakArrivalCount() + engine.getRainPeakArrivalCount() != engine.getArrivedCount()) {
            throw new IllegalStateException("arrival group counts must equal arrivedCount");
        }

        int servedByWindow = 0;
        for (int count : engine.getWindowServedCounts()) {
            if (count < 0) {
                throw new IllegalStateException("window served count must be >= 0");
            }
            servedByWindow += count;
        }
        if (servedByWindow != engine.getServedCount()) {
            throw new IllegalStateException("sum(windowServedCounts) must equal servedCount");
        }
        if (engine.getNormalWindowServedCount() + engine.getTakeawayWindowServedCount() != engine.getServedCount()) {
            throw new IllegalStateException("normalWindowServedCount + takeawayWindowServedCount must equal servedCount");
        }
    }
}
