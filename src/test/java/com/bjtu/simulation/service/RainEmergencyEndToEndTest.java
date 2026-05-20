package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * 第九轮 A1/A3:雨天应急预案端到端契约测试。
 * 直跑 SimulationRunService.run(rain_emergency 配置) 经 ObjectMapper 序列化(模拟
 * ReportResponseBuilder 默认路径剥 table_snapshots 但保留 frame_seat_layout),
 * 断言 timeline 完整、所有数值字段是有限数、frame_seat_layout 在响应中保留。
 *
 * 设计意图:把"雨天无法直接运行 + 无法读取 timeline"的回归一次性锁死在测试里。
 */
class RainEmergencyEndToEndTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();

    @Test
    void rainEmergencyShouldRunAndProduceCompleteTimeline() {
        Optional<ScenarioPreset> preset = catalog.find("rain_emergency");
        assertTrue(preset.isPresent(), "rain_emergency 场景必须存在");

        SimulationReport report = runService.run(preset.get().config());
        assertNotNull(report, "rain_emergency 必须能直接跑出报告");
        SimulationSummary summary = report.getSummary();
        assertNotNull(summary, "summary 不能为空");

        assertNotNull(summary.getTimeline(), "timeline 不能为 null");
        assertFalse(summary.getTimeline().isEmpty(), "timeline 至少要有 1 帧");
        assertTrue(summary.getTimeline().size() > 5,
                "雨天场景 timeline 应有多帧, got " + summary.getTimeline().size());
    }

    @Test
    void rainEmergencyTimelineMetricsMustBeFinite() {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        for (SimulationTimePoint point : report.getSummary().getTimeline()) {
            assertFiniteRate(point.getSeatUtilizationRate(), "seatUtilizationRate", point.getMinute());
            assertFiniteRate(point.getSeatUnavailableRate(), "seatUnavailableRate", point.getMinute());
            assertFiniteRate(point.getSeatReservedShare(), "seatReservedShare", point.getMinute());
            assertFiniteRate(point.getSeatFreeRate(), "seatFreeRate", point.getMinute());
        }
    }

    @Test
    void rainEmergencyTakeawayRateShouldBeWithinExpectedBand() {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        double observed = report.getSummary().getTakeawayRate();
        // ScenarioPresetCatalog 预期 32%-50%,留出方差余量 [0.20, 0.60]
        assertTrue(observed >= 0.20 && observed <= 0.60,
                "rain_emergency takeawayRate 偏离预期带 [0.20, 0.60], got " + observed);
    }

    @Test
    void rainEmergencyResponseShouldRetainFrameSeatLayoutAfterStrip() throws Exception {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
        ObjectNode reportJson = (ObjectNode) mapper.valueToTree(report);
        ObjectNode summary = (ObjectNode) reportJson.path("summary");
        JsonNode timeline = summary.path("timeline");
        assertTrue(timeline.isArray() && timeline.size() > 0, "timeline 必须是非空数组");

        // 模拟 ReportResponseBuilder 默认路径(includeHistory=false 时只剥 table_snapshots)
        for (JsonNode item : timeline) {
            if (item instanceof ObjectNode itemObject) {
                itemObject.remove("table_snapshots");
            }
        }

        int framesWithLayout = 0;
        for (JsonNode item : timeline) {
            assertJsonFiniteRate(item, "seat_utilization_rate");
            assertJsonFiniteRate(item, "seat_unavailable_rate");
            assertJsonFiniteRate(item, "seat_free_rate");
            JsonNode layout = item.path("frame_seat_layout");
            if (layout.isArray() && layout.size() > 0) {
                framesWithLayout++;
            }
        }
        assertTrue(framesWithLayout > 5,
                "剥 table_snapshots 后,雨天 timeline 仍应保留 >5 帧 frame_seat_layout, got " + framesWithLayout);
    }

    @Test
    void rainEmergencySummaryRateFieldsMustBeFinite() throws Exception {
        SimulationReport report = runService.run(catalog.find("rain_emergency").orElseThrow().config());
        ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
        ObjectNode reportJson = (ObjectNode) mapper.valueToTree(report);
        JsonNode summary = reportJson.path("summary");

        for (String key : new String[] {
                "takeaway_rate", "dine_in_rate",
                "seat_utilization_rate", "seat_time_weighted_utilization",
                "peak_seat_utilization_rate", "steady_state_seat_utilization",
                "seat_turnover_rate"}) {
            JsonNode node = summary.path(key);
            assertTrue(node.isNumber(), key + " 必须是数字, got " + node);
            assertTrue(Double.isFinite(node.asDouble()), key + " 必须是有限数, got " + node.asDouble());
        }
    }

    private static void assertFiniteRate(double value, String name, long minute) {
        if (!Double.isFinite(value)) {
            fail(name + " at minute " + minute + " is not finite: " + value);
        }
    }

    private static void assertJsonFiniteRate(JsonNode item, String key) {
        JsonNode node = item.path(key);
        assertTrue(node.isNumber(), key + " 必须是数字, got " + node + " at minute "
                + item.path("minute").asLong());
        assertTrue(Double.isFinite(node.asDouble()),
                key + " 必须是有限数 at minute " + item.path("minute").asLong() + ", got " + node.asDouble());
    }
}
