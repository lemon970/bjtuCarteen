package com.bjtu.simulation.model;

public class ArrivalSample {
    private final long timeSeconds;
    private final long minute;
    private final long intervalSeconds;
    private final double lambdaPerHour;
    private final String arrivalGroup;
    private final int partySize;

    public ArrivalSample(long timeSeconds,
                         long intervalSeconds,
                         double lambdaPerHour,
                         ArrivalGroup arrivalGroup,
                         int partySize) {
        this.timeSeconds = Math.max(0L, timeSeconds);
        this.minute = this.timeSeconds / 60L;
        this.intervalSeconds = Math.max(0L, intervalSeconds);
        this.lambdaPerHour = round3(Math.max(0.0, lambdaPerHour));
        this.arrivalGroup = (arrivalGroup == null ? ArrivalGroup.NORMAL : arrivalGroup).name();
        this.partySize = Math.max(1, partySize);
    }

    public long getTimeSeconds() {
        return timeSeconds;
    }

    public long getMinute() {
        return minute;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public double getLambdaPerHour() {
        return lambdaPerHour;
    }

    public String getArrivalGroup() {
        return arrivalGroup;
    }

    public int getPartySize() {
        return partySize;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
