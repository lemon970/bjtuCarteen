package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9B 阶段验收测试。
 *
 * <ul>
 *   <li>T1: STATIC_SPLIT 默认核心指标不变(default 与 explicit STATIC_SPLIT 同 seed 字节级一致)。</li>
 *   <li>T5: §8.1 配置校验规则。</li>
 *   <li>T7: 默认报告无 summary.window_choice_metrics 字段。</li>
 *   <li>T-9B-1: PREFERENCE_AWARE / WORKLOAD_ROUTING / HYBRID_OVERFLOW 在 PR-9B fail-fast。</li>
 * </ul>
 */
class QueueChoiceModelPr9bTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final SimulationConfigNormalizer normalizer = new SimulationConfigNormalizer();

    // ---- T1: STATIC_SPLIT 默认核心指标不变 ----

    @Test
    void t1_defaultAndExplicitStaticSplitProduceIdenticalCoreMetrics() {
        SimConfig defaultConfig = baselineConfig(20260520L);
        SimConfig explicitStatic = baselineConfig(20260520L);
        explicitStatic.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.STATIC_SPLIT);

        SimulationReport defaultReport = runService.run(defaultConfig, "rfc009-pr9b-t1-default");
        SimulationReport explicitReport = runService.run(explicitStatic, "rfc009-pr9b-t1-explicit");

        // 10 个核心指标(RFC-009 Rev 3 §11 T1)字节级一致
        assertEquals(defaultReport.getSummary().getArrivedCount(),
                explicitReport.getSummary().getArrivedCount(), "arrived_count");
        assertEquals(defaultReport.getSummary().getServedCount(),
                explicitReport.getSummary().getServedCount(), "served_count");
        assertEquals(defaultReport.getSummary().getTakeawayCount(),
                explicitReport.getSummary().getTakeawayCount(), "takeaway_count");
        assertEquals(defaultReport.getSummary().getDineInCount(),
                explicitReport.getSummary().getDineInCount(), "dine_in_count");
        assertEquals(defaultReport.getSummary().getMaxTotalQueueSize(),
                explicitReport.getSummary().getMaxTotalQueueSize(), "max_total_queue_size");
        assertEquals(defaultReport.getSummary().getSeatUtilizationRate(),
                explicitReport.getSummary().getSeatUtilizationRate(), 0.0, "seat_utilization_rate");
        assertEquals(defaultReport.getSummary().getTakeawayRate(),
                explicitReport.getSummary().getTakeawayRate(), 0.0, "takeaway_rate");

        // wait time 三项通过 SimulationSummary 直接 getter(委托到 WaitTimeMetrics)
        assertEquals(defaultReport.getSummary().getTypicalWaitTimeMinutes(),
                explicitReport.getSummary().getTypicalWaitTimeMinutes(),
                0.0, "typical_wait_time_minutes");
        assertEquals(defaultReport.getSummary().getMedianWaitTimeMinutes(),
                explicitReport.getSummary().getMedianWaitTimeMinutes(),
                0.0, "median_wait_time_minutes");
        assertEquals(defaultReport.getSummary().getP90WaitTimeMinutes(),
                explicitReport.getSummary().getP90WaitTimeMinutes(),
                0.0, "p90_wait_time_minutes");
    }

    // ---- T5: 配置校验 ----

    @Test
    void t5_popularPlusColdRatioAboveOneShouldThrow() {
        SimConfig config = preferenceAwareConfig();
        WindowAttractivenessConfig attr = config.getBaseConfig().getWindowAttractiveness();
        attr.setPopularWindowRatio(0.7);
        attr.setColdWindowRatio(0.5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("must be <= 1.0"), ex.getMessage());
    }

    @Test
    void t5_zeroAttractivenessShouldThrow() {
        SimConfig config = preferenceAwareConfig();
        config.getBaseConfig().getWindowAttractiveness().setPopularAttractiveness(0.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        // 0.0 触发 jakarta @DecimalMin(inclusive=false) 校验,但 normalizer 自身的代码
        // 也会对 attractiveness <= 0 抛错;两条路径都满足"必须抛 IllegalArgumentException"。
        assertNotNull(ex.getMessage());
    }

    @Test
    void t5_negativeAttractivenessShouldThrow() {
        SimConfig config = preferenceAwareConfig();
        // 跳过 setter 上限校验:popular 设 1.0、normal 设 1.0、cold 设 -0.5
        config.getBaseConfig().getWindowAttractiveness().setColdAttractiveness(-0.5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("> 0") || ex.getMessage().contains("must be"),
                ex.getMessage());
    }

    @Test
    void t5_popularLessThanNormalShouldThrow() {
        SimConfig config = preferenceAwareConfig();
        WindowAttractivenessConfig attr = config.getBaseConfig().getWindowAttractiveness();
        attr.setPopularAttractiveness(0.9);
        attr.setNormalAttractiveness(1.0);
        attr.setColdAttractiveness(0.8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("popularAttractiveness")
                && ex.getMessage().contains(">= normalAttractiveness"), ex.getMessage());
    }

    @Test
    void t5_normalLessThanColdShouldThrow() {
        SimConfig config = preferenceAwareConfig();
        WindowAttractivenessConfig attr = config.getBaseConfig().getWindowAttractiveness();
        attr.setPopularAttractiveness(1.4);
        attr.setNormalAttractiveness(0.7);
        attr.setColdAttractiveness(0.8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("normalAttractiveness")
                && ex.getMessage().contains(">= coldAttractiveness"), ex.getMessage());
    }

    @Test
    void t5_popularPlusColdEqualsOneIsLegalAndEmitsNoNormalWindowsWarning() {
        SimConfig config = preferenceAwareConfig();
        WindowAttractivenessConfig attr = config.getBaseConfig().getWindowAttractiveness();
        attr.setPopularWindowRatio(0.5);
        attr.setColdWindowRatio(0.5);

        normalizer.drainLastWarnings(); // 清掉前序状态
        // PREFERENCE_AWARE 在 normalize 后由 Engine fail-fast 拒绝;Validator 阶段必须放行。
        // 因此直接调 normalize,不调 run。
        normalizer.normalize(config);
        List<String> warnings = normalizer.drainLastWarnings();
        assertTrue(warnings.contains("no_normal_windows"),
                () -> "expected no_normal_windows warning, got: " + warnings);
    }

    @Test
    void t5_preferenceAwareWithoutAttractivenessShouldFillDefaultsAndWarn() {
        SimConfig config = baselineConfig(20260520L);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(null);

        normalizer.drainLastWarnings();
        normalizer.normalize(config);

        assertNotNull(config.getBaseConfig().getWindowAttractiveness(),
                "windowAttractiveness should be auto-filled when missing under PREFERENCE_AWARE");
        List<String> warnings = normalizer.drainLastWarnings();
        assertTrue(warnings.contains("window_attractiveness_missing_filled_default"),
                () -> "expected fill-default warning, got: " + warnings);
    }

    // ---- T7: 默认报告无 window_choice_metrics ----

    @Test
    void t7_defaultReportShouldNotContainWindowChoiceMetrics() throws Exception {
        SimulationReport report = runService.run(baselineConfig(20260520L),
                "rfc009-pr9b-t7-default");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.valueToTree(report);
        assertFalse(root.has("window_choice_metrics"),
                "STATIC_SPLIT default report must not contain window_choice_metrics top-level field");
        // summary 内也不应出现
        if (root.has("summary")) {
            assertFalse(root.get("summary").has("window_choice_metrics"),
                    "summary.window_choice_metrics must not be present in PR-9B");
        }
    }

    // ---- T-9B-1: fail-fast for枚举值 (PR-9C 之后) ----
    // PR-9C 起,PREFERENCE_AWARE 已正式启用,只有 WORKLOAD_ROUTING / HYBRID_OVERFLOW 仍 fail-fast。

    @Test
    void t9b1_workloadRoutingShouldFailFast() {
        SimConfig config = baselineConfig(20260520L);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.WORKLOAD_ROUTING);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> runService.run(config, "rfc009-pr9b-t9b1-workload"));
        assertTrue(ex.getMessage().contains("V2/V3 not enabled"),
                () -> ex.getMessage());
    }

    @Test
    void t9b1_hybridOverflowShouldFailFast() {
        SimConfig config = baselineConfig(20260520L);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.HYBRID_OVERFLOW);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> runService.run(config, "rfc009-pr9b-t9b1-hybrid"));
        assertTrue(ex.getMessage().contains("V2/V3 not enabled"),
                () -> ex.getMessage());
    }

    // ---- helpers ----

    private SimConfig baselineConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc009-pr9b-baseline");
        config.setDuration(0.5);
        config.setArrivalRate(120);
        config.setQueueLimit(15);
        config.setPackProbability(0.2);
        config.setSeed(seed);
        config.getBaseConfig().setWindowCount(4);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTotalSeats(40);
        config.getBaseConfig().setTotalStudents(60);
        return config;
    }

    private SimConfig preferenceAwareConfig() {
        SimConfig config = baselineConfig(20260520L);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        WindowAttractivenessConfig attr = new WindowAttractivenessConfig();
        config.getBaseConfig().setWindowAttractiveness(attr);
        return config;
    }
}
