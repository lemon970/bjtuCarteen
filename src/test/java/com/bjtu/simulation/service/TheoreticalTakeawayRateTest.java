package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.TakeawayRateBreakdown;

import org.junit.jupiter.api.Test;

/**
 * 第九轮 B3 / Bug-03 修复后契约:验证 SimulationSummary.theoreticalTakeawayRate 与 takeawayRateBreakdown。
 *
 * 期望(Bug-03 修复后):
 *   p_intent = clamp(packProbability × WeatherFactorPolicy.resolveEffectiveFactor(weather, userFactor), 0, 0.95)
 *   theoretical = clamp(p_intent + (1 - p_intent) × takeawayWindowCount / windowCount, 0, 0.95)
 *
 * 新增 routed 分量描述 ServiceFinishEvent.recordForcedTakeaway:从打包窗口完成
 * 服务的学生被强制打包。修复前公式只算 p_intent,系统性偏低,被
 * TheoreticalTakeawayBaselineAccuracyTest 在 6 个预设里 5 个打红。
 */
class TheoreticalTakeawayRateTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();

    @Test
    void sunnyDefaultShouldFollowBaselineFormula() {
        SimConfig config = baseConfig(0.13, "sunny", 1.0);
        SimulationSummary summary = runService.run(config).getSummary();
        // p_intent = 0.13×1.0 = 0.13;routed = 0.87×(1/5) = 0.174;total = 0.304
        assertEquals(0.304, summary.getTheoreticalTakeawayRate(), 1e-3,
                "sunny: p_intent=0.13 + routed 0.174 = 0.304");
    }

    @Test
    void rainyEmergencyShouldFollowBaselineFormula() {
        SimConfig config = baseConfig(0.20, "rainy", 1.25);
        SimulationSummary summary = runService.run(config).getSummary();
        // canonical(rainy)=1.30, user=1.25, effective=1.625, p_intent=0.20×1.625=0.325
        // routed = 0.675×(1/5) = 0.135;total = 0.460
        assertEquals(0.460, summary.getTheoreticalTakeawayRate(), 1e-3,
                "rainy: p_intent=0.325 + routed 0.135 = 0.460");
    }

    @Test
    void stormyHighFactorShouldFollowBaselineFormulaAfterCanonicalClamp() {
        SimConfig config = baseConfig(0.30, "stormy", 2.0);
        SimulationSummary summary = runService.run(config).getSummary();
        // canonical(stormy)=1.55, user=2.0, raw effective=3.10 → WeatherFactorPolicy clamp 到 3.0
        // p_intent=0.30×3.0=0.90;routed = 0.10×(1/5) = 0.02;total = 0.92
        assertEquals(0.92, summary.getTheoreticalTakeawayRate(), 1e-3,
                "stormy: p_intent=0.90 + routed 0.02 = 0.92");
    }

    @Test
    void extremePackTimesFactorShouldClampAtNinetyFive() {
        SimConfig config = baseConfig(0.95, "snowy", 1.5);
        SimulationSummary summary = runService.run(config).getSummary();
        // p_intent = clamp(0.95×canonical(snowy)×1.5, 0, 0.95) = 0.95(已封顶)
        // routed = 0.05×(1/5) = 0.01;total raw = 0.96 → clamp 到 0.95
        assertEquals(0.95, summary.getTheoreticalTakeawayRate(), 1e-3,
                "p_intent 已饱和到 0.95,加 routed 后再被 clamp(0,0.95)");
    }

    @Test
    void rainEmergencyScenarioShouldExposeBreakdownBetweenObservedAndTheoretical() {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        SimulationSummary summary = report.getSummary();

        // rain_emergency: windowCount=9, takeawayWindowCount=2
        // p_intent=0.325,routed=0.675×(2/9)=0.150,total=0.475
        assertEquals(0.475, summary.getTheoreticalTakeawayRate(), 1e-3,
                "rain_emergency 理论值 = 0.325 + 0.150 = 0.475");

        TakeawayRateBreakdown breakdown = summary.getTakeawayRateBreakdown();
        assertNotNull(breakdown, "breakdown 不能为 null");
        assertEquals(summary.getTakeawayRate(), breakdown.getObservedRate(), 1e-3,
                "breakdown.observedRate 应与 summary.takeawayRate 一致");
        assertEquals(summary.getTheoreticalTakeawayRate(), breakdown.getTheoreticalRate(), 1e-3,
                "breakdown.theoreticalRate 应与 summary.theoreticalTakeawayRate 一致");

        // 雨天场景:观测落在 [0.20, 0.60] 内(预期 32%-50% 留方差)
        assertTrue(breakdown.getObservedRate() >= 0.20 && breakdown.getObservedRate() <= 0.60,
                "rain_emergency 实际打包率偏离合理区间, got " + breakdown.getObservedRate());

        // 三段相加要覆盖大部分实际打包(无座路径不来自 arrived,可能稍偏离;只验证非负且和 ≤ 1)
        double sum = breakdown.getInitialIntentRate() + breakdown.getDynamicFlipRate()
                + breakdown.getNoSeatForcedRate();
        assertTrue(sum >= 0.0 && sum <= 1.0,
                "三段占比相加应在 [0,1], got " + sum);

        // Bug-03 修复后:理论 47.5%,实际 [20%, 60%],相对偏离应 < 30%
        double deviation = Math.abs(breakdown.getObservedRate() - breakdown.getTheoreticalRate())
                / Math.max(1e-6, breakdown.getTheoreticalRate());
        assertTrue(deviation < 0.30,
                "rain_emergency 修复后偏离应 < 30%, deviation=" + deviation);
    }

    private SimConfig baseConfig(double packProb, String weather, double weatherFactor) {
        SimConfig config = new SimConfig();
        config.setSimulationName("theoretical-rate-test");
        config.setDuration(0.5);
        config.setArrivalRate(120);
        config.setQueueLimit(20);
        config.setPackProbability(packProb);
        config.setSeed(20260901L);
        config.getBaseConfig().setTotalSeats(120);
        config.getBaseConfig().setTotalStudents(200);
        config.getBaseConfig().setWindowCount(5);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getWeatherConfig().setCurrentWeather(weather);
        config.getWeatherConfig().setWeatherImpactFactor(weatherFactor);
        return config;
    }
}
