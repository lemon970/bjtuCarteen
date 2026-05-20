package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.model.ArrivalSample;

import org.junit.jupiter.api.Test;

/**
 * Bug-1 回归:fixed-interval 到达模式下成组学生应均匀分散到整段仿真,
 * 而不是只集中在仿真开头(旧实现里 generatedGroupSequence 顺序消耗导致的)。
 *
 * 修复后 scheduleFixedIntervalArrivalEvents 与主路径共用 allocateGroupsToMinutes,
 * 按到达权重把 groupCount 个组分散到各分钟。
 *
 * 测试直接看 arrivalSamples 的分布(到达时刻是契约的源头),不依赖 timeline 视图。
 */
class FixedIntervalGroupDistributionTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void fixedIntervalGroupArrivalTimesShouldSpreadAcrossSimulation() {
        SimulationReport report = runService.run(fixedIntervalGroupConfig(20261201L));
        SimulationSummary summary = report.getSummary();
        List<ArrivalSample> arrivalSamples = summary.getArrivalSamples();
        assertNotNull(arrivalSamples);
        assertTrue(!arrivalSamples.isEmpty(), "expected non-empty arrivalSamples");

        long durationSeconds = Math.round(report.getConfig().getDuration() * 3600.0);
        long halfSeconds = durationSeconds / 2;

        long groupArrivalsInFirstHalf = arrivalSamples.stream()
                .filter(s -> s.getPartySize() > 1)
                .filter(s -> s.getTimeSeconds() < halfSeconds)
                .count();
        long groupArrivalsInLateHalf = arrivalSamples.stream()
                .filter(s -> s.getPartySize() > 1)
                .filter(s -> s.getTimeSeconds() >= halfSeconds)
                .count();
        long totalGroupArrivals = groupArrivalsInFirstHalf + groupArrivalsInLateHalf;

        assertTrue(totalGroupArrivals >= 3,
                "至少应有 3 个组到达,observed=" + totalGroupArrivals);
        assertTrue(groupArrivalsInLateHalf > 0,
                "fixed-interval 后半段应至少有 1 个组到达(原 bug:全部集中前半段),"
                        + " firstHalf=" + groupArrivalsInFirstHalf
                        + " lateHalf=" + groupArrivalsInLateHalf);
    }

    @Test
    void fixedIntervalGroupCountShouldRespectConfiguredCap() {
        SimulationReport report = runService.run(fixedIntervalGroupConfig(20261202L));
        List<ArrivalSample> arrivalSamples = report.getSummary().getArrivalSamples();

        long groupArrivalCount = arrivalSamples.stream()
                .filter(s -> s.getPartySize() > 1)
                .count();
        assertTrue(groupArrivalCount <= 10,
                "groupCount=10 cap should be respected, observed " + groupArrivalCount);
        assertTrue(groupArrivalCount >= 3,
                "should observe at least 3 group arrivals, got " + groupArrivalCount);
    }

    private SimConfig fixedIntervalGroupConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("fixed-interval-group-test");
        config.setDuration(1.0);
        config.setArrivalRate(120);
        config.setQueueLimit(30);
        config.setPackProbability(0.10);
        config.setSeed(seed);
        config.getBaseConfig().setTotalSeats(240);
        config.getBaseConfig().setTotalStudents(0);
        config.getBaseConfig().setWindowCount(5);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.18);
        config.getRandomBounds().setArrivalInterval(20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.40));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        SimConfig.GroupConfig group = new SimConfig.GroupConfig();
        group.setEnabled(true);
        group.setGroupCount(10);
        group.setSizeMin(3);
        group.setSizeMax(5);
        group.setBehaviorCorrelation(0.85);
        group.setPreferAdjacentSeats(true);
        config.setGroupConfig(group);
        config.setPartySize(4);
        config.setGroupArrivalProb(0.50);
        return config;
    }
}
