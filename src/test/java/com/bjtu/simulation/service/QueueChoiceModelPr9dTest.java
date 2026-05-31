package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.bjtu.simulation.dto.WindowChoiceMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9D 验收测试。
 *
 * <p>本测试只覆盖 {@code window_choice_metrics} 输出契约,
 * 不验证统计精度(交给 PR-9C 集成 + sampler 单元测试)。</p>
 *
 * <ul>
 *   <li>T-9D-1: STATIC_SPLIT 报告对象 {@code summary.windowChoiceMetrics == null}。</li>
 *   <li>T-9D-2: STATIC_SPLIT 报告 JSON 中不含 summary.window_choice_metrics 字段。</li>
 *   <li>T-9D-3: PREFERENCE_AWARE 报告含 {@code window_choice_metrics};所有字段非空。</li>
 *   <li>T-9D-4: window_count 拆分(popular/normal/cold/takeaway)与 base_config 一致。</li>
 *   <li>T-9D-5: preference + served share 三类之和 ≈ 1.0(普通窗口集合内归一)。</li>
 *   <li>T-9D-6: avg wait minutes 三类均 >= 0;cv >= 0;max_window_queue_gap >= 0。</li>
 *   <li>T-9D-7: 同 seed → 所有 metrics 字段字节级一致。</li>
 *   <li>T-9D-8: JSON 字段全部按 SNAKE_CASE 序列化。</li>
 * </ul>
 */
class QueueChoiceModelPr9dTest {

    private final SimulationRunService runService = new SimulationRunService();

    // ---- T-9D-1 / T-9D-2 STATIC_SPLIT 缺省 ----

    @Test
    void t9d1_staticSplitSummaryHasNullWindowChoiceMetrics() {
        SimulationReport report = runService.run(baselineConfig(20260521L), "rfc009-pr9d-t1");
        assertNull(report.getSummary().getWindowChoiceMetrics(),
                "STATIC_SPLIT summary.windowChoiceMetrics 必须为 null");
    }

    @Test
    void t9d2_staticSplitJsonOmitsWindowChoiceMetrics() throws Exception {
        SimulationReport report = runService.run(baselineConfig(20260521L), "rfc009-pr9d-t2");
        JsonNode root = snakeCaseMapper().valueToTree(report);
        assertFalse(root.has("window_choice_metrics"),
                "STATIC_SPLIT 顶级 JSON 不得有 window_choice_metrics");
        assertFalse(root.path("summary").has("window_choice_metrics"),
                "STATIC_SPLIT summary 子节点不得有 window_choice_metrics");
    }

    // ---- T-9D-3 PREFERENCE_AWARE 结构完整性 ----

