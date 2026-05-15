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
