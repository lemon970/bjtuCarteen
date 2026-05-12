package com.bjtu.simulation.dto;

public class QueueTheoryMetrics {
    private final boolean enabled;
    private final boolean stable;
    private final String modelType;
    private final int serverCount;
    private final double arrivalRatePerHour;
    private final double serviceRatePerHour;
    private final double utilization;
    private final Double expectedQueueLength;
    private final Double expectedSystemLength;
    private final Double expectedQueueWaitMinutes;
    private final Double expectedSystemWaitMinutes;

    public QueueTheoryMetrics(boolean enabled,
                              boolean stable,
                              String modelType,
                              int serverCount,
                              double arrivalRatePerHour,
                              double serviceRatePerHour,
                              double utilization,
                              Double expectedQueueLength,
                              Double expectedSystemLength,
                              Double expectedQueueWaitMinutes,
                              Double expectedSystemWaitMinutes) {
        this.enabled = enabled;
        this.stable = stable;
        this.modelType = modelType;
        this.serverCount = serverCount;
        this.arrivalRatePerHour = round3(arrivalRatePerHour);
        this.serviceRatePerHour = round3(serviceRatePerHour);
        this.utilization = round3(utilization);
        this.expectedQueueLength = roundNullable(expectedQueueLength);
        this.expectedSystemLength = roundNullable(expectedSystemLength);
        this.expectedQueueWaitMinutes = roundNullable(expectedQueueWaitMinutes);
        this.expectedSystemWaitMinutes = roundNullable(expectedSystemWaitMinutes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isStable() {
        return stable;
    }

    public String getModelType() {
        return modelType;
    }

    public int getServerCount() {
        return serverCount;
    }

    public double getArrivalRatePerHour() {
        return arrivalRatePerHour;
    }

    public double getServiceRatePerHour() {
        return serviceRatePerHour;
    }

    public double getUtilization() {
        return utilization;
    }

    public Double getExpectedQueueLength() {
        return expectedQueueLength;
    }

    public Double getExpectedSystemLength() {
        return expectedSystemLength;
    }

    public Double getExpectedQueueWaitMinutes() {
        return expectedQueueWaitMinutes;
    }

    public Double getExpectedSystemWaitMinutes() {
        return expectedSystemWaitMinutes;
    }

    private Double roundNullable(Double value) {
        return value == null ? null : round3(value);
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
