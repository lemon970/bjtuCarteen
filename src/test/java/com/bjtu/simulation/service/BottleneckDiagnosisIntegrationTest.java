package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.BottleneckDiagnosis;
import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-012:T-12-INT-1 ~ T-12-INT-3。
 *
 * <p>验收 RFC-012 与 SimulationRunService 的端到端接通:</p>
 * <ul>
 *   <li>T-12-INT-1: STATIC_SPLIT 路径下 summary.bottleneck_diagnosis 存在且 primary
 *       为 5 个合法 enum 值之一(snake_case);bottlenecks[].evidence 含 4 子字段</li>
 *   <li>T-12-INT-2: 同 baseConfig + 同 seed + 同 reportId 两次 run → bottleneckDiagnosis
 *       JSON 字节级一致</li>
 *   <li>T-12-INT-3: PREFERENCE_AWARE 与 RFC-011 共存路径下 bottleneckDiagnosis 仍非 null</li>
 * </ul>
 */
class BottleneckDiagnosisIntegrationTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    private static final Set<String> LEGAL_PRIMARY_VALUES = Set.of(
            "window_service_capacity", "seat_capacity", "takeaway_capacity",
            "arrival_surge", "balanced");

    private SimConfig staticSplitConfig() {
        SimConfig c = new SimConfig();
        c.setSimulationName("rfc012-static");
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
        c.setSimulationName("rfc012-pref");
        c.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        c.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return c;
    }

    // ---- T-12-INT-1 ----

    @Test
    void t12Int1_staticSplitAttachesDiagnosis() throws Exception {
        SimulationReport report = runService.run(staticSplitConfig(), "rfc012-int1");
        BottleneckDiagnosis diagnosis = report.getSummary().getBottleneckDiagnosis();
        assertNotNull(diagnosis, "RFC-012 buildSummary 后必须 setBottleneckDiagnosis,不能为 null");
        assertNotNull(diagnosis.getPrimary());

        JsonNode root = mapper.readTree(mapper.writeValueAsBytes(report));
        JsonNode summaryNode = root.path("summary");
        assertFalse(summaryNode.isMissingNode(), "summary 节点必须存在");

        JsonNode diag = summaryNode.path("bottleneck_diagnosis");
        assertFalse(diag.isMissingNode(), "summary.bottleneck_diagnosis 必须存在");

        // primary 必须取 5 个合法 enum 值之一(lower_snake_case)
        String primary = diag.path("primary").asText();
        assertTrue(LEGAL_PRIMARY_VALUES.contains(primary),
                () -> "primary 必须取 5 个合法 lower_snake_case 值之一,实际:" + primary);

        // bottlenecks 在 BALANCED 路径下为空数组,经 mapper NON_EMPTY 策略会被省略;
        // 非空时必须是数组,且每条 bottleneck 的 evidence 含 4 子字段
        JsonNode bottlenecks = diag.path("bottlenecks");
        if (!bottlenecks.isMissingNode()) {
            assertTrue(bottlenecks.isArray(), "bottlenecks 存在时必须是数组");
            for (JsonNode b : bottlenecks) {
                JsonNode ev = b.path("evidence");
                assertFalse(ev.isMissingNode(), "每条 bottleneck 必有 evidence 子节点");
                assertTrue(ev.has("metric_name"), "evidence 必有 metric_name");
                assertTrue(ev.has("observed_value"), "evidence 必有 observed_value");
                assertTrue(ev.has("threshold"), "evidence 必有 threshold");
                assertTrue(ev.has("window_id"), "evidence 必有 window_id");
            }
        }
        // BALANCED 路径下 primary 必为 "balanced";否则 bottlenecks 必非空(至少 1 条触发)
        if ("balanced".equals(primary)) {
            assertTrue(bottlenecks.isMissingNode() || bottlenecks.size() == 0,
                    "BALANCED 时 bottlenecks 必为空(经 NON_EMPTY 省略或显式 0 长)");
        } else {
            assertFalse(bottlenecks.isMissingNode(), "非 BALANCED 时 bottlenecks 必存在");
            assertTrue(bottlenecks.size() >= 1, "非 BALANCED 时 bottlenecks 至少 1 条");
        }
    }

    // ---- T-12-INT-2 ----

    @Test
    void t12Int2_deterministicSameSeed() throws Exception {
        SimulationReport r1 = runService.run(staticSplitConfig(), "rfc012-int2");
        SimulationReport r2 = runService.run(staticSplitConfig(), "rfc012-int2");

        byte[] d1 = mapper.writeValueAsBytes(r1.getSummary().getBottleneckDiagnosis());
        byte[] d2 = mapper.writeValueAsBytes(r2.getSummary().getBottleneckDiagnosis());
        assertArrayEquals(d1, d2,
                "同 baseConfig + 同 seed + 同 reportId,bottleneck_diagnosis 必须字节级一致");
    }

    // ---- T-12-INT-3 ----

    @Test
    void t12Int3_preferenceAwareCoexistsWithRfc011() {
        SimulationReport report = runService.run(preferenceAwareConfig(), "rfc012-int3");
        // PREFERENCE_AWARE 下 PR-9D / RFC-012 同时非 null
        assertNotNull(report.getSummary().getWindowChoiceMetrics(),
                "PREFERENCE_AWARE 下 PR-9D windowChoiceMetrics 应非 null");
        assertNotNull(report.getSummary().getBottleneckDiagnosis(),
                "RFC-012 bottleneckDiagnosis 必须共存");

        BottleneckDiagnosis diagnosis = report.getSummary().getBottleneckDiagnosis();
        // primary 必须为 5 类之一
        String primaryName = diagnosis.getPrimary().name();
        assertTrue(Arrays.asList(
                "WINDOW_SERVICE_CAPACITY", "SEAT_CAPACITY", "TAKEAWAY_CAPACITY",
                "ARRIVAL_SURGE", "BALANCED").contains(primaryName),
                () -> "primary 必须取合法 enum 值,实际:" + primaryName);

        // bottlenecks 中不允许出现 BALANCED
        for (var b : diagnosis.getBottlenecks()) {
            assertFalse(b.getType().name().equals("BALANCED"),
                    "BALANCED 不应出现在 bottlenecks[] 列表里");
        }
    }
}
