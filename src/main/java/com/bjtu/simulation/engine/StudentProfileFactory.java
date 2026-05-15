package com.bjtu.simulation.engine;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.service.SimulationMath;

class StudentProfileFactory {

    Student create(String id,
                   ArrivalGroup arrivalGroup,
                   int partySize,
                   SimConfig config,
                   int windowCount,
                   SimulationRandomSampler random) {
        return create(id, arrivalGroup, partySize, null, partySize, 0, config, windowCount, random);
    }

    Student create(String id,
                   ArrivalGroup arrivalGroup,
                   int partySize,
                   String groupId,
                   int groupSize,
                   int groupMemberIndex,
                   SimConfig config,
                   int windowCount,
                   SimulationRandomSampler random) {
        List<Double> preferenceRange = config.getRandomBounds() == null
                ? null
                : config.getRandomBounds().getPreferenceRange();
        double minPref = 0.1;
        double maxPref = 0.3;
        if (preferenceRange != null && preferenceRange.size() >= 2) {
            double a = preferenceRange.get(0);
            double b = preferenceRange.get(1);
            minPref = Math.min(a, b);
            maxPref = Math.max(a, b);
        }
        minPref = SimulationMath.clamp(minPref, 0.0, 1.0);
        maxPref = SimulationMath.clamp(maxPref, minPref, 1.0);
        double rawPackPreference = minPref + (maxPref - minPref) * random.nextDouble();
        double packPreference = discretizeProbability(rawPackPreference, 0.05);
        Student.PackPreferenceLevel packPreferenceLevel = resolvePackPreferenceLevel(packPreference);

        double weatherFactor = config.getWeatherConfig() == null
                ? 1.0
                : SimulationMath.clamp(config.getWeatherConfig().getWeatherImpactFactor(), 0.5, 2.0);
        double basePack = SimulationMath.clamp(config.getPackProbability(), 0.0, 1.0);
        double intentProbability = SimulationMath.clamp(basePack * weatherFactor, 0.0, 0.95);
        boolean wantsTakeaway = random.nextDouble() < intentProbability;

        int queueLimit = Math.max(0, config.getQueueLimit());
        Student.PatienceLevel patienceLevel = samplePatienceLevel(random);
        int patienceLimit = resolvePatienceLimit(queueLimit, patienceLevel, random);

        int windowPreference = windowCount == 0 ? 0 : random.nextInt(0, windowCount - 1);
        Student.SeatToleranceLevel seatToleranceLevel = sampleSeatToleranceLevel(packPreferenceLevel, random);

        return new Student(
                id,
                packPreference,
                patienceLimit,
                windowPreference,
                resolveSeatSearchPatience(seatToleranceLevel, patienceLevel),
                arrivalGroup == null ? ArrivalGroup.NORMAL : arrivalGroup,
                packPreferenceLevel,
                patienceLevel,
                seatToleranceLevel,
                Math.max(1, partySize),
                groupId,
                groupSize,
                groupMemberIndex,
                wantsTakeaway);
    }

    private Student.PackPreferenceLevel resolvePackPreferenceLevel(double packPreference) {
        if (packPreference <= 0.18) {
            return Student.PackPreferenceLevel.DINE_IN_BIASED;
        }
        if (packPreference < 0.45) {
            return Student.PackPreferenceLevel.BALANCED;
        }
        return Student.PackPreferenceLevel.TAKEAWAY_BIASED;
    }

    private Student.PatienceLevel samplePatienceLevel(SimulationRandomSampler random) {
        double roll = random.nextDouble();
        if (roll < 0.30) {
            return Student.PatienceLevel.LOW;
        }
        if (roll < 0.75) {
            return Student.PatienceLevel.MEDIUM;
        }
        return Student.PatienceLevel.HIGH;
    }

    private int resolvePatienceLimit(int queueLimit,
                                     Student.PatienceLevel patienceLevel,
                                     SimulationRandomSampler random) {
        if (queueLimit <= 0) {
            return 0;
        }
        return switch (patienceLevel) {
            case LOW -> random.nextInt(Math.max(0, queueLimit - 4), Math.max(0, queueLimit - 1));
            case MEDIUM -> random.nextInt(Math.max(0, queueLimit - 2), queueLimit + 1);
            case HIGH -> random.nextInt(queueLimit, queueLimit + 4);
        };
    }

    private Student.SeatToleranceLevel sampleSeatToleranceLevel(Student.PackPreferenceLevel level,
                                                                SimulationRandomSampler random) {
        double roll = random.nextDouble();
        return switch (level) {
            case TAKEAWAY_BIASED -> roll < 0.60 ? Student.SeatToleranceLevel.LOW
                    : roll < 0.90 ? Student.SeatToleranceLevel.MEDIUM : Student.SeatToleranceLevel.HIGH;
            case BALANCED -> roll < 0.25 ? Student.SeatToleranceLevel.LOW
                    : roll < 0.75 ? Student.SeatToleranceLevel.MEDIUM : Student.SeatToleranceLevel.HIGH;
            case DINE_IN_BIASED -> roll < 0.10 ? Student.SeatToleranceLevel.LOW
                    : roll < 0.55 ? Student.SeatToleranceLevel.MEDIUM : Student.SeatToleranceLevel.HIGH;
        };
    }

    private int resolveSeatSearchPatience(Student.SeatToleranceLevel seatToleranceLevel,
                                          Student.PatienceLevel patienceLevel) {
        int base = switch (seatToleranceLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
        int patienceAdjustment = switch (patienceLevel) {
            case LOW -> -1;
            case MEDIUM -> 0;
            case HIGH -> 1;
        };
        return Math.max(0, Math.min(4, base + patienceAdjustment));
    }

    private double discretizeProbability(double value, double step) {
        if (step <= 0) {
            return SimulationMath.clamp(value, 0.0, 1.0);
        }
        double clamped = SimulationMath.clamp(value, 0.0, 1.0);
        double scaled = Math.round(clamped / step) * step;
        return SimulationMath.clamp(scaled, 0.0, 1.0);
    }
}
