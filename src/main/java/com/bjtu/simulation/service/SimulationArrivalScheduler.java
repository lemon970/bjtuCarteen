package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.engine.SimulationEngine;
import com.bjtu.simulation.engine.StudentArriveEvent;
import com.bjtu.simulation.model.ArrivalGroup;

import org.springframework.stereotype.Service;

@Service
public class SimulationArrivalScheduler {
    private static final int MAX_SCHEDULED_STUDENTS = 1000;
    private int generatedGroupSequence = 1;

    public void schedule(SimulationEngine engine, SimConfig config, long durationSeconds) {
        generatedGroupSequence = 1;
        if (config.getRandomBounds().getArrivalInterval() > 0) {
            scheduleFixedIntervalArrivalEvents(engine, config, durationSeconds);
            return;
        }
        int targetStudents = targetArrivalCount(config, durationSeconds);
        if (targetStudents <= 0) {
            return;
        }
        long durationMinutes = Math.max(1L, (long) Math.ceil(durationSeconds / 60.0));
        List<Double> minuteWeights = normalizedMinuteWeights(durationMinutes, config);
        List<Integer> minutePersonCounts = sampleMinutePersonCounts(engine, minuteWeights, targetStudents);
        int studentIndex = 1;
        long lastArrivalTime = 0L;

        for (long minute = 0; minute < durationMinutes; minute++) {
            int personsThisMinute = minutePersonCounts.get((int) minute);
            if (personsThisMinute <= 0) {
                continue;
            }
            double effectiveRatePerHour = minuteWeights.get((int) minute) * targetStudents * 60.0;
            List<PartyArrival> parties = buildPartyArrivals(engine, config, personsThisMinute);
            List<Long> offsets = exponentialOffsetsWithinMinute(engine, parties.size(), effectiveRatePerHour);

            for (int j = 0; j < parties.size(); j++) {
                PartyArrival party = parties.get(j);
                int partySize = party.partySize();
                if (studentIndex > targetStudents) {
                    return;
                }
                if (studentIndex + partySize - 1 > targetStudents) {
                    partySize = targetStudents - studentIndex + 1;
                }
                if (partySize <= 0) {
                    return;
                }

                long offset = offsets.isEmpty() ? engine.nextLong(1L, 61L) : offsets.get(Math.min(j, offsets.size() - 1));
                long arriveTime = Math.min(durationSeconds, minute * 60 + offset);
                if (arriveTime <= 0 || arriveTime > durationSeconds) {
                    continue;
                }

                ArrivalGroup arrivalGroup = resolveArrivalGroupByTime(arriveTime, durationSeconds, config);
                long intervalSeconds = lastArrivalTime <= 0 ? arriveTime : Math.max(1L, arriveTime - lastArrivalTime);
                engine.recordArrivalSample(arriveTime, intervalSeconds, effectiveRatePerHour, arrivalGroup, partySize);
                engine.scheduleEvent(new StudentArriveEvent(
                        arriveTime,
                        "student-" + studentIndex,
                        arrivalGroup,
                        partySize,
                        party.groupId(),
                        party.groupSize(),
                        0));
                lastArrivalTime = arriveTime;
                studentIndex += partySize;
            }
        }
    }

    private int targetArrivalCount(SimConfig config, long durationSeconds) {
        double arrivalRatePerHour = configuredArrivalRatePerHour(config);
        if (arrivalRatePerHour <= 0.0 || durationSeconds <= 0L) {
            return 0;
        }
        int expectedStudents = (int) Math.round(arrivalRatePerHour * durationSeconds / 3600.0);
        return Math.min(Math.max(0, expectedStudents), effectiveStudentLimit(config));
    }

    private List<Double> normalizedMinuteWeights(long durationMinutes, SimConfig config) {
        List<Double> rawWeights = new ArrayList<>();
        double sum = 0.0;
        for (long minute = 0; minute < durationMinutes; minute++) {
            double weight = minuteArrivalWeight(minute, durationMinutes, config);
            rawWeights.add(weight);
            sum += weight;
        }

        if (sum <= 0.0) {
            double uniform = 1.0 / Math.max(1L, durationMinutes);
            return new ArrayList<>(Collections.nCopies((int) durationMinutes, uniform));
        }

        List<Double> normalized = new ArrayList<>();
        for (double weight : rawWeights) {
            normalized.add(weight / sum);
        }
        return normalized;
    }

