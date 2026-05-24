package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-011:T-11-INT-1 ~ T-11-INT-3。
 *
 * <p>验收 RFC-011 与 SimulationRunService / SimulationSummary 的端到端接通:</p>
 * <ul>
 *   <li>T-11-INT-1: STATIC_SPLIT 路径下 windowChoiceMetrics=null,但
 *       waitExperienceProxyMetrics 与 fairnessMetrics 都非 null;snake_case JSON 路径正确</li>
 *   <li>T-11-INT-2: PREFERENCE_AWARE 下 3 个 sub-DTO 共存</li>
 *   <li>T-11-INT-3: 同 seed/config/reportId 两次 run → 2 个 sub-DTO 字节级一致</li>
 * </ul>
 */
class SimulationSummaryRfc011IntegrationTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    private SimConfig staticSplitConfig() {
        SimConfig c = new SimConfig();
        c.setSimulationName("rfc011-static");
        c.setDuration(0.5);
        c.setArrivalRate(120);
        c.setQueueLimit(15);
        c.setPackProbability(0.2);
        c.setSeed(20260524L);
        c.getBaseConfig().setWindowCount(10);
        c.getBaseConfig().setTakeawayWindowCount(2);
        c.getBaseConfig().setTotalSeats(80);
        c.getBaseConfig().setTotalStudents(80);
        return c;
    }

    private SimConfig preferenceAwareConfig() {
        SimConfig c = staticSplitConfig();
        c.setSimulationName("rfc011-pref");
        c.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        c.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return c;
    }

    // ---- T-11-INT-1 ----

    @Test
    void t11Int1_staticSplitPopulatesBothMetrics() throws Exception {
        SimulationReport report = runService.run(staticSplitConfig(), "rfc011-int1");
        SimulationSummary summary = report.getSummary();

        assertNull(summary.getWindowChoiceMetrics(),
                "STATIC_SPLIT 下 windowChoiceMetrics 必须保持 null(PR-9D 行为)");
        assertNotNull(summary.getWaitExperienceProxyMetrics(),
                "RFC-011 §A wait_experience_proxy_metrics 在 STATIC_SPLIT N≥50 路径下必须非 null");
        assertNotNull(summary.getFairnessMetrics(),
                "RFC-011 §B fairness_metrics 在 STATIC_SPLIT N≥50 路径下必须非 null");

        // JSON snake_case 路径
        JsonNode root = mapper.readTree(mapper.writeValueAsBytes(report));
        JsonNode summaryNode = root.path("summary");
        assertFalse(summaryNode.isMissingNode(), "summary 节点必须存在");

        JsonNode wepm = summaryNode.path("wait_experience_proxy_metrics");
        assertFalse(wepm.isMissingNode(), "summary.wait_experience_proxy_metrics 必须存在");
        for (String f : new String[] {
                "pre_process_wait_share",
                "wait_uncertainty_score",
                "anxiety_pressure_index",
                "solo_adjusted_wait_minutes",
                "wait_experience_proxy_index",
                "sample_count"
        }) {
            assertTrue(wepm.has(f), () -> "wait_experience_proxy_metrics 缺 snake_case 字段:" + f);
        }

        JsonNode fm = summaryNode.path("fairness_metrics");
        assertFalse(fm.isMissingNode(), "summary.fairness_metrics 必须存在");
        for (String f : new String[] {
                "wait_gini",
                "non_takeaway_window_load_cv",
                "cross_role_fairness",
                "sample_count"
        }) {
            assertTrue(fm.has(f), () -> "fairness_metrics 缺 snake_case 字段:" + f);
        }

        // STATIC_SPLIT 下 window_choice_metrics 仍然不出现
        assertFalse(summaryNode.has("window_choice_metrics"),
                "STATIC_SPLIT summary 节点不得有 window_choice_metrics(PR-9D 契约)");
    }

    // ---- T-11-INT-2 ----

    @Test
    void t11Int2_preferenceAwareAllThreeMetricsCoexist() {
        SimulationReport report = runService.run(preferenceAwareConfig(), "rfc011-int2");
        SimulationSummary summary = report.getSummary();
        assertNotNull(summary.getWindowChoiceMetrics(),
                "PREFERENCE_AWARE 下 PR-9D windowChoiceMetrics 应非 null");
        assertNotNull(summary.getWaitExperienceProxyMetrics(),
                "PREFERENCE_AWARE 下 RFC-011 §A 必须共存");
        assertNotNull(summary.getFairnessMetrics(),
                "PREFERENCE_AWARE 下 RFC-011 §B 必须共存");
    }

    // ---- T-11-INT-3 ----

    @Test
    void t11Int3_deterministicSameSeed() throws Exception {
        SimulationReport r1 = runService.run(staticSplitConfig(), "rfc011-int3");
        SimulationReport r2 = runService.run(staticSplitConfig(), "rfc011-int3");

        byte[] wepm1 = mapper.writeValueAsBytes(r1.getSummary().getWaitExperienceProxyMetrics());
        byte[] wepm2 = mapper.writeValueAsBytes(r2.getSummary().getWaitExperienceProxyMetrics());
        assertArrayEquals(wepm1, wepm2,
                "同 baseConfig + 同 seed + 同 reportId,wait_experience_proxy_metrics 必须字节级一致");

        byte[] fm1 = mapper.writeValueAsBytes(r1.getSummary().getFairnessMetrics());
        byte[] fm2 = mapper.writeValueAsBytes(r2.getSummary().getFairnessMetrics());
        assertArrayEquals(fm1, fm2,
                "同 baseConfig + 同 seed + 同 reportId,fairness_metrics 必须字节级一致");
    }
}
