package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.service.SimulationRunService;

import org.junit.jupiter.api.Test;

/**
 * 第七轮重构后:WeatherFactorPolicy 把 weather 类型 × user factor 合并为 effectiveFactor;
 * StudentProfileFactory 用 effectiveFactor 决定到达意图,ServiceFinishEvent 在服务完成时
 * 通过 TakeawayDecisionPolicy 进一步根据队列/座位压力做动态翻转。所以最终
 * takeaway_rate ≈ packProb × effectiveFactor + 动态压力 bump。本测试断言端到端区间。
 */
class TakeawayRateBoundTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void baselineOffpeakTakeawayRateShouldHugBaseProbability() {
        SimConfig config = baselineConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();

        double rate = summary.getTakeawayRate();
        // base=0.10, sunny canonical=1.0 → intent ≈ 10%;
        // 加上 ServiceFinishEvent 动态决策的低压力 bump,端到端 12-30%
        assertTrue(rate >= 0.12, "takeaway_rate=" + rate);
        assertTrue(rate <= 0.30, "takeaway_rate=" + rate);
    }

    @Test
    void rainEmergencyTakeawayRateShouldReflectWeatherScaling() {
        SimConfig config = rainConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();

        double rate = summary.getTakeawayRate();
        // base=0.20, rainy canonical=1.30 × user 1.25 = 1.625 → intent ≈ 32.5%;
        // 加上动态决策在 rain_emergency 高压场景下的 bump,端到端 30-55%
        assertTrue(rate >= 0.30, "takeaway_rate=" + rate);
        assertTrue(rate <= 0.55, "takeaway_rate=" + rate);
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
