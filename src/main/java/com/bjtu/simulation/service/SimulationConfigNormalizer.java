package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.SimConfig;

public class SimulationConfigNormalizer {

    public SimConfig normalize(SimConfig raw) {
        SimConfig config = raw == null ? new SimConfig() : raw;

        if (config.getBaseConfig() == null) {
            config.setBaseConfig(new SimConfig.BaseConfig());
        }
        if (config.getWeatherConfig() == null) {
            config.setWeatherConfig(new SimConfig.WeatherConfig());
        }
        if (config.getRandomBounds() == null) {
            config.setRandomBounds(new SimConfig.RandomBounds());
        }
        if (config.getPeakConfig() == null) {
            config.setPeakConfig(new SimConfig.PeakConfig());
        }
        if (config.getArrivalDist() == null) {
            config.setArrivalDist(SimConfig.DistributionSpec.poisson());
        }
        if (config.getWindowServiceDist() == null) {
            config.setWindowServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (config.getNormalServiceDist() == null) {
            config.setNormalServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (config.getDiningTimeDist() == null) {
            config.setDiningTimeDist(SimConfig.DistributionSpec.uniform());
        }

        validate(config);
        normalizeMutableDefaults(config);
        return config;
    }

    private void validate(SimConfig config) {
        if (Double.isNaN(config.getDuration()) || Double.isInfinite(config.getDuration()) || config.getDuration() <= 0) {
            throw new IllegalArgumentException("duration must be > 0");
        }
        if (Double.isNaN(config.getArrivalRate()) || Double.isInfinite(config.getArrivalRate()) || config.getArrivalRate() < 0) {
            throw new IllegalArgumentException("arrivalRate must be >= 0");
        }
        if (config.getBaseConfig().getWindowCount() < 1) {
            throw new IllegalArgumentException("windowCount must be >= 1");
        }
        if (config.getBaseConfig().getTakeawayWindowCount() < 0) {
            throw new IllegalArgumentException("takeawayWindowCount must be >= 0");
        }
        if (config.getBaseConfig().getTakeawayWindowCount() > config.getBaseConfig().getWindowCount()) {
            throw new IllegalArgumentException("takeawayWindowCount must be <= windowCount");
        }
        double takeawayServiceTimeMultiplier = config.getBaseConfig().getTakeawayServiceTimeMultiplier();
        if (Double.isNaN(takeawayServiceTimeMultiplier)
                || Double.isInfinite(takeawayServiceTimeMultiplier)
                || takeawayServiceTimeMultiplier < 1.0) {
            throw new IllegalArgumentException("takeawayServiceTimeMultiplier must be >= 1");
        }
        if (config.getBaseConfig().getTotalSeats() < 0) {
            throw new IllegalArgumentException("totalSeats must be >= 0");
        }
        if (config.getBaseConfig().getTotalStudents() < 0) {
            throw new IllegalArgumentException("totalStudents must be >= 0");
        }
        if (config.getQueueLimit() < 0) {
            throw new IllegalArgumentException("queueLimit must be >= 0");
        }
        if (config.getPackProbability() < 0 || config.getPackProbability() > 1) {
            throw new IllegalArgumentException("packProbability must be in [0, 1]");
        }
        if (config.getGroupArrivalProb() < 0 || config.getGroupArrivalProb() > 1) {
            throw new IllegalArgumentException("groupArrivalProb must be in [0, 1]");
        }
        if (config.getPartySize() < 1) {
            throw new IllegalArgumentException("partySize must be >= 1");
        }
        if (Double.isNaN(config.getWalkTimeMean()) || Double.isInfinite(config.getWalkTimeMean()) || config.getWalkTimeMean() < 0) {
            throw new IllegalArgumentException("walkTimeMean must be >= 0");
        }
        if (Double.isNaN(config.getCongestionPenalty()) || Double.isInfinite(config.getCongestionPenalty()) || config.getCongestionPenalty() < 0) {
            throw new IllegalArgumentException("congestionPenalty must be >= 0");
        }
        if (config.getBaseConfig().getNumFourSeatTables() < 0) {
            throw new IllegalArgumentException("numFourSeatTables must be >= 0");
        }
        if (config.getBaseConfig().getNumTwoSeatTables() < 0) {
            throw new IllegalArgumentException("numTwoSeatTables must be >= 0");
        }
        if (config.getBaseConfig().getLargeTableRatio() < 0 || config.getBaseConfig().getLargeTableRatio() > 1) {
            throw new IllegalArgumentException("largeTableRatio must be in [0, 1]");
        }
    }

    private void normalizeMutableDefaults(SimConfig config) {
        if (config.getWeatherConfig().getWeatherImpactFactor() < 0) {
            config.getWeatherConfig().setWeatherImpactFactor(0);
        }
        if (config.getPeakConfig().getClassPeakStartMinute() < 0) {
            config.getPeakConfig().setClassPeakStartMinute(0);
        }
        if (config.getPeakConfig().getClassPeakEndMinute() < config.getPeakConfig().getClassPeakStartMinute()) {
            config.getPeakConfig().setClassPeakEndMinute(config.getPeakConfig().getClassPeakStartMinute());
        }
        if (Double.isNaN(config.getPeakConfig().getClassPeakMultiplier())
                || Double.isInfinite(config.getPeakConfig().getClassPeakMultiplier())
                || config.getPeakConfig().getClassPeakMultiplier() < 1) {
            config.getPeakConfig().setClassPeakMultiplier(1.0);
        }
        config.getPeakConfig().setClassPeakWindows(normalizePeakWindows(config.getPeakConfig().getClassPeakWindows()));

        config.getRandomBounds().setArrivalInterval(Math.max(0, config.getRandomBounds().getArrivalInterval()));
        config.getRandomBounds().setServiceRange(normalizeIntRange(config.getRandomBounds().getServiceRange(), 45, 180));
        config.getRandomBounds().setDiningRange(normalizeIntRange(config.getRandomBounds().getDiningRange(), 900, 2400));
        normalizeDistributionSpec(config.getArrivalDist(), "POISSON");
        normalizeDistributionSpec(config.getWindowServiceDist(), "EXPONENTIAL");
        normalizeDistributionSpec(config.getNormalServiceDist(), "EXPONENTIAL");
        normalizeDistributionSpec(config.getDiningTimeDist(), "UNIFORM");
        // [重构] 到达率由 arrivalRate 统一定义，原因是前端旧 lambda 与到达率不同步会直接造成总人数偏差。
        config.getArrivalDist().setLambda(Math.max(0.0, config.getArrivalRate()));
    }

    private void normalizeDistributionSpec(SimConfig.DistributionSpec spec, String defaultType) {
        if (spec == null) {
            return;
        }
        if (spec.getType() == null || spec.getType().isBlank()) {
            spec.setType(defaultType);
        } else {
            spec.setType(spec.getType().trim().toUpperCase());
        }
        if (Double.isNaN(spec.getLambda()) || Double.isInfinite(spec.getLambda()) || spec.getLambda() < 0) {
            spec.setLambda(0.0);
        }
        if (Double.isNaN(spec.getMean()) || Double.isInfinite(spec.getMean()) || spec.getMean() < 0) {
            spec.setMean(0.0);
        }
        if (Double.isNaN(spec.getStd()) || Double.isInfinite(spec.getStd()) || spec.getStd() < 0) {
            spec.setStd(0.0);
        }
        if (spec.getMin() < 0) {
            spec.setMin(0L);
        }
        if (spec.getMax() < 0) {
            spec.setMax(0L);
        }
        if (spec.getMax() > 0 && spec.getMax() < spec.getMin()) {
            long min = spec.getMin();
            spec.setMin(spec.getMax());
            spec.setMax(min);
        }
    }

    private List<SimConfig.PeakConfig.PeakWindow> normalizePeakWindows(List<SimConfig.PeakConfig.PeakWindow> source) {
        List<SimConfig.PeakConfig.PeakWindow> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }

        for (SimConfig.PeakConfig.PeakWindow peakWindow : source) {
            if (peakWindow == null) {
                continue;
            }
            int start = Math.max(0, peakWindow.getStartMinute());
            int end = Math.max(start, peakWindow.getEndMinute());
            double multiplier = peakWindow.getMultiplier();
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 1.0) {
                multiplier = 1.0;
            }
            normalized.add(new SimConfig.PeakConfig.PeakWindow(start, end, multiplier));
        }
        return normalized;
    }

    private List<Integer> normalizeIntRange(List<Integer> source, int defaultMin, int defaultMax) {
        int min = defaultMin;
        int max = defaultMax;

        if (source != null && source.size() >= 2) {
            int a = source.get(0) == null ? defaultMin : source.get(0);
            int b = source.get(1) == null ? defaultMax : source.get(1);
            min = Math.min(a, b);
            max = Math.max(a, b);
        }

        min = Math.max(1, min);
        max = Math.max(min + 1, max);

        List<Integer> normalized = new ArrayList<>();
        normalized.add(min);
        normalized.add(max);
        return normalized;
    }
}
