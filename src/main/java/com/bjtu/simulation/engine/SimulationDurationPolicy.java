package com.bjtu.simulation.engine;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.WaitTimeSample;
import com.bjtu.simulation.service.SimulationMath;

class SimulationDurationPolicy {
    private final SimConfig config;
    private final SimulationRandomSampler randomSampler;

    SimulationDurationPolicy(SimConfig config, SimulationRandomSampler randomSampler) {
        this.config = config;
        this.randomSampler = randomSampler;
    }

    int sampleArrivalCountForMinute(double effectiveArrivalRatePerHour) {
        double lambdaPerHour = Math.max(0.0, effectiveArrivalRatePerHour);
        if (lambdaPerHour <= 0.0 && config.getArrivalDist().getLambda() > 0) {
            lambdaPerHour = config.getArrivalDist().getLambda();
        }
        double lambdaPerMinute = Math.max(0.0, lambdaPerHour / 60.0);
        String type = randomSampler.normalizeDistributionType(config.getArrivalDist(), "POISSON");

        if ("FIXED".equals(type)) {
            return (int) Math.floor(lambdaPerMinute);
        }
        if ("UNIFORM".equals(type)) {
            long min = Math.max(0L, config.getArrivalDist().getMin());
            long max = config.getArrivalDist().getMax() > 0
                    ? Math.max(min, config.getArrivalDist().getMax())
                    : Math.max(min, (long) Math.ceil(lambdaPerMinute * 2));
            return (int) randomSampler.nextLong(min, max + 1);
        }
        return randomSampler.samplePoisson(lambdaPerMinute);
    }

    long sampleExponentialInterarrivalSeconds(double effectiveArrivalRatePerHour) {
        double lambdaPerHour = Math.max(0.0, effectiveArrivalRatePerHour);
        if (lambdaPerHour <= 0.0 && config.getArrivalDist().getLambda() > 0) {
            lambdaPerHour = config.getArrivalDist().getLambda();
        }
        if (lambdaPerHour <= 0.0) {
            return 60L;
        }
        double meanSeconds = 3600.0 / lambdaPerHour;
        double u = Math.max(1.0e-12, 1.0 - randomSampler.nextDouble());
        return Math.max(1L, Math.round(-Math.log(u) * meanSeconds));
    }

    int samplePartySize() {
        int configuredPartySize = Math.max(1, config.getPartySize());
        if (configuredPartySize <= 1
                || randomSampler.nextDouble() >= SimulationMath.clamp(config.getGroupArrivalProb(), 0.0, 1.0)) {
            return 1;
        }
        return configuredPartySize;
    }

    long resolveServiceTimeSeconds(boolean takeawayWindow) {
        return resolveServiceTimeSeconds(takeawayWindow, takeawayWindow);
    }

    long resolveServiceTimeSeconds(boolean takeawayWindow, boolean willTakeaway) {
        SimConfig.DistributionSpec spec = takeawayWindow
                ? config.getWindowServiceDist()
                : config.getNormalServiceDist();
        long sampled = randomSampler.sampleDurationSeconds(spec, serviceRangeMin(), serviceRangeMax());
        if (!willTakeaway) {
            return sampled;
        }

        double multiplier = config.getBaseConfig() == null
                ? 1.15
                : config.getBaseConfig().getTakeawayServiceTimeMultiplier();
        multiplier = Double.isNaN(multiplier) || Double.isInfinite(multiplier) ? 1.15 : Math.max(1.0, multiplier);
        if (!takeawayWindow && willTakeaway) {
            multiplier *= 1.10;
        }
        return Math.max(sampled, Math.round(sampled * multiplier));
    }

    long resolveDiningTimeSeconds() {
        return randomSampler.sampleDurationSeconds(config.getDiningTimeDist(), diningRangeMin(), diningRangeMax());
    }

    long resolveMovementTimeSeconds(CanteenState state, int pendingSeatDecisionCount) {
        double walkTimeMean = Math.max(0.0, config.getWalkTimeMean());
        if (walkTimeMean <= 0.0) {
            return 0L;
        }
        double pressure = currentPeopleInSystem(state, pendingSeatDecisionCount) / (double) maxPeopleCapacity(state);
        double movementTime = walkTimeMean * (1.0 + Math.max(0.0, config.getCongestionPenalty()) * pressure);
        return Math.max(0L, Math.round(movementTime));
    }

    WaitTimeSample.Phase resolveWaitPhase(long serviceStartTimeSeconds) {
        long durationSeconds = Math.max(0L, Math.round(config.getDuration() * 3600.0));
        if (durationSeconds <= 0L) {
            return WaitTimeSample.Phase.STEADY;
        }
        double ratio = (double) serviceStartTimeSeconds / durationSeconds;
        if (ratio < 0.10) {
            return WaitTimeSample.Phase.WARMUP;
        }
        return ratio >= 0.90 ? WaitTimeSample.Phase.COOLDOWN : WaitTimeSample.Phase.STEADY;
    }

    private long serviceRangeMin() {
        return rangeValue(config.getRandomBounds().getServiceRange(), 0, 60L);
    }

    private long serviceRangeMax() {
        return rangeValue(config.getRandomBounds().getServiceRange(), 1, 300L);
    }

    private long diningRangeMin() {
        return rangeValue(config.getRandomBounds().getDiningRange(), 0, 600L);
    }

    private long diningRangeMax() {
        return rangeValue(config.getRandomBounds().getDiningRange(), 1, 1800L);
    }

    private long rangeValue(List<Integer> range, int index, long fallback) {
        if (range == null || range.size() <= index || range.get(index) == null) {
            return fallback;
        }
        return Math.max(1L, range.get(index));
    }

    private int currentPeopleInSystem(CanteenState state, int pendingSeatDecisionCount) {
        int queueSize = 0;
        for (int size : state.getWindowQueues()) {
            queueSize += Math.max(0, size);
        }
        return queueSize + state.getOccupiedSeats() + Math.max(0, pendingSeatDecisionCount);
    }

    private int maxPeopleCapacity(CanteenState state) {
        int seatCapacity = Math.max(0, state.getTotalSeats());
        int queueCapacity = Math.max(0, config.getQueueLimit()) * Math.max(0, state.getWindowCount());
        return Math.max(1, seatCapacity + queueCapacity);
    }
}