    private List<Integer> sampleMinutePersonCounts(SimulationEngine engine, List<Double> minuteWeights, int targetStudents) {
        List<Integer> counts = new ArrayList<>();
        int total = 0;
        for (double weight : minuteWeights) {
            double expectedPersonsThisMinute = Math.max(0.0, targetStudents * weight);
            int sampled = engine.sampleArrivalCountForMinute(expectedPersonsThisMinute * 60.0);
            counts.add(sampled);
            total += sampled;
        }

        rebalanceMinuteCounts(engine, counts, minuteWeights, targetStudents, total);
        return counts;
    }

    private void rebalanceMinuteCounts(SimulationEngine engine,
                                       List<Integer> counts,
                                       List<Double> minuteWeights,
                                       int targetStudents,
                                       int currentTotal) {
        int total = currentTotal;
        while (total < targetStudents) {
            int minute = pickWeightedMinute(engine, minuteWeights);
            counts.set(minute, counts.get(minute) + 1);
            total++;
        }

        while (total > targetStudents) {
            int minute = pickNonEmptyMinute(engine, counts);
            counts.set(minute, counts.get(minute) - 1);
            total--;
        }
    }

    private int pickWeightedMinute(SimulationEngine engine, List<Double> minuteWeights) {
        double roll = engine.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < minuteWeights.size(); i++) {
            cumulative += minuteWeights.get(i);
            if (roll <= cumulative) {
                return i;
            }
        }
        return Math.max(0, minuteWeights.size() - 1);
    }

    private int pickNonEmptyMinute(SimulationEngine engine, List<Integer> counts) {
        int total = 0;
        for (int count : counts) {
            total += Math.max(0, count);
        }
        if (total <= 0) {
            return 0;
        }
        int roll = engine.nextInt(1, total);
        int cumulative = 0;
        for (int i = 0; i < counts.size(); i++) {
            cumulative += Math.max(0, counts.get(i));
            if (roll <= cumulative) {
                return i;
            }
        }
        return 0;
    }

    private List<PartyArrival> buildPartyArrivals(SimulationEngine engine, SimConfig config, int personsThisMinute) {
        List<PartyArrival> parties = new ArrayList<>();
        int remaining = Math.max(0, personsThisMinute);
        while (remaining > 0) {
            int partySize = Math.min(remaining, samplePartySize(engine, config));
            String groupId = partySize > 1 ? nextGroupId(config) : null;
            parties.add(new PartyArrival(partySize, groupId, partySize));
            remaining -= partySize;
        }
        return parties;
    }

    private void scheduleFixedIntervalArrivalEvents(SimulationEngine engine, SimConfig config, long durationSeconds) {
        int intervalSeconds = Math.max(1, config.getRandomBounds().getArrivalInterval());
        int maxStudents = effectiveStudentLimit(config);
        int studentIndex = 1;
        long lastArrivalTime = 0L;

        for (long arriveTime = intervalSeconds; arriveTime <= durationSeconds; arriveTime += intervalSeconds) {
            int partySize = samplePartySize(engine, config);
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
            long interval = lastArrivalTime <= 0 ? arriveTime : Math.max(1L, arriveTime - lastArrivalTime);
            engine.recordArrivalSample(arriveTime, interval, config.getArrivalRate(), arrivalGroup, partySize);
            String groupId = partySize > 1 ? nextGroupId(config) : null;
            engine.scheduleEvent(new StudentArriveEvent(
                    arriveTime,
                    "student-" + studentIndex,
                    arrivalGroup,
                    partySize,
                    groupId,
                    partySize,
                    0));
            lastArrivalTime = arriveTime;
            studentIndex += partySize;
        }
    }

    private int samplePartySize(SimulationEngine engine, SimConfig config) {
        SimConfig.GroupConfig groupConfig = config.getGroupConfig();
        if (groupConfig != null && groupConfig.isEnabled()) {
            if (groupConfig.getGroupCount() > 0 && generatedGroupSequence > groupConfig.getGroupCount()) {
                return 1;
            }
            return engine.nextInt(groupConfig.getSizeMin(), groupConfig.getSizeMax());
        }
        return engine.samplePartySize();
    }

    private String nextGroupId(SimConfig config) {
        SimConfig.GroupConfig groupConfig = config.getGroupConfig();
        if (groupConfig != null && groupConfig.isEnabled()) {
            return "group-" + generatedGroupSequence++;
        }
        return "legacy-group-" + generatedGroupSequence++;
    }

    private record PartyArrival(int partySize, String groupId, int groupSize) {
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
            // [重构] 多峰叠加使用上限保护，原因是重叠课间峰值不能无限线性放大到达率。
            return Math.max(1.0, Math.min(4.5, combinedFactor));
        }

        int start = peakConfig.getClassPeakStartMinute();
        int end = Math.max(start + 1, peakConfig.getClassPeakEndMinute());
        return peakWindowFactorAtMinute(minute, start, end, peakConfig.getClassPeakMultiplier());
    }

    private double minuteArrivalWeight(long minute, long durationMinutes, SimConfig config) {
        double weight = naturalMealFactor(minute, durationMinutes);
        if (isClassPeakEnabled(config)) {
            weight *= arrivalFactorAtMinute(minute, config);
        }
        return Math.max(0.001, weight);
    }

    private double configuredArrivalRatePerHour(SimConfig config) {
        return config == null ? 0.0 : Math.max(0.0, config.getArrivalRate());
    }

    private double naturalMealFactor(long minute, long durationMinutes) {
        double safeDuration = Math.max(1.0, durationMinutes - 1.0);
        double progress = Math.max(0.0, Math.min(1.0, minute / safeDuration));
        double lunchPeak = gaussian(progress, 0.34, 0.13);
        double classBreakPeak = gaussian(progress, 0.58, 0.10);
        double dinnerPeak = gaussian(progress, 0.76, 0.16);
        return Math.max(0.35, Math.min(1.85, 0.45 + 0.72 * lunchPeak + 0.38 * classBreakPeak + 0.62 * dinnerPeak));
    }

    private double gaussian(double x, double center, double width) {
        double safeWidth = Math.max(0.001, width);
        double z = (x - center) / safeWidth;
        return Math.exp(-0.5 * z * z);
    }

    private List<Long> exponentialOffsetsWithinMinute(SimulationEngine engine, int count, double effectiveRatePerHour) {
        if (count <= 0) {
            return List.of();
        }
        List<Long> rawOffsets = new ArrayList<>();
        long cursor = 0L;
        for (int i = 0; i < count; i++) {
            cursor += engine.sampleExponentialInterarrivalSeconds(effectiveRatePerHour);
            rawOffsets.add(cursor);
        }

        long lastRaw = Math.max(1L, rawOffsets.get(rawOffsets.size() - 1));
        double scale = lastRaw > 59L ? 59.0 / lastRaw : 1.0;
        List<Long> normalized = new ArrayList<>();
        long previous = 0L;
        for (long raw : rawOffsets) {
            long offset = Math.max(1L, Math.min(59L, Math.round(raw * scale)));
            if (offset < previous) {
                offset = previous;
            }
            normalized.add(offset);
            previous = offset;
        }
        Collections.sort(normalized);
        return normalized;
    }

    private double peakWindowFactorAtMinute(long minute, int start, int end, double multiplier) {
        int normalizedEnd = Math.max(start + 1, end);
        if (minute < start || minute > normalizedEnd) {
            return 1.0;
        }

        double progress = (minute - start) / (double) Math.max(1, normalizedEnd - start);
        double safeMultiplier = Math.max(1.0, multiplier);
        // [重构] 峰值窗口改为余弦钟形曲线，原因是指数爬升会在窗口末端产生不真实的陡降。
        double smoothPeak = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * progress);
        return 1.0 + (safeMultiplier - 1.0) * smoothPeak;
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
