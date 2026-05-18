package com.bjtu.simulation.dto;

import java.util.List;

public class WaitTimeMetrics {
    private final double rawAvgWaitTimeMinutes;
    private final double steadyAvgWaitTimeMinutes;
    private final double typicalWaitTimeMinutes;
    private final double medianWaitTimeMinutes;
    private final double p75WaitTimeMinutes;
    private final double p90WaitTimeMinutes;
    private final double longWaitRate;
    private final double zeroWaitRate;
    private final double edgeWaitSampleRate;
    private final List<WaitTimeBucket> waitTimeDistribution;
    private final WaitTimeInsight waitTimeInsight;

    public WaitTimeMetrics(double rawAvgWaitTimeMinutes,
                           double steadyAvgWaitTimeMinutes,
                           double typicalWaitTimeMinutes,
                           double medianWaitTimeMinutes,
                           double p75WaitTimeMinutes,
                           double p90WaitTimeMinutes,
                           double longWaitRate,
                           double zeroWaitRate,
                           double edgeWaitSampleRate,
                           List<WaitTimeBucket> waitTimeDistribution,
                           WaitTimeInsight waitTimeInsight) {
        this.rawAvgWaitTimeMinutes = rawAvgWaitTimeMinutes;
        this.steadyAvgWaitTimeMinutes = steadyAvgWaitTimeMinutes;
        this.typicalWaitTimeMinutes = typicalWaitTimeMinutes;
        this.medianWaitTimeMinutes = medianWaitTimeMinutes;
        this.p75WaitTimeMinutes = p75WaitTimeMinutes;
        this.p90WaitTimeMinutes = p90WaitTimeMinutes;
        this.longWaitRate = longWaitRate;
        this.zeroWaitRate = zeroWaitRate;
        this.edgeWaitSampleRate = edgeWaitSampleRate;
        this.waitTimeDistribution = waitTimeDistribution == null ? List.of() : List.copyOf(waitTimeDistribution);
        this.waitTimeInsight = waitTimeInsight == null ? WaitTimeInsight.empty() : waitTimeInsight;
    }

    public static WaitTimeMetrics empty() {
        return new WaitTimeMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of(), WaitTimeInsight.empty());
    }

    public double getRawAvgWaitTimeMinutes() {
        return rawAvgWaitTimeMinutes;
    }

    public double getSteadyAvgWaitTimeMinutes() {
        return steadyAvgWaitTimeMinutes;
    }

    public double getTypicalWaitTimeMinutes() {
        return typicalWaitTimeMinutes;
    }

    public double getMedianWaitTimeMinutes() {
        return medianWaitTimeMinutes;
    }

    public double getP75WaitTimeMinutes() {
        return p75WaitTimeMinutes;
    }

    public double getP90WaitTimeMinutes() {
        return p90WaitTimeMinutes;
    }

    public double getLongWaitRate() {
        return longWaitRate;
    }

    public double getZeroWaitRate() {
        return zeroWaitRate;
    }

    public double getEdgeWaitSampleRate() {
        return edgeWaitSampleRate;
    }

    public List<WaitTimeBucket> getWaitTimeDistribution() {
        return waitTimeDistribution;
    }

    public WaitTimeInsight getWaitTimeInsight() {
        return waitTimeInsight;
    }
}
