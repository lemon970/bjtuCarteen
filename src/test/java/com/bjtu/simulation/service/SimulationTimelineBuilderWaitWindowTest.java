package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bjtu.simulation.dto.SimulationResult;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.bjtu.simulation.model.WaitTimeSample;

class SimulationTimelineBuilderWaitWindowTest {

    private final SimulationTimelineBuilder builder = new SimulationTimelineBuilder();

    @Test
    void waitWindowShouldAggregateOnlyRecentSamples() {
        List<WaitTimeSample> samples = List.of(
                new WaitTimeSample(0, 60, 1, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 240, 2, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 480, 1, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 540, 3, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 600, 1, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY));

        List<SimulationResult> history = List.of(historyAt(60), historyAt(600));
        List<SimulationTimePoint> timeline = builder.build(history, 1, 0, List.of("NORMAL"), 1, 0, samples);

        SimulationTimePoint last = timeline.get(timeline.size() - 1);

        // window covers [300, 600]: samples at serviceStart 480 (1ppl, wait 8min), 540 (3ppl, wait 9min), 600 (1ppl, wait 10min)
        // weighted mean = (8*1 + 9*3 + 10*1) / 5 = 45/5 = 9.0
        assertEquals(9.0, last.getAvgWaitMinutesWindow(), 0.001);
        assertEquals(5, last.getWaitSampleCountWindow());
    }

    @Test
    void waitWindowShouldBeZeroWhenNoSamplesFitWindow() {
        List<WaitTimeSample> samples = List.of(
                new WaitTimeSample(0, 30, 1, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY));

        List<SimulationResult> history = List.of(historyAt(900));
        List<SimulationTimePoint> timeline = builder.build(history, 1, 0, List.of("NORMAL"), 1, 0, samples);

        SimulationTimePoint last = timeline.get(timeline.size() - 1);
        assertEquals(0.0, last.getAvgWaitMinutesWindow(), 0.001);
        assertEquals(0, last.getWaitSampleCountWindow());
    }

    @Test
    void waitWindowShouldDefaultToZeroWhenNoSamplesProvided() {
        List<SimulationResult> history = List.of(historyAt(60));
        List<SimulationTimePoint> timeline = builder.build(history, 1, 0, List.of("NORMAL"), 1, 0, List.of());

        SimulationTimePoint point = timeline.get(timeline.size() - 1);
        assertEquals(0.0, point.getAvgWaitMinutesWindow(), 0.001);
        assertEquals(0, point.getWaitSampleCountWindow());
    }

    @Test
    void backwardCompatibleBuildOmittingSamplesShouldStillSucceed() {
        List<SimulationResult> history = List.of(historyAt(60));
        List<SimulationTimePoint> timeline = builder.build(history, 1, 0, List.of("NORMAL"), 1, 0);

        assertFalse(timeline.isEmpty());
        assertTrue(timeline.get(0).getAvgWaitMinutesWindow() == 0.0);
    }

    // P1-1 回归:16h 仿真 + dining 长尾导致 engine.currentTime ≈ 1000 min。
    // MAX_TIMELINE_POINTS=2000 时 stepMinutes 仍=1,timeline 长度等于 endMinute+1。
    // 若常量被改回 1000,这个断言立即破裂。
    @Test
    void timelineShouldKeepMinuteGranularityAtSixteenHourTail() {
        List<SimulationResult> history = List.of(historyAt(60_000));
        List<SimulationTimePoint> timeline = builder.build(history, 1, 0, List.of("NORMAL"), 1, 0, List.of());

        assertEquals(1001, timeline.size(),
                "expected 1001 minute-granular points (endMinute=1000), got " + timeline.size());
        for (int i = 1; i < timeline.size(); i++) {
            long delta = timeline.get(i).getMinute() - timeline.get(i - 1).getMinute();
            assertEquals(1L, delta, "adjacent minutes must differ by 1, found " + delta + " at index " + i);
        }
    }

    @Test
    void emptyHistoryShouldProduceSinglePointTimeline() {
        List<SimulationTimePoint> timeline = builder.build(List.of(), 2, 20, List.of("NORMAL", "NORMAL"), 2, 0);

        assertEquals(1, timeline.size());
        SimulationTimePoint only = timeline.get(0);
        assertEquals(0L, only.getMinute());
        assertEquals(List.of(0, 0), only.getWindowQueueSizes());
        assertEquals(1.0, only.getSeatFreeRate(), 1e-9);
    }

    // 防御性裁剪:source 队列 5 项但 windowCount=3,windowTypes=null,normalCount=2。
    @Test
    void defensiveTruncationWhenSourceQueueExceedsWindowCount() {
        SimulationResult oversized = new SimulationResult(
                60, List.of(1, 2, 3, 4, 5), 15,
                0, 0, "",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0,
                0, 0.0, 0.0,
                new ArrayList<>());
        List<SimulationTimePoint> timeline = builder.build(List.of(oversized), 3, 0, null, 2, 1, List.of());

        SimulationTimePoint last = timeline.get(timeline.size() - 1);
        assertEquals(3, last.getWindowQueueSizes().size(), "queues must be truncated to windowCount=3");
        assertEquals(List.of(1, 2, 3), last.getWindowQueueSizes());
        assertEquals(List.of("NORMAL", "NORMAL", "TAKEAWAY"), last.getWindowTypes(),
                "null windowTypes should fall back to NORMAL[0..normal) + TAKEAWAY[normal..)");
    }

    private SimulationResult historyAt(long timeSeconds) {
        return new SimulationResult(
                timeSeconds,
                List.of(0),
                0,
                0,
                0,
                "",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0.0,
                0.0,
                new ArrayList<>());
    }
}
