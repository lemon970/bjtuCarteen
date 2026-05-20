package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.Student;

class StudentProfileFactoryTakeawayIntentTest {

    private static final int SAMPLE_COUNT = 5000;
    private static final long SEED = 20260601L;

    @Test
    void wantsTakeawayShouldFollowBaseProbabilityUnderSunnyWeather() {
        SimConfig config = newConfig(0.20, "sunny", 1.0);
        double rate = sampleTakeawayRate(config);
        assertTrue(rate >= 0.16 && rate <= 0.24,
                "wantsTakeaway rate=" + rate + " should be near base 0.20");
    }

    @Test
    void wantsTakeawayShouldScaleWithWeatherFactor() {
        // rainy canonical=1.30 × user 1.25 = 1.625 → intent ≈ 0.20 × 1.625 = 0.325
        SimConfig config = newConfig(0.20, "rainy", 1.25);
        double rate = sampleTakeawayRate(config);
        assertTrue(rate >= 0.28 && rate <= 0.37,
                "wantsTakeaway rate=" + rate + " should reflect weather scaling");
    }

    private double sampleTakeawayRate(SimConfig config) {
        StudentProfileFactory factory = new StudentProfileFactory();
        SimulationRandomSampler sampler = new SimulationRandomSampler(new Random(SEED));
        int hits = 0;
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            Student student = factory.create(
                    "student-" + i,
                    ArrivalGroup.NORMAL,
                    1,
                    config,
                    6,
                    sampler);
            if (student.wantsTakeaway()) {
                hits++;
            }
        }
        return (double) hits / SAMPLE_COUNT;
    }

    private SimConfig newConfig(double packProbability, String weather, double weatherFactor) {
        SimConfig config = new SimConfig();
        config.setPackProbability(packProbability);
        config.setQueueLimit(20);
        config.getRandomBounds().setPreferenceRange(List.of(0.05, 0.40));
        config.getWeatherConfig().setCurrentWeather(weather);
        config.getWeatherConfig().setWeatherImpactFactor(weatherFactor);
        return config;
    }
}
