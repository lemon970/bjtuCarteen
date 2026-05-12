package com.bjtu.simulation.model;

public class TakeawayDecisionRecord {
    private final long timeSeconds;
    private final String studentId;
    private final String reason;
    private final double finalProbability;
    private final double randomRoll;
    private final double seatUtilizationRate;
    private final double queuePressure;
    private final double waitMinutes;
    private final double studentPreference;
    private final boolean takeaway;
    private final int partySize;

    public TakeawayDecisionRecord(long timeSeconds,
                                  String studentId,
                                  String reason,
                                  double finalProbability,
                                  double randomRoll,
                                  double seatUtilizationRate,
                                  double queuePressure,
                                  double waitMinutes,
                                  double studentPreference,
                                  boolean takeaway,
                                  int partySize) {
        this.timeSeconds = Math.max(0L, timeSeconds);
        this.studentId = studentId == null ? "" : studentId;
        this.reason = reason == null || reason.isBlank() ? "MODEL" : reason;
        this.finalProbability = round3(clamp(finalProbability, 0.0, 1.0));
        this.randomRoll = round3(clamp(randomRoll, 0.0, 1.0));
        this.seatUtilizationRate = round3(clamp(seatUtilizationRate, 0.0, 1.0));
        this.queuePressure = round3(clamp(queuePressure, 0.0, 1.0));
        this.waitMinutes = round3(Math.max(0.0, waitMinutes));
        this.studentPreference = round3(clamp(studentPreference, 0.0, 1.0));
        this.takeaway = takeaway;
        this.partySize = Math.max(1, partySize);
    }

    public long getTimeSeconds() {
        return timeSeconds;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getReason() {
        return reason;
    }

    public double getFinalProbability() {
        return finalProbability;
    }

    public double getRandomRoll() {
        return randomRoll;
    }

    public double getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public double getQueuePressure() {
        return queuePressure;
    }

    public double getWaitMinutes() {
        return waitMinutes;
    }

    public double getStudentPreference() {
        return studentPreference;
    }

    public boolean isTakeaway() {
        return takeaway;
    }

    public int getPartySize() {
        return partySize;
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
