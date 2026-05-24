package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bjtu.simulation.dto.PerSeedMetric;
import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;

import org.junit.jupiter.api.Test;

/**
 * RFC-010A:T-10A-EX-1 / EX-2 / EX-3。
 *
 * <p>不使用 mocking 框架(项目现有测试均不引 mockito),通过 {@link SimulationRunService} 真实
 * 跑一个最小 STATIC_SPLIT 与最小 PREFERENCE_AWARE 报告作为 "mock 报告" 喂给提取器。</p>
 */
class PerSeedMetricExtractorTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final PerSeedMetricExtractor extractor = new PerSeedMetricExtractor();

    private SimConfig staticSplitConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc010a-ex-static");
        config.setDuration(0.25);
        config.setArrivalRate(60);
        config.setQueueLimit(10);
        config.setPackProbability(0.2);
        config.setSeed(seed);
        config.getBaseConfig().setWindowCount(4);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTotalSeats(40);
        config.getBaseConfig().setTotalStudents(40);
        return config;
    }

    private SimConfig preferenceAwareConfig(long seed) {
        SimConfig config = staticSplitConfig(seed);
        config.setSimulationName("rfc010a-ex-pref");
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return config;
    }

    @Test
    void exT1_staticSplitOmitsThreePr9dFields() {
        SimulationReport report = runService.run(staticSplitConfig(20260521L), "ex-1");

        PerSeedMetric metric = extractor.extract(20260521L, report);
        assertNotNull(metric);
        assertEquals(20260521L, metric.getSeed());
        assertEquals("ex-1", metric.getReportId());

        assertEquals(report.getSummary().getArrivedCount(), metric.getArrivedCount());
        assertEquals(report.getSummary().getServedCount(), metric.getServedCount());
        assertEquals(report.getSummary().getTypicalWaitTimeMinutes(),
                metric.getTypicalWaitTimeMinutes(), 0.0);
        assertEquals(report.getSummary().getMedianWaitTimeMinutes(),
                metric.getMedianWaitTimeMinutes(), 0.0);
        assertEquals(report.getSummary().getP90WaitTimeMinutes(),
                metric.getP90WaitTimeMinutes(), 0.0);
        assertEquals(report.getSummary().getSeatUtilizationRate(),
                metric.getSeatUtilizationRate(), 0.0);
        assertEquals(report.getSummary().getTakeawayRate(),
                metric.getTakeawayRate(), 0.0);
        assertEquals(report.getSummary().getMaxTotalQueueSize(),
                metric.getMaxTotalQueueSize());

        assertNull(report.getSummary().getWindowChoiceMetrics(),
                "STATIC_SPLIT 报告里 windowChoiceMetrics 必须为 null");
        assertNull(metric.getPopularServedShare(), "STATIC_SPLIT 下 popularServedShare 必须 null");
        assertNull(metric.getColdServedShare(), "STATIC_SPLIT 下 coldServedShare 必须 null");
        assertNull(metric.getWindowServedCountCv(), "STATIC_SPLIT 下 windowServedCountCv 必须 null");
    }

    @Test
    void exT2_preferenceAwareFillsAllElevenFields() {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L), "ex-2");

        PerSeedMetric metric = extractor.extract(20260521L, report);
        assertNotNull(metric);
        assertNotNull(report.getSummary().getWindowChoiceMetrics(),
                "PREFERENCE_AWARE 报告必须填充 windowChoiceMetrics");
        assertNotNull(metric.getPopularServedShare(), "popularServedShare 必须非 null");
        assertNotNull(metric.getColdServedShare(), "coldServedShare 必须非 null");
        assertNotNull(metric.getWindowServedCountCv(), "windowServedCountCv 必须非 null");

        assertEquals(report.getSummary().getWindowChoiceMetrics().getPopularServedShare(),
                metric.getPopularServedShare(), 0.0);
        assertEquals(report.getSummary().getWindowChoiceMetrics().getColdServedShare(),
                metric.getColdServedShare(), 0.0);
        assertEquals(report.getSummary().getWindowChoiceMetrics().getWindowServedCountCv(),
                metric.getWindowServedCountCv(), 0.0);
    }

    @Test
    void exT3_nullReportThrowsNpe() {
        assertThrows(NullPointerException.class, () -> extractor.extract(1L, null));
    }
}
