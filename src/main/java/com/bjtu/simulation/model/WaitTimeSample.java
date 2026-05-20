package com.bjtu.simulation.model;

public class WaitTimeSample {
    public enum Phase {
        WARMUP,
        STEADY,
        COOLDOWN
    }

    private final long queueEnterTimeSeconds;
    private final long serviceStartTimeSeconds;
    private final double waitMinutes;
    private final int partySize;
    private final int windowId;
    private final String windowType;
    private final int queueLengthAtJoin;
    private final Phase phase;

    public WaitTimeSample(long queueEnterTimeSeconds,
                          long serviceStartTimeSeconds,
                          int partySize,
                          int windowId,
                          String windowType,
                          int queueLengthAtJoin,
                          Phase phase) {
        this.queueEnterTimeSeconds = Math.max(0L, queueEnterTimeSeconds);
        this.serviceStartTimeSeconds = Math.max(this.queueEnterTimeSeconds, serviceStartTimeSeconds);
        this.waitMinutes = (this.serviceStartTimeSeconds - this.queueEnterTimeSeconds) / 60.0;
        this.partySize = Math.max(1, partySize);
        this.windowId = windowId;
        this.windowType = windowType == null || windowType.isBlank() ? "UNKNOWN" : windowType;
        this.queueLengthAtJoin = Math.max(0, queueLengthAtJoin);
        this.phase = phase == null ? Phase.STEADY : phase;
    }

    public long getQueueEnterTimeSeconds() {
        return queueEnterTimeSeconds;
    }

    public long getServiceStartTimeSeconds() {
        return serviceStartTimeSeconds;
    }

    public double getWaitMinutes() {
        return waitMinutes;
    }

    public int getPartySize() {
        return partySize;
    }

    public int getWindowId() {
        return windowId;
    }

    public String getWindowType() {
        return windowType;
    }

    public int getQueueLengthAtJoin() {
        return queueLengthAtJoin;
    }

    public Phase getPhase() {
        return phase;
    }
}
