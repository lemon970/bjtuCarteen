package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SimulationMath {
    private SimulationMath() {
    }

    public static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public static double rate(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : round3((double) numerator / denominator);
    }

    public static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    public static double percentile(List<Double> source, double percentile) {
        if (source == null || source.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>(source);
        Collections.sort(values);
        double safePercentile = clamp(percentile, 0.0, 1.0);
        int index = (int) Math.ceil(safePercentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    public static double trimmedMean(List<Double> source, double trimRate) {
        if (source == null || source.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>(source);
        Collections.sort(values);
        int trim = (int) Math.floor(values.size() * clamp(trimRate, 0.0, 0.45));
        if (trim == 0 || values.size() - trim * 2 <= 0) {
            return mean(values);
        }
        return mean(values.subList(trim, values.size() - trim));
    }
}
