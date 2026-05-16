package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.SimulationTimePoint;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 第八轮 B1+B2:校验分层座位指标
 *  - frame 级:seatUnavailableRate / seatReservedShare / seatFreeRate / reservedSeats
 *  - summary 级:seatTimeWeightedUtilization / seatTurnoverRate / peakSeatUtilizationRate / steadyStateSeatUtilization
 */
class SeatUtilizationMetricsTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void summaryShouldExposeTieredSeatMetrics() {
        SimulationReport report = runService.run(workloadConfig());
        SimulationSummary summary = report.getSummary();

        // 翻台率 = dineInCount / totalSeats,可大于 1
        double expectedTurnover = (double) summary.getDineInCount() / summary.getTotalSeats();
        assertEquals(expectedTurnover, summary.getSeatTurnoverRate(), 0.01,
                "seatTurnoverRate should equal dineInCount / totalSeats");

        // 峰值占用率 ≤ 1.0,且 ≥ avg
        assertTrue(summary.getPeakSeatUtilizationRate() <= 1.0,
                "peakSeatUtilizationRate must be ≤ 1.0");
        assertTrue(summary.getPeakSeatUtilizationRate() >= summary.getSeatUtilizationRate() - 0.01,
                "peakSeatUtilizationRate (" + summary.getPeakSeatUtilizationRate()
                        + ") must be ≥ avg (" + summary.getSeatUtilizationRate() + ")");

        // 时间加权占用率属于 [0, 1]
        double weighted = summary.getSeatTimeWeightedUtilization();
        assertTrue(weighted >= 0.0 && weighted <= 1.0,
                "seatTimeWeightedUtilization should be in [0,1], got " + weighted);

        // 稳态占用率属于 [0, 1]
        double steady = summary.getSteadyStateSeatUtilization();
        assertTrue(steady >= 0.0 && steady <= 1.0,
                "steadyStateSeatUtilization should be in [0,1], got " + steady);

        // 兼容字段未被破坏
        assertTrue(summary.getSeatUtilizationRate() >= 0.0 && summary.getSeatUtilizationRate() <= 1.0);
    }

    @Test
    void timelineFramesShouldExposeUnavailableShareAndFreeRate() {
        SimulationReport report = runService.run(workloadConfig());
        List<SimulationTimePoint> timeline = report.getSummary().getTimeline();
        assertTrue(!timeline.isEmpty(), "timeline should be present");

        boolean foundReserved = false;
        for (SimulationTimePoint point : timeline) {
            // 每帧 unavailable >= utilization(unavailable 包含 reserved)
            assertTrue(point.getSeatUnavailableRate() + 0.01 >= point.getSeatUtilizationRate(),
                    "seat_unavailable_rate (" + point.getSeatUnavailableRate()
                            + ") must be ≥ seat_utilization_rate (" + point.getSeatUtilizationRate()
                            + ") at minute " + point.getMinute());

            // free + unavailable ≈ 1.0
            double sum = point.getSeatFreeRate() + point.getSeatUnavailableRate();
            assertTrue(Math.abs(sum - 1.0) < 0.05 || point.getTotalSeats() == 0,
                    "seat_free_rate + seat_unavailable_rate should be ≈ 1, got " + sum);

            if (point.getReservedSeats() > 0) {
                foundReserved = true;
            }
        }
        // 高压力场景下,至少有一帧出现预定座位
        assertTrue(foundReserved,
                "expected at least one frame to have reserved_seats > 0 under high pressure");
    }

    private SimConfig workloadConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-tiered-metrics");
        config.setDuration(1.5);
        config.setArrivalRate(280);
        config.setQueueLimit(40);
        config.setPackProbability(0.13);
        config.setSeed(20260820L);
        config.getBaseConfig().setTotalSeats(180);
        config.getBaseConfig().setTotalStudents(360);
        config.getBaseConfig().setWindowCount(8);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.45));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }
}
