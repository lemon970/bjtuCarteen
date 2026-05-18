package com.bjtu.simulation.dto;

public class WaitTimeBucket {
    private final String label;
    private final int count;
    private final double rate;

    public WaitTimeBucket(String label, int count, double rate) {
        this.label = label;
        this.count = Math.max(0, count);
        this.rate = Math.max(0.0, rate);
    }

    public String getLabel() {
        return label;
    }

    public int getCount() {
        return count;
    }

    public double getRate() {
        return rate;
    }
}
