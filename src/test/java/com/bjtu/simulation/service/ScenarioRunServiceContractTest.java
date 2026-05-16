package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.ScenarioBatchRunRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 第七轮 B2 + B3 验证:
 * - 批量场景默认响应剥掉 timeline / history / table_snapshots 等大字段(轻量)
 * - include_history=true 显式请求时仍然返回完整结构(此处通过 service 直接验证)
 * - groupConfig override 真实进入 SimulationArrivalScheduler,产出非零 grouped_student_count
 */
@SpringBootTest
@TestPropertySource(properties = "spring.main.banner-mode=off")
class ScenarioRunServiceContractTest {

    @Autowired
    private ScenarioRunService scenarioRunService;

    @Test
    void defaultBatchResponseShouldOmitHeavyFields() {
        ScenarioBatchRunRequest request = new ScenarioBatchRunRequest();
        request.setScenarioIds(java.util.List.of("baseline_offpeak"));

        ObjectNode result = scenarioRunService.runScenarios(request, false);
        JsonNode summary = result.path("results").path(0).path("summary");

        assertNotNull(summary);
        assertTrue(summary.isObject(), "summary should be object");
        // 轻量响应不包含这些重字段
        assertTrue(summary.path("timeline").isMissingNode(), "timeline should be stripped");
        assertTrue(summary.path("history").isMissingNode(), "history should be stripped");
        assertTrue(summary.path("table_snapshots").isMissingNode(), "table_snapshots should be stripped");
        assertTrue(summary.path("seat_cells").isMissingNode(), "seat_cells should be stripped");
        // 但保留聚合数值指标
        assertTrue(summary.path("arrived_count").isNumber(), "arrived_count should remain");
        assertTrue(summary.path("takeaway_rate").isNumber(), "takeaway_rate should remain");
        assertTrue(summary.path("typical_wait_time_minutes").isNumber(), "typical_wait_time_minutes should remain");
    }

    @Test
    void includeHistoryTrueShouldReturnFullSummary() {
        ScenarioBatchRunRequest request = new ScenarioBatchRunRequest();
        request.setScenarioIds(java.util.List.of("baseline_offpeak"));

        ObjectNode result = scenarioRunService.runScenarios(request, true);
        JsonNode summary = result.path("results").path(0).path("summary");

        assertTrue(summary.path("timeline").isArray(), "timeline must be present when include_history=true");
    }

    @Test
    void groupConfigOverrideShouldFlowIntoArrivalScheduler() throws Exception {
        // baseline_offpeak 默认 group_count=0(非 group 场景),通过 override 显式开启 group 行为
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode overrides = mapper.readTree(
                "{\"group_config\":{\"enabled\":true,\"group_count\":20,\"size_min\":3,\"size_max\":5,\"behavior_correlation\":0.85}," +
                        "\"group_arrival_prob\":0.6,\"party_size\":4}");
        ScenarioBatchRunRequest request = new ScenarioBatchRunRequest();
        request.setScenarioIds(java.util.List.of("baseline_offpeak"));
        request.setOverrides(overrides);

        ObjectNode result = scenarioRunService.runScenarios(request, false);
        JsonNode summary = result.path("results").path(0).path("summary");

        // group 启用后应有 grouped_student_count > 0(若 copyInto 漏复制 groupConfig 这里会是 0)
        int grouped = summary.path("grouped_student_count").asInt(0);
        assertTrue(grouped > 0,
                "grouped_student_count=" + grouped + " — groupConfig override 没有进入 scheduler");
    }
}
