package com.bjtu.simulation.service;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.engine.SimulationEngine;
import com.bjtu.simulation.engine.StudentArriveEvent;
import com.bjtu.simulation.model.ArrivalGroup;

public class SimulationArrivalScheduler {
    private static final int MAX_SCHEDULED_STUDENTS = 1000;

    public void schedule(SimulationEngine engine, SimConfig config, long durationSeconds) {
        if (config.getRandomBounds().getArrivalInterval() > 0) {
            scheduleFixedIntervalArrivalEvents(engine, config, durationSeconds);
            return;
        }
        if (config.getArrivalRate() <= 0) {
            return;
        }
        int maxStudents = effectiveStudentLimit(config);
        long durationMinutes = Math.max(1L, (long) Math.ceil(durationSeconds / 60.0));
        int studentIndex = 1;

        for (long minute = 0; minute < durationMinutes; minute++) {
            double effectiveRatePerHour = config.getArrivalRate();
            if (isClassPeakEnabled(config)) {
                effectiveRatePerHour *= arrivalFactorAtMinute(minute, config);
            }
            int arrivalsThisMinute = engine.sampleArrivalCountForMinute(effectiveRatePerHour);

            for (int j = 0; j < arrivalsThisMinute; j++) {
                int partySize = engine.samplePartySize();
                if (studentIndex > maxStudents) {
                    return;
                }
                if (studentIndex + partySize - 1 > maxStudents) {
                    partySize = maxStudents - studentIndex + 1;
                }
                if (partySize <= 0) {
                    return;
                }

                long offset = engine.nextLong(1L, 61L);
                long arriveTime = Math.min(durationSeconds, minute * 60 + offset);
                if (arriveTime <= 0 || arriveTime > durationSeconds) {
                    continue;
                }

                ArrivalGroup arrivalGroup = resolveArrivalGroupByTime(arriveTime, durationSeconds, config);
                engine.scheduleEvent(new StudentArriveEvent(arriveTime, "student-" + studentIndex, arrivalGroup, partySize));
                studentIndex += partySize;
            }
        }
    }

    private void scheduleFixedIntervalArrivalEvents(SimulationEngine engine, SimConfig config, long durationSeconds) {
        int intervalSeconds = Math.max(1, config.getRandomBounds().getArrivalInterval());
        int maxStudents = effectiveStudentLimit(config);
        int studentIndex = 1;

        for (long arriveTime = intervalSeconds; arriveTime <= durationSeconds; arriveTime += intervalSeconds) {
            int partySize = engine.samplePartySize();
            if (studentIndex > maxStudents) {
                return;
            }
            if (studentIndex + partySize - 1 > maxStudents) {
                partySize = maxStudents - studentIndex + 1;
            }
            if (partySize <= 0) {
                return;
            }
            ArrivalGroup arrivalGroup = resolveArrivalGroupByTime(arriveTime, durationSeconds, config);
            engine.scheduleEvent(new StudentArriveEvent(arriveTime, "student-" + studentIndex, arrivalGroup, partySize));
            studentIndex += partySize;
        }
    }

    private int effectiveStudentLimit(SimConfig config) {
        int configured = Math.max(0, config.getBaseConfig().getTotalStudents());
        if (configured <= 0) {
            return MAX_SCHEDULED_STUDENTS;
        }
        return Math.min(configured, MAX_SCHEDULED_STUDENTS);
    }

    private double arrivalFactorAtMinute(long minute, SimConfig config) {
        SimConfig.PeakConfig peakConfig = config.getPeakConfig();
        if (peakConfig.getClassPeakWindows() != null && !peakConfig.getClassPeakWindows().isEmpty()) {
            double combinedFactor = 1.0;
            for (SimConfig.PeakConfig.PeakWindow peakWindow : peakConfig.getClassPeakWindows()) {
                double windowFactor = peakWindowFactorAtMinute(minute,
                        peakWindow.getStartMinute(),
                        peakWindow.getEndMinute(),
                        peakWindow.getMultiplier());
                combinedFactor += Math.max(0.0, windowFactor - 1.0);
            }
            return Math.max(1.0, combinedFactor);
        }

        int start = peakConfig.getClassPeakStartMinute();
        int end = Math.max(start + 1, peakConfig.getClassPeakEndMinute());
        return peakWindowFactorAtMinute(minute, start, end, peakConfig.getClassPeakMultiplier());
    }

    private double peakWindowFactorAtMinute(long minute, int start, int end, double multiplier) {
        int normalizedEnd = Math.max(start + 1, end);
        if (minute < start || minute > normalizedEnd) {
            return 1.0;
        }

        double progress = (minute - start) / (double) Math.max(1, normalizedEnd - start);
        double safeMultiplier = Math.max(1.0, multiplier);
        return Math.exp(progress * Math.log(safeMultiplier));
    }

    private boolean isClassPeakEnabled(SimConfig config) {
        return config != null
                && config.getPeakConfig() != null
                && config.getPeakConfig().isClassPeakEnabled();
    }

    private ArrivalGroup resolveArrivalGroupByTime(long arriveTimeSeconds, long durationSeconds, SimConfig config) {
        long minute = arriveTimeSeconds / 60;
        String weather = "";
        if (config != null && config.getWeatherConfig() != null && config.getWeatherConfig().getCurrentWeather() != null) {
            weather = config.getWeatherConfig().getCurrentWeather().toLowerCase();
        }
        boolean rainy = weather.contains("rain");
        double progress = durationSeconds <= 0 ? 0 : (double) arriveTimeSeconds / durationSeconds;

        if (rainy && progress >= 0.20 && progress <= 0.50) {
            return ArrivalGroup.RAIN_PEAK;
        }
        if (isClassPeakEnabled(config)) {
            if (isClassPeakActiveAtMinute(minute, config.getPeakConfig())) {
                return ArrivalGroup.CLASS_PEAK;
            }
            return ArrivalGroup.NORMAL;
        }
        if (progress >= 0.45 && progress <= 0.75) {
            return ArrivalGroup.CLASS_PEAK;
        }
        return ArrivalGroup.NORMAL;
    }

    private boolean isClassPeakActiveAtMinute(long minute, SimConfig.PeakConfig peakConfig) {
        if (peakConfig.getClassPeakWindows() != null && !peakConfig.getClassPeakWindows().isEmpty()) {
            for (SimConfig.PeakConfig.PeakWindow peakWindow : peakConfig.getClassPeakWindows()) {
                int start = peakWindow.getStartMinute();
                int end = Math.max(start, peakWindow.getEndMinute());
                if (minute >= start && minute <= end) {
                    return true;
                }
            }
            return false;
        }

        int start = peakConfig.getClassPeakStartMinute();
        int end = Math.max(start, peakConfig.getClassPeakEndMinute());
        return minute >= start && minute <= end;
    }
}
