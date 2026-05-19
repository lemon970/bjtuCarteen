package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationTimePoint;

import org.junit.jupiter.api.Test;

/**
 * Bug-01 复现:午高峰压力测试在 ~第 34 分钟后打包窗口(index=7)排队人数极高,
 * 严重偏离用户期望的"各个窗口实际排队人数应该大致相同或略有误差"。
 *
 * 复现路径 = 人工操作:
 *   1. 加载预设 lunch_peak_pressure(ScenarioPresetCatalog)
 *   2. 运行该配置(SimulationRunService.run)
 *   3. 看时间线坐标轴上 window_queue_sizes 的分布
 *
 * 该预设固定 seed=20260512L,确定性可复现。
 */
class LunchPeakWindowBalanceTest {

    /**
     * 容忍上限:打包窗口最大单帧排队人数不应超过普通窗口最大单帧排队人数的 2 倍。
     * 用户期望"略有误差",2× 已经是非常宽松的容忍。当前 Bug-01 下打包窗口往往
     * 是普通窗口的 5-10 倍,这条断言在修复前必然失败。
     */
    private static final double BALANCE_TOLERANCE = 2.0;

    /**
     * Bug 报告中的关键时间点:peak (12,32) 结束后约 ~34 分钟开始观察到队列暴涨。
     * 在此之前,稀疏到达让单一打包窗口能跟上,这段时间不算关键证据,跳过。
     */
    private static final long INSPECTION_START_MINUTE = 34L;

    @Test
    void lunchPeakPressureShouldKeepTakeawayWindowQueueBalancedAfterMinute34() {
        ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();
        ScenarioPreset preset = catalog.find("lunch_peak_pressure")
                .orElseThrow(() -> new AssertionError("lunch_peak_pressure preset must exist"));
        SimConfig config = preset.config();

        // sanity:确认预设确实是 windowCount=8、takeawayWindowCount=1 的午高峰压力测试。
        assertTrue(config.getBaseConfig().getWindowCount() == 8,
                "preset windowCount changed; review test before re-using");
        assertTrue(config.getBaseConfig().getTakeawayWindowCount() == 1,
                "preset takeawayWindowCount changed; review test before re-using");

        SimulationRunService runService = new SimulationRunService();
        SimulationReport report = runService.run(config);

        assertNotNull(report.getSummary());
        List<SimulationTimePoint> timeline = report.getSummary().getTimeline();
        assertNotNull(timeline);
        assertTrue(timeline.size() > INSPECTION_START_MINUTE,
                "timeline must extend past minute " + INSPECTION_START_MINUTE);

        int normalWindowCount = report.getSummary().getNormalWindowCount();
        int takeawayWindowCount = report.getSummary().getTakeawayWindowCount();
        assertTrue(normalWindowCount == 7 && takeawayWindowCount == 1,
                "expected 7 normal + 1 takeaway, got normal=" + normalWindowCount
                        + ", takeaway=" + takeawayWindowCount);

        int maxNormalQueue = 0;
        int maxTakeawayQueue = 0;
        long peakTakeawayMinute = -1L;
        int peakNormalQueueAtPeakTakeawayMinute = 0;
        long framesPastInspection = 0;

        for (SimulationTimePoint frame : timeline) {
            if (frame.getMinute() < INSPECTION_START_MINUTE) {
                continue;
            }
            framesPastInspection++;
            List<Integer> queues = frame.getWindowQueueSizes();
            int frameNormalMax = 0;
            for (int i = 0; i < normalWindowCount && i < queues.size(); i++) {
                int q = queues.get(i);
                if (q > frameNormalMax) frameNormalMax = q;
                if (q > maxNormalQueue) maxNormalQueue = q;
            }
            for (int i = normalWindowCount; i < queues.size(); i++) {
                int q = queues.get(i);
                if (q > maxTakeawayQueue) {
                    maxTakeawayQueue = q;
                    peakTakeawayMinute = frame.getMinute();
                    peakNormalQueueAtPeakTakeawayMinute = frameNormalMax;
                }
            }
        }

        assertTrue(framesPastInspection > 0,
                "expected at least 1 timeline frame past minute " + INSPECTION_START_MINUTE);

        // 主断言:打包窗口最大队列不应是普通窗口最大队列的 BALANCE_TOLERANCE 倍以上。
        // 这是 Bug-01 的核心:用户期望各窗口大致均衡,实际打包窗口被强制路由,严重失衡。
        double ratio = maxNormalQueue == 0
                ? (maxTakeawayQueue > 0 ? Double.POSITIVE_INFINITY : 0.0)
                : (double) maxTakeawayQueue / maxNormalQueue;
        assertTrue(
                ratio <= BALANCE_TOLERANCE,
                String.format(
                        "Bug-01 reproduced: takeaway window queue is %.2fx the max normal window queue "
                                + "after minute %d (tolerance=%.1fx). "
                                + "maxTakeawayQueue=%d (peaked at minute %d, normal-max-then=%d), "
                                + "maxNormalQueue=%d (over the whole post-%d window). "
                                + "User expectation: queues across windows should be roughly balanced.",
                        ratio, INSPECTION_START_MINUTE, BALANCE_TOLERANCE,
                        maxTakeawayQueue, peakTakeawayMinute, peakNormalQueueAtPeakTakeawayMinute,
                        maxNormalQueue, INSPECTION_START_MINUTE));
    }
}
