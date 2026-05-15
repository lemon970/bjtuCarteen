package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bjtu.simulation.dto.WaitTimeBucket;
import com.bjtu.simulation.dto.WaitTimeMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

class WaitTimeMetricsCalculatorTest {
    private final WaitTimeMetricsCalculator calculator = new WaitTimeMetricsCalculator();

    @Test
    void typicalWaitShouldIgnoreWarmupAndCooldownBias() {
        List<WaitTimeSample> samples = List.of(
                new WaitTimeSample(0, 0, 40, 0, "NORMAL", 0, WaitTimeSample.Phase.WARMUP),
                new WaitTimeSample(600, 960, 100, 0, "NORMAL", 8, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(7_000, 8_800, 50, 0, "NORMAL", 60, WaitTimeSample.Phase.COOLDOWN));

        WaitTimeMetrics metrics = calculator.build(samples, 90, 0.62);

        assertTrue(metrics.getRawAvgWaitTimeMinutes() > 10.0);
        assertEquals(6.0, metrics.getSteadyAvgWaitTimeMinutes(), 0.000001);
        assertEquals(6.0, metrics.getTypicalWaitTimeMinutes(), 0.000001);
        assertEquals(6.0, metrics.getMedianWaitTimeMinutes(), 0.000001);
        assertEquals(6.0, metrics.getP75WaitTimeMinutes(), 0.000001);
        assertEquals(6.0, metrics.getP90WaitTimeMinutes(), 0.000001);
        assertEquals(0.474, metrics.getEdgeWaitSampleRate(), 0.000001);
        assertEquals("warning", metrics.getWaitTimeInsight().getStatus());
        assertTrue(metrics.getWaitTimeInsight().getSecondaryReasons().stream()
                .anyMatch(reason -> reason.contains("开头/结尾")));
    }

    @Test
    void distributionShouldUseWeightedStudentCount() {
        List<WaitTimeSample> samples = List.of(
                new WaitTimeSample(0, 60, 2, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 360, 3, 0, "NORMAL", 4, WaitTimeSample.Phase.STEADY),
                new WaitTimeSample(0, 900, 5, 0, "NORMAL", 10, WaitTimeSample.Phase.STEADY));

        WaitTimeMetrics metrics = calculator.build(samples, 10, 0.30);
        int bucketTotal = metrics.getWaitTimeDistribution().stream().mapToInt(WaitTimeBucket::getCount).sum();

        assertEquals(10, bucketTotal);
        assertEquals(0.5, metrics.getLongWaitRate(), 0.000001);
        assertTrue(metrics.getMedianWaitTimeMinutes() >= 0.0);
    }
}
