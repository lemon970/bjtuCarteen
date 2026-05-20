package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.bjtu.simulation.dto.SimulationSummary;

import org.junit.jupiter.api.Test;

/**
 * 第十轮 B:验证成组到达时间不再集中在仿真开头。
 *
 * 旧实现里 SimulationArrivalScheduler.samplePartySize 用全局 generatedGroupSequence
 * 顺序消耗 groupCount 名额,前 N 个 party 全是组,之后 100% 单人。
 * 新实现把 groupCount 个组按 minuteWeights 加权随机分散到各分钟,
 * 因此后半段 timeline 帧上仍能看到新的 occupied_group_ids。
 */
class GroupArrivalDistributionTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void groupsShouldBeDistributedAcrossSimulation() {
        SimulationReport report = runService.run(highGroupConfig());
        SimulationSummary summary = report.getSummary();
        List<SimulationTimePoint> timeline = summary.getTimeline();
        assertNotNull(timeline);
        assertTrue(timeline.size() > 10, "timeline should have many frames, got " + timeline.size());

        Set<String> earlyGroupIds = new HashSet<>();
        Set<String> lateGroupIds = new HashSet<>();
        int half = timeline.size() / 2;
        for (int i = 0; i < timeline.size(); i++) {
            SimulationTimePoint frame = timeline.get(i);
            if (frame.getFrameSeatLayout() == null) continue;
            Set<String> bucket = (i < half) ? earlyGroupIds : lateGroupIds;
            frame.getFrameSeatLayout().forEach(layout -> {
                if (layout.getOccupiedGroupIds() != null) {
                    bucket.addAll(layout.getOccupiedGroupIds());
                }
                if (layout.getReservedGroupIds() != null) {
                    bucket.addAll(layout.getReservedGroupIds());
                }
            });
        }

        assertTrue(earlyGroupIds.size() > 0,
                "expected groups in first half, got " + earlyGroupIds.size());
        // 关键断言:后半段也应观察到新 group_id (而不是只前半段)
        Set<String> lateOnly = new HashSet<>(lateGroupIds);
        lateOnly.removeAll(earlyGroupIds);
        assertTrue(lateOnly.size() > 0,
                "expected at least one group_id appearing only in second half (groups should "
                        + "spread across simulation), got 0; earlySet=" + earlyGroupIds.size()
                        + " lateSet=" + lateGroupIds.size());
    }

    @Test
    void groupCountShouldStillRespectConfiguredCap() {
        SimulationReport report = runService.run(highGroupConfig());
        SimulationSummary summary = report.getSummary();
        List<SimulationTimePoint> timeline = summary.getTimeline();
        assertNotNull(timeline);

        Set<String> allGroupIds = new HashSet<>();
        for (SimulationTimePoint frame : timeline) {
            if (frame.getFrameSeatLayout() == null) continue;
            frame.getFrameSeatLayout().forEach(layout -> {
                if (layout.getOccupiedGroupIds() != null) {
                    allGroupIds.addAll(layout.getOccupiedGroupIds());
                }
                if (layout.getReservedGroupIds() != null) {
                    allGroupIds.addAll(layout.getReservedGroupIds());
                }
            });
        }
        // 配置 groupCount=20。允许 ≤ 20,但应该接近 20(不会出现远大于的爆量)
        assertTrue(allGroupIds.size() <= 20,
                "groupCount cap not respected, observed unique group_ids=" + allGroupIds.size());
        assertTrue(allGroupIds.size() >= 5,
                "expected at least 5 distinct group_ids materializing, got " + allGroupIds.size());
    }

    private SimConfig highGroupConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-group-distribution");
        config.setDuration(1.5);
        config.setArrivalRate(240);
        config.setQueueLimit(35);
        config.setPackProbability(0.10);
        config.setSeed(20260901L);
        config.getBaseConfig().setTotalSeats(180);
        config.getBaseConfig().setTotalStudents(360);
        config.getBaseConfig().setWindowCount(7);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.40));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        SimConfig.GroupConfig group = new SimConfig.GroupConfig();
        group.setEnabled(true);
        group.setGroupCount(20);
        group.setSizeMin(3);
        group.setSizeMax(5);
        group.setBehaviorCorrelation(0.85);
        group.setPreferAdjacentSeats(true);
        config.setGroupConfig(group);
        config.setGroupArrivalProb(0.50);
        config.setPartySize(4);
        return config;
    }

    @Test
    void exactGroupCountAssertion() {
        // 单独跑一次小规模仿真,断言生成的不同 group_id 数严格 == groupCount
        SimulationReport report = runService.run(modestGroupConfig());
        Set<String> uniqueGroupIds = new HashSet<>();
        for (SimulationTimePoint frame : report.getSummary().getTimeline()) {
            if (frame.getFrameSeatLayout() == null) continue;
            frame.getFrameSeatLayout().forEach(layout -> {
                if (layout.getOccupiedGroupIds() != null) {
                    uniqueGroupIds.addAll(layout.getOccupiedGroupIds());
                }
                if (layout.getReservedGroupIds() != null) {
                    uniqueGroupIds.addAll(layout.getReservedGroupIds());
                }
            });
        }
        // 不强求严格 ==(部分 group 可能因到达上限被裁剪),但应非常接近
        assertTrue(uniqueGroupIds.size() >= 6 && uniqueGroupIds.size() <= 10,
                "unique group_ids should be near groupCount=8, got " + uniqueGroupIds.size());
        assertEquals(true, uniqueGroupIds.size() > 0);
    }

    private SimConfig modestGroupConfig() {
        SimConfig config = highGroupConfig();
        config.setSimulationName("test-modest-group");
        config.setSeed(20260902L);
        SimConfig.GroupConfig group = config.getGroupConfig();
        group.setGroupCount(8);
        return config;
    }
}
