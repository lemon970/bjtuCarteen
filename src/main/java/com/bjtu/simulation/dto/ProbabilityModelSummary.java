package com.bjtu.simulation.dto;

public class ProbabilityModelSummary {
    private final String arrivalCountDistribution;
    private final String interarrivalDistribution;
    private final String serviceTimeDistribution;
    private final String diningTimeDistribution;
    private final double configuredArrivalLambdaPerHour;
    private final double expectedMeanInterarrivalSeconds;
    private final double observedMeanInterarrivalSeconds;
    private final double observedInterarrivalAccuracy;
    private final double observedMinuteCountVarianceMeanRatio;
    private final int arrivalSampleCount;
    private final int takeawayDecisionSampleCount;

    public ProbabilityModelSummary(String arrivalCountDistribution,
                                   String interarrivalDistribution,
                                   String serviceTimeDistribution,
                                   String diningTimeDistribution,
                                   double configuredArrivalLambdaPerHour,
                                   double expectedMeanInterarrivalSeconds,
                                   double observedMeanInterarrivalSeconds,
                                   double observedInterarrivalAccuracy,
                                   double observedMinuteCountVarianceMeanRatio,
                                   int arrivalSampleCount,
                                   int takeawayDecisionSampleCount) {
        this.arrivalCountDistribution = arrivalCountDistribution;
        this.interarrivalDistribution = interarrivalDistribution;
        this.serviceTimeDistribution = serviceTimeDistribution;
        this.diningTimeDistribution = diningTimeDistribution;
        this.configuredArrivalLambdaPerHour = round3(configuredArrivalLambdaPerHour);
        this.expectedMeanInterarrivalSeconds = round3(expectedMeanInterarrivalSeconds);
        this.observedMeanInterarrivalSeconds = round3(observedMeanInterarrivalSeconds);
        this.observedInterarrivalAccuracy = round3(observedInterarrivalAccuracy);
        this.observedMinuteCountVarianceMeanRatio = round3(observedMinuteCountVarianceMeanRatio);
        this.arrivalSampleCount = Math.max(0, arrivalSampleCount);
        this.takeawayDecisionSampleCount = Math.max(0, takeawayDecisionSampleCount);
    }

    public String getArrivalCountDistribution() {
        return arrivalCountDistribution;
    }

    public String getInterarrivalDistribution() {
        return interarrivalDistribution;
    }

    public String getServiceTimeDistribution() {
        return serviceTimeDistribution;
    }

    public String getDiningTimeDistribution() {
        return diningTimeDistribution;
    }

    public double getConfiguredArrivalLambdaPerHour() {
        return configuredArrivalLambdaPerHour;
    }

    public double getExpectedMeanInterarrivalSeconds() {
        return expectedMeanInterarrivalSeconds;
    }

    public double getObservedMeanInterarrivalSeconds() {
        return observedMeanInterarrivalSeconds;
    }

    public double getObservedInterarrivalAccuracy() {
        return observedInterarrivalAccuracy;
    }

    public double getObservedMinuteCountVarianceMeanRatio() {
        return observedMinuteCountVarianceMeanRatio;
    }

    public int getArrivalSampleCount() {
        return arrivalSampleCount;
    }

    public int getTakeawayDecisionSampleCount() {
        return takeawayDecisionSampleCount;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
