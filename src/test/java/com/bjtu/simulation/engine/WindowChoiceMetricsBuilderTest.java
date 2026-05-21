package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import com.bjtu.simulation.dto.WindowChoiceMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9D 补丁:三类 avg_wait_minutes 必须按 {@code partySize} 加权,
 * 与 {@link com.bjtu.simulation.service.WaitTimeMetricsCalculator} 口径对齐。
 *
 * <p>构造同一 popular 窗口下两个 {@link WaitTimeSample}:</p>
 * <ul>
 *   <li>wait=10min, partySize=3</li>
 *   <li>wait=1min,  partySize=1</li>
 * </ul>
 *
 * <p>正确口径(partySize 加权): (10×3 + 1×1) / (3+1) = 7.75</p>
 * <p>错误口径(条数平均):       (10 + 1) / 2 = 5.5</p>
 */
class WindowChoiceMetricsBuilderTest {

    @Test
    void avgWaitMinutesIsWeightedByPartySize() {
        // index 0 = POPULAR, index 1 = NORMAL,无 cold,无 takeaway
        List<WindowRole> roles = List.of(WindowRole.POPULAR, WindowRole.NORMAL);
        List<Integer> servedCounts = List.of(0, 0);

        // wait = (serviceStart - queueEnter) / 60;构造 600s/60s 得 10min/1min
        WaitTimeSample s1 = new WaitTimeSample(
                /* queueEnterTimeSeconds */ 0L,
                /* serviceStartTimeSeconds */ 600L,
                /* partySize */ 3,
                /* windowId */ 0,
                /* windowType */ "NORMAL",
                /* queueLengthAtJoin */ 0,
                WaitTimeSample.Phase.STEADY);
        WaitTimeSample s2 = new WaitTimeSample(
                0L, 60L, 1, 0, "NORMAL", 0, WaitTimeSample.Phase.STEADY);

        WindowChoiceMetrics metrics = WindowChoiceMetricsBuilder.build(
                "PREFERENCE_AWARE",
                roles,
                servedCounts,
                Map.of(),               // 不测 preference share
                List.of(s1, s2),
                List.of());             // 不测 max gap

        assertNotNull(metrics, "PREFERENCE_AWARE roles 非空时必须返回 metrics");
        assertEquals(7.75, metrics.getPopularAvgWaitMinutes(), 1e-9,
                "popular_avg_wait_minutes 必须按 partySize 加权 = (10×3 + 1×1)/4 = 7.75");
        assertEquals(0.0, metrics.getNormalAvgWaitMinutes(), 0.0,
                "normal 桶无样本时为 0.0");
        assertEquals(0.0, metrics.getColdAvgWaitMinutes(), 0.0,
                "cold 桶无样本时为 0.0");
    }

    @Test
    void emptyWaitSamplesYieldZeroAvgWait() {
        List<WindowRole> roles = List.of(WindowRole.POPULAR, WindowRole.NORMAL, WindowRole.COLD);
        WindowChoiceMetrics metrics = WindowChoiceMetricsBuilder.build(
                "PREFERENCE_AWARE",
                roles,
                List.of(0, 0, 0),
                Map.of(),
                List.of(),
                List.of());
        assertNotNull(metrics);
        assertEquals(0.0, metrics.getPopularAvgWaitMinutes(), 0.0);
        assertEquals(0.0, metrics.getNormalAvgWaitMinutes(), 0.0);
        assertEquals(0.0, metrics.getColdAvgWaitMinutes(), 0.0);
    }
}
