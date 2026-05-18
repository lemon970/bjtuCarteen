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
 * 第九轮 B3:验证 SimulationSummary.theoreticalTakeawayRate 与 takeawayRateBreakdown 的契约。
 *
 * 期望:theoretical = clamp(packProbability × WeatherFactorPolicy.resolveEffectiveFactor(weather, userFactor), 0, 0.95)。
 * 这是 StudentProfileFactory.intentProbability 的解析期望值,前端会用它代替原始 packProbability 作为偏离基准。
 */
class TheoreticalTakeawayRateTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();

    @Test
    void sunnyDefaultShouldEqualPackProbability() {
        SimConfig config = baseConfig(0.13, "sunny", 1.0);
        SimulationSummary summary = runService.run(config).getSummary();
        assertEquals(0.13, summary.getTheoreticalTakeawayRate(), 1e-3,
                "sunny 1.0 × 1.0 应该 = packProbability");
    }

    @Test
    void rainyEmergencyShouldEqualPackTimesEffectiveFactor() {
        SimConfig config = baseConfig(0.20, "rainy", 1.25);
        SimulationSummary summary = runService.run(config).getSummary();
        // canonical(rainy)=1.30, user=1.25, effective=1.625, 0.20×1.625=0.325
        assertEquals(0.325, summary.getTheoreticalTakeawayRate(), 1e-3,
                "rainy + factor=1.25 → 0.20 × 1.30 × 1.25 = 0.325");
    }

    @Test
    void stormyHighFactorShouldClampAtCanonicalCeiling() {
        SimConfig config = baseConfig(0.30, "stormy", 2.0);
        SimulationSummary summary = runService.run(config).getSummary();
        // canonical(stormy)=1.55, user=2.0, raw effective=3.10 → clamp 到 3.0,0.30×3.0=0.90
        assertEquals(0.90, summary.getTheoreticalTakeawayRate(), 1e-3,
                "stormy × 2.0 raw 3.10 应被 WeatherFactorPolicy clamp 到 3.0,然后 ×0.30=0.90");
    }

    @Test
    void extremePackTimesFactorShouldClampAtNinetyFive() {
        SimConfig config = baseConfig(0.95, "snowy", 1.5);
        SimulationSummary summary = runService.run(config).getSummary();
        // canonical(snowy)=1.45, user=1.5, effective=2.175,0.95×2.175=2.066 → clamp 到 0.95
        assertEquals(0.95, summary.getTheoreticalTakeawayRate(), 1e-3,
                "intent 期望应被最终 clamp(0, 0.95) 卡住");
    }

    @Test
    void rainEmergencyScenarioShouldExposeBreakdownBetweenObservedAndTheoretical() {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        SimulationSummary summary = report.getSummary();

        assertEquals(0.325, summary.getTheoreticalTakeawayRate(), 1e-3,
                "rain_emergency 场景理论值应 = 0.325");

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

        // 雨天理论 32.5%,实际范围 [20%, 60%],偏离应小于 100%
        double deviation = Math.abs(breakdown.getObservedRate() - breakdown.getTheoreticalRate())
                / Math.max(1e-6, breakdown.getTheoreticalRate());
        assertTrue(deviation < 1.0,
                "rain_emergency 偏离度过大, deviation=" + deviation);
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
