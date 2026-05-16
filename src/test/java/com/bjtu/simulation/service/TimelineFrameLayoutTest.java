package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.bjtu.simulation.config.AppBeansConfig;

import org.junit.jupiter.api.Test;

/**
 * 第八轮 A2:校验 timeline 帧带有 frameSeatLayout,且响应剥除 table_snapshots 后
 * 仍保留每帧成组信息。验证逻辑覆盖了"成组占用色块只显示一次"的真实根因。
 */
class TimelineFrameLayoutTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void timelineFramesShouldCarryDistinctFrameSeatLayout() {
        SimulationReport report = runService.run(highGroupPressureConfig());
        List<SimulationTimePoint> timeline = report.getSummary().getTimeline();
        assertNotNull(timeline, "timeline should be present");
        assertFalse(timeline.isEmpty(), "timeline should have frames");

        int framesWithLayout = 0;
        Set<String> signatures = new HashSet<>();
        for (SimulationTimePoint point : timeline) {
            if (point.getFrameSeatLayout() == null || point.getFrameSeatLayout().isEmpty()) {
                continue;
            }
            framesWithLayout++;
            StringBuilder sig = new StringBuilder();
            point.getFrameSeatLayout().forEach(layout -> {
                sig.append(layout.getTableId()).append(':');
                sig.append(String.join(",", layout.getOccupiedGroupIds()));
                sig.append('|');
            });
            signatures.add(sig.toString());
        }
        assertTrue(framesWithLayout > 5,
                "expected >5 frames with frame_seat_layout, got " + framesWithLayout);
        assertTrue(signatures.size() >= 3,
                "expected ≥3 distinct frame signatures (per-frame group layout must vary), got "
                        + signatures.size());
    }

    @Test
    void responseSerializationShouldKeepFrameSeatLayoutAfterStrippingTableSnapshots() throws Exception {
        SimulationReport report = runService.run(highGroupPressureConfig());
        ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
        ObjectNode reportJson = (ObjectNode) mapper.valueToTree(report);

        ObjectNode summary = (ObjectNode) reportJson.path("summary");
        JsonNode timeline = summary.path("timeline");
        assertTrue(timeline.isArray() && timeline.size() > 0, "timeline must be array");

        // 模拟 ReportResponseBuilder 默认路径:剥 table_snapshots,保留 frame_seat_layout
        for (JsonNode item : timeline) {
            if (item instanceof ObjectNode itemObject) {
                itemObject.remove("table_snapshots");
            }
        }

        int framesWithLayout = 0;
        for (JsonNode item : timeline) {
            JsonNode layout = item.path("frame_seat_layout");
            if (layout.isArray() && layout.size() > 0) {
                framesWithLayout++;
            }
            assertTrue(item.path("table_snapshots").isMissingNode(),
                    "table_snapshots should be stripped from each frame");
        }
        assertTrue(framesWithLayout > 5,
                "expected >5 frames retaining frame_seat_layout after strip, got " + framesWithLayout);
    }

    @Test
    void serializedFrameSeatLayoutShouldExposeOccupiedGroupIds() throws Exception {
        SimulationReport report = runService.run(highGroupPressureConfig());
        ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
        ObjectNode reportJson = (ObjectNode) mapper.valueToTree(report);

        JsonNode timeline = reportJson.path("summary").path("timeline");
        assertTrue(timeline.isArray());

        boolean foundGroupedOccupancy = false;
        for (JsonNode item : timeline) {
            JsonNode layout = item.path("frame_seat_layout");
            if (!layout.isArray()) continue;
            for (JsonNode entry : layout) {
                JsonNode ids = entry.path("occupied_group_ids");
                if (ids.isArray() && ids.size() > 0) {
                    foundGroupedOccupancy = true;
                    break;
                }
            }
            if (foundGroupedOccupancy) break;
        }
        assertTrue(foundGroupedOccupancy,
                "expected at least one frame to expose occupied_group_ids in JSON");
    }

    private SimConfig highGroupPressureConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-frame-layout");
        config.setDuration(1.0);
        config.setArrivalRate(220);
        config.setQueueLimit(35);
        config.setPackProbability(0.12);
        config.setSeed(20260801L);
        config.getBaseConfig().setTotalSeats(160);
        config.getBaseConfig().setTotalStudents(220);
        config.getBaseConfig().setWindowCount(7);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.40));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        SimConfig.GroupConfig group = new SimConfig.GroupConfig();
        group.setEnabled(true);
        group.setGroupCount(35);
        group.setSizeMin(3);
        group.setSizeMax(5);
        group.setBehaviorCorrelation(0.85);
        group.setPreferAdjacentSeats(true);
        config.setGroupConfig(group);
        config.setGroupArrivalProb(0.65);
        config.setPartySize(4);
        return config;
    }
}
