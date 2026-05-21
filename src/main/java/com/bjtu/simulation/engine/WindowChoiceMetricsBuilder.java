package com.bjtu.simulation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bjtu.simulation.dto.SimulationResult;
import com.bjtu.simulation.dto.WindowChoiceMetrics;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.WaitTimeSample;

/**
 * RFC-009 PR-9D:从 PREFERENCE_AWARE 引擎状态构造 {@link WindowChoiceMetrics}。
 *
 * <p>所有 share 类指标分母统一锁定**普通窗口集合**(POPULAR + NORMAL + COLD),
 * 打包窗口不计入分母(§9.2 守恒约束)。</p>
 *
 * <p>{@code max_window_queue_gap} = 任一时刻**普通窗口**队长极差的全程最大值,
 * 复用 {@code history.queueSizes}(已存在,无需新增 timeline 字段)。</p>
 *
 * <p>{@code window_served_count_cv} = 各**普通窗口**最终完成数的变异系数 (std/mean);
 * 不依赖 per-window time series(§11 Rev 3 已将 {@code window_queue_cv} 降级)。</p>
 */
final class WindowChoiceMetricsBuilder {

    private WindowChoiceMetricsBuilder() {
    }

    static WindowChoiceMetrics build(String queueChoiceModelName,
                                     List<WindowRole> windowRoles,
                                     List<Integer> windowServedCounts,
                                     Map<String, Student> studentRoster,
                                     List<WaitTimeSample> waitTimeSamples,
                                     List<SimulationResult> history) {
        if (windowRoles == null || windowRoles.isEmpty()) {
            return null;
        }

        int popularWindowCount = 0;
        int normalRoleCount = 0;
        int coldWindowCount = 0;
        int takeawayWindowCount = 0;
        List<Integer> normalWindowIndices = new ArrayList<>();
        for (int i = 0; i < windowRoles.size(); i++) {
            switch (windowRoles.get(i)) {
                case POPULAR -> {
                    popularWindowCount++;
                    normalWindowIndices.add(i);
                }
                case NORMAL -> {
                    normalRoleCount++;
                    normalWindowIndices.add(i);
                }
                case COLD -> {
                    coldWindowCount++;
                    normalWindowIndices.add(i);
                }
                case TAKEAWAY -> takeawayWindowCount++;
            }
        }

        // ---- Preference shares(普通窗口集合内归一) ----
        int popPref = 0;
        int normPref = 0;
        int coldPref = 0;
        if (studentRoster != null) {
            for (Student student : studentRoster.values()) {
                int wp = student.getWindowPreference();
                if (wp < 0 || wp >= windowRoles.size()) {
                    continue;
                }
                switch (windowRoles.get(wp)) {
                    case POPULAR -> popPref++;
                    case NORMAL -> normPref++;
                    case COLD -> coldPref++;
                    case TAKEAWAY -> { /* 不计入普通窗口分母 */ }
                }
            }
        }
        double prefDenom = popPref + normPref + coldPref;
        double popPrefShare = prefDenom > 0 ? popPref / prefDenom : 0.0;
        double normPrefShare = prefDenom > 0 ? normPref / prefDenom : 0.0;
        double coldPrefShare = prefDenom > 0 ? coldPref / prefDenom : 0.0;

        // ---- Served shares(普通窗口集合内归一) ----
        long popServed = 0;
        long normServed = 0;
        long coldServed = 0;
        if (windowServedCounts != null) {
            for (int i = 0; i < windowRoles.size() && i < windowServedCounts.size(); i++) {
                int s = Math.max(0, windowServedCounts.get(i));
                switch (windowRoles.get(i)) {
                    case POPULAR -> popServed += s;
                    case NORMAL -> normServed += s;
                    case COLD -> coldServed += s;
                    case TAKEAWAY -> { /* 不计入普通窗口分母 */ }
                }
            }
        }
        double servedDenom = popServed + normServed + coldServed;
        double popServedShare = servedDenom > 0 ? popServed / servedDenom : 0.0;
        double normServedShare = servedDenom > 0 ? normServed / servedDenom : 0.0;
        double coldServedShare = servedDenom > 0 ? coldServed / servedDenom : 0.0;

        // ---- Avg wait per role(各普通窗口角色样本均值) ----
        double[] waitSums = new double[3]; // [popular, normal, cold]
        long[] waitCounts = new long[3];
        if (waitTimeSamples != null) {
            for (WaitTimeSample sample : waitTimeSamples) {
                int wid = sample.getWindowId();
                if (wid < 0 || wid >= windowRoles.size()) {
                    continue;
                }
                int bucket = switch (windowRoles.get(wid)) {
                    case POPULAR -> 0;
                    case NORMAL -> 1;
                    case COLD -> 2;
                    default -> -1;
                };
                if (bucket < 0) {
                    continue;
                }
                waitSums[bucket] += sample.getWaitMinutes();
                waitCounts[bucket]++;
            }
        }
        double popAvgWait = waitCounts[0] > 0 ? waitSums[0] / waitCounts[0] : 0.0;
        double normAvgWait = waitCounts[1] > 0 ? waitSums[1] / waitCounts[1] : 0.0;
        double coldAvgWait = waitCounts[2] > 0 ? waitSums[2] / waitCounts[2] : 0.0;

        // ---- max_window_queue_gap:每帧普通窗口极差的全程最大值 ----
        int maxGap = 0;
        if (history != null && !normalWindowIndices.isEmpty()) {
            for (SimulationResult snapshot : history) {
                List<Integer> queues = snapshot.getQueueSizes();
                if (queues == null || queues.isEmpty()) {
                    continue;
                }
                int frameMin = Integer.MAX_VALUE;
                int frameMax = Integer.MIN_VALUE;
                for (int idx : normalWindowIndices) {
                    if (idx < 0 || idx >= queues.size()) {
                        continue;
                    }
                    int q = queues.get(idx);
                    if (q < frameMin) frameMin = q;
                    if (q > frameMax) frameMax = q;
                }
                if (frameMin == Integer.MAX_VALUE) {
                    continue;
                }
                int gap = frameMax - frameMin;
                if (gap > maxGap) {
                    maxGap = gap;
                }
            }
        }

        // ---- window_served_count_cv:普通窗口最终完成数 CV ----
        double cv = 0.0;
        if (!normalWindowIndices.isEmpty() && windowServedCounts != null) {
            double mean = servedDenom / normalWindowIndices.size();
            if (mean > 0.0) {
                double variance = 0.0;
                for (int idx : normalWindowIndices) {
                    if (idx < 0 || idx >= windowServedCounts.size()) {
                        continue;
                    }
                    double diff = windowServedCounts.get(idx) - mean;
                    variance += diff * diff;
                }
                variance /= normalWindowIndices.size();
                cv = Math.sqrt(variance) / mean;
            }
        }

        return new WindowChoiceMetrics(
                queueChoiceModelName,
                popularWindowCount,
                normalRoleCount,
                coldWindowCount,
                takeawayWindowCount,
                popPrefShare, normPrefShare, coldPrefShare,
                popServedShare, normServedShare, coldServedShare,
                popAvgWait, normAvgWait, coldAvgWait,
                maxGap,
                cv);
    }
}