    @Test
    void t9d3_preferenceAwareReportContainsAllMetricFields() throws Exception {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L),
                "rfc009-pr9d-t3");

        SimulationSummary summary = report.getSummary();
        WindowChoiceMetrics metrics = summary.getWindowChoiceMetrics();
        assertNotNull(metrics, "PREFERENCE_AWARE 必须输出 window_choice_metrics");
        assertEquals("PREFERENCE_AWARE", metrics.getQueueChoiceModel());

        // JSON 路径上 summary.window_choice_metrics 必须存在,字段全部 snake_case
        JsonNode root = snakeCaseMapper().valueToTree(report);
        JsonNode wcm = root.path("summary").path("window_choice_metrics");
        assertFalse(wcm.isMissingNode(), "summary.window_choice_metrics 必须存在");
        for (String field : new String[] {
                "queue_choice_model",
                "popular_window_count", "normal_window_count",
                "cold_window_count", "takeaway_window_count",
                "popular_preference_share", "normal_preference_share", "cold_preference_share",
                "popular_served_share", "normal_served_share", "cold_served_share",
                "popular_avg_wait_minutes", "normal_avg_wait_minutes", "cold_avg_wait_minutes",
                "max_window_queue_gap", "window_served_count_cv"
        }) {
            assertTrue(wcm.has(field),
                    () -> "window_choice_metrics 缺少 snake_case 字段:" + field);
        }
    }

    // ---- T-9D-4 窗口拆分与配置一致 ----

    @Test
    void t9d4_windowCountsAreConsistentWithBaseConfig() {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L),
                "rfc009-pr9d-t4");
        WindowChoiceMetrics m = report.getSummary().getWindowChoiceMetrics();

        int totalNormal = m.getPopularWindowCount()
                + m.getNormalWindowCount()
                + m.getColdWindowCount();
        int expectedNormal = report.getConfig().getBaseConfig().getWindowCount()
                - report.getConfig().getBaseConfig().getTakeawayWindowCount();
        assertEquals(expectedNormal, totalNormal,
                "popular + normal + cold 之和必须等于 base_config 普通窗口数");
        assertEquals(report.getConfig().getBaseConfig().getTakeawayWindowCount(),
                m.getTakeawayWindowCount(),
                "takeaway_window_count 必须与 base_config 一致");
    }

    // ---- T-9D-5 share 守恒 ----

    @Test
    void t9d5_shareSumsAreOne() {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L),
                "rfc009-pr9d-t5");
        WindowChoiceMetrics m = report.getSummary().getWindowChoiceMetrics();

        double prefSum = m.getPopularPreferenceShare()
                + m.getNormalPreferenceShare()
                + m.getColdPreferenceShare();
        double servedSum = m.getPopularServedShare()
                + m.getNormalServedShare()
                + m.getColdServedShare();

        // round3 引入最大三舍入误差 1.5e-3,放宽到 5e-3
        assertEquals(1.0, prefSum, 5e-3,
                () -> "preference share 之和应为 1.0,实际 = " + prefSum);
        assertEquals(1.0, servedSum, 5e-3,
                () -> "served share 之和应为 1.0,实际 = " + servedSum);
    }

    // ---- T-9D-6 数值合法性 ----

    @Test
    void t9d6_metricNumericFieldsAreNonNegative() {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L),
                "rfc009-pr9d-t6");
        WindowChoiceMetrics m = report.getSummary().getWindowChoiceMetrics();

        assertTrue(m.getPopularAvgWaitMinutes() >= 0.0, "popular_avg_wait_minutes >= 0");
        assertTrue(m.getNormalAvgWaitMinutes() >= 0.0, "normal_avg_wait_minutes >= 0");
        assertTrue(m.getColdAvgWaitMinutes() >= 0.0, "cold_avg_wait_minutes >= 0");
        assertTrue(m.getMaxWindowQueueGap() >= 0, "max_window_queue_gap >= 0");
        assertTrue(m.getWindowServedCountCv() >= 0.0, "window_served_count_cv >= 0");
    }

    // ---- T-9D-7 决定论 ----

    @Test
    void t9d7_sameSeedProducesByteEqualMetrics() {
        SimulationReport r1 = runService.run(preferenceAwareConfig(20260521L), "rfc009-pr9d-t7-a");
        SimulationReport r2 = runService.run(preferenceAwareConfig(20260521L), "rfc009-pr9d-t7-b");

        WindowChoiceMetrics m1 = r1.getSummary().getWindowChoiceMetrics();
        WindowChoiceMetrics m2 = r2.getSummary().getWindowChoiceMetrics();

        assertEquals(m1.getPopularWindowCount(), m2.getPopularWindowCount());
        assertEquals(m1.getNormalWindowCount(), m2.getNormalWindowCount());
        assertEquals(m1.getColdWindowCount(), m2.getColdWindowCount());
        assertEquals(m1.getTakeawayWindowCount(), m2.getTakeawayWindowCount());
        assertEquals(m1.getPopularPreferenceShare(), m2.getPopularPreferenceShare(), 0.0);
        assertEquals(m1.getNormalPreferenceShare(), m2.getNormalPreferenceShare(), 0.0);
        assertEquals(m1.getColdPreferenceShare(), m2.getColdPreferenceShare(), 0.0);
        assertEquals(m1.getPopularServedShare(), m2.getPopularServedShare(), 0.0);
        assertEquals(m1.getNormalServedShare(), m2.getNormalServedShare(), 0.0);
        assertEquals(m1.getColdServedShare(), m2.getColdServedShare(), 0.0);
        assertEquals(m1.getPopularAvgWaitMinutes(), m2.getPopularAvgWaitMinutes(), 0.0);
        assertEquals(m1.getNormalAvgWaitMinutes(), m2.getNormalAvgWaitMinutes(), 0.0);
        assertEquals(m1.getColdAvgWaitMinutes(), m2.getColdAvgWaitMinutes(), 0.0);
        assertEquals(m1.getMaxWindowQueueGap(), m2.getMaxWindowQueueGap());
        assertEquals(m1.getWindowServedCountCv(), m2.getWindowServedCountCv(), 0.0);
    }

    // ---- T-9D-8 方向健全性(冗余于 PR-9C T2b/T3,但确保 builder 直接产出仍方向正确) ----

    @Test
    void t9d8_popularSharesLeanAboveUniform() {
        SimulationReport report = runService.run(preferenceAwareConfig(20260521L),
                "rfc009-pr9d-t8");
        WindowChoiceMetrics m = report.getSummary().getWindowChoiceMetrics();

        int normalTotal = m.getPopularWindowCount()
                + m.getNormalWindowCount()
                + m.getColdWindowCount();
        double uniformPop = m.getPopularWindowCount() / (double) normalTotal;

        assertTrue(m.getPopularPreferenceShare() > uniformPop,
                () -> "popular_preference_share = " + m.getPopularPreferenceShare()
                        + " 应大于 uniform = " + uniformPop);
        assertTrue(m.getPopularServedShare() <= 0.60,
                () -> "popular_served_share = " + m.getPopularServedShare()
                        + " 不得超出 §11 pilot guardrail 0.6");
    }

    // ---- helpers ----

    private SimConfig baselineConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc009-pr9d-baseline");
        config.setDuration(0.5);
        config.setArrivalRate(120);
        config.setQueueLimit(15);
        config.setPackProbability(0.2);
        config.setSeed(seed);
        config.getBaseConfig().setWindowCount(10);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getBaseConfig().setTotalSeats(80);
        config.getBaseConfig().setTotalStudents(80);
        return config;
    }

    private SimConfig preferenceAwareConfig(long seed) {
        SimConfig config = baselineConfig(seed);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return config;
    }

    /** 与 {@code AppBeansConfig} 的 mapper 配置保持一致(SNAKE_CASE),否则路径取不到字段。 */
    private ObjectMapper snakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }
}
