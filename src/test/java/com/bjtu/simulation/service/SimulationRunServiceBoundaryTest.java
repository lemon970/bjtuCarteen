package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationTimePoint;

import org.junit.jupiter.api.Test;

class SimulationRunServiceBoundaryTest {

    private final SimulationRunService runService = new SimulationRunService();

    // E1: run(null) 应当不抛,通过 ConfigNormalizer 兜底产出有效 report
    @Test
    void runWithNullConfigShouldNotThrowAndYieldValidReport() {
        SimulationReport report = assertDoesNotThrow(() -> runService.run(null));

        assertNotNull(report);
        assertNotNull(report.getReportId());
        assertNotNull(report.getSummary(), "summary must be present");
        assertTrue(report.getSummary().getArrivedCount() >= 0,
                "arrived_count must be non-negative");
        // 默认 arrivalRate=60、duration=1h、totalStudents=0(无人数上限,纯泊松到达),
        // 守约不变量:served + abandoned + leave + pendingDecision <= arrived
        int arrived = report.getSummary().getArrivedCount();
        int served = report.getSummary().getServedCount();
        int abandoned = report.getSummary().getAbandonedCount();
        assertTrue(served + abandoned <= arrived,
                () -> "served+abandoned (" + served + "+" + abandoned + ") must not exceed arrived=" + arrived);
        List<SimulationTimePoint> timeline = report.getSummary().getTimeline();
        assertNotNull(timeline);
        assertFalse(timeline.isEmpty(), "timeline must contain at least the minute=0 point");
        assertEquals(0L, timeline.get(0).getMinute());
    }

    // E2: 全外卖布局 + 雨天 + packProbability=1 应当几乎全为外卖,基本不变量保持
    @Test
    void allTakeawayLayoutInRainShouldServeMostlyTakeaway() {
        SimConfig config = new SimConfig();
        config.setSimulationName("e2-all-takeaway-rain");
        config.setDuration(0.5);
        config.setArrivalRate(180);
        config.setQueueLimit(20);
        config.setPackProbability(1.0);
        config.setSeed(20260518L);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getBaseConfig().setTotalSeats(20);
        config.getBaseConfig().setTotalStudents(50);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.15);
        config.getWeatherConfig().setCurrentWeather("rain");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);

        SimulationReport report = assertDoesNotThrow(() -> runService.run(config));

        int served = report.getSummary().getServedCount();
        int dineIn = report.getSummary().getDineInCount();
        int takeaway = report.getSummary().getTakeawayCount();
        assertEquals(served, dineIn + takeaway, "served must equal dineIn + takeaway");
        assertTrue(served > 0, "rain + arrivalRate=180 + 50 students should serve >0");
        assertTrue(takeaway >= dineIn,
                () -> "expected takeaway>=dineIn under all-takeaway layout, got takeaway="
                        + takeaway + ", dineIn=" + dineIn);
        assertEquals(served, report.getSummary().getTakeawayWindowServedCount(),
                "all served customers must come through takeaway windows when no normal window exists");
    }

    // E3: duration 严格等于 16h 上限不抛;P1-1 边界与归一化的接缝
    @Test
    void durationAtSixteenHourBoundaryShouldNotThrow() {
        SimConfig config = new SimConfig();
        config.setSimulationName("e3-16h-edge");
        config.setDuration(16.0);
        config.setArrivalRate(0);
        config.setQueueLimit(10);
        config.setPackProbability(0.2);
        config.setSeed(20260519L);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTotalSeats(20);
        config.getBaseConfig().setTotalStudents(0);

        SimulationReport report = assertDoesNotThrow(() -> runService.run(config));

        assertNotNull(report.getSummary());
        List<SimulationTimePoint> timeline = report.getSummary().getTimeline();
        assertNotNull(timeline);
        assertFalse(timeline.isEmpty());
        for (int i = 1; i < timeline.size(); i++) {
            long delta = timeline.get(i).getMinute() - timeline.get(i - 1).getMinute();
            assertEquals(1L, delta,
                    "16h timeline must keep minute granularity, found delta=" + delta + " at i=" + i);
        }
    }
}
