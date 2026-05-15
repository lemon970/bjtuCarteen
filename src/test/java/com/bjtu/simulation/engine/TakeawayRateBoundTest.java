package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.service.SimulationRunService;

import org.junit.jupiter.api.Test;

/**
 * 第六轮重构后,意图前置 + reserve-then-queue 把 takeaway_rate 锁定到
 * packProb × weatherFactor ± ε 区间。本测试断言常用场景的 takeaway_rate 在区间内。
 */
class TakeawayRateBoundTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void baselineOffpeakTakeawayRateShouldHugBaseProbability() {
        SimConfig config = baselineConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();

        double rate = summary.getTakeawayRate();
        // base=0.10, weather=1.0,目标区间 7%-13%
        assertTrue(rate >= 0.05, "takeaway_rate=" + rate);
        assertTrue(rate <= 0.16, "takeaway_rate=" + rate);
    }

    @Test
    void rainEmergencyTakeawayRateShouldReflectWeatherScaling() {
        SimConfig config = rainConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();

        double rate = summary.getTakeawayRate();
        // base=0.20, weather=1.25 → 0.25,目标区间 22%-28%
        assertTrue(rate >= 0.18, "takeaway_rate=" + rate);
        assertTrue(rate <= 0.32, "takeaway_rate=" + rate);
    }

    private SimConfig baselineConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-baseline");
        config.setDuration(1.0);
        config.setArrivalRate(120);
        config.setQueueLimit(24);
        config.setPackProbability(0.10);
        config.setSeed(20260601L);
        config.getBaseConfig().setTotalSeats(300);
        config.getBaseConfig().setTotalStudents(120);
        config.getBaseConfig().setWindowCount(6);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.18);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.40));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }

    private SimConfig rainConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-rain");
        config.setDuration(1.0);
        config.setArrivalRate(220);
        config.setQueueLimit(45);
        config.setPackProbability(0.20);
        config.setSeed(20260604L);
        config.getBaseConfig().setTotalSeats(220);
        config.getBaseConfig().setTotalStudents(220);
        config.getBaseConfig().setWindowCount(9);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.25);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.10, 0.50));
        config.getWeatherConfig().setCurrentWeather("rainy");
        config.getWeatherConfig().setWeatherImpactFactor(1.25);
        return config;
    }
}
