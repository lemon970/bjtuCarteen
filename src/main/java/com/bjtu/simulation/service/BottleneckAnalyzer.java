package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.bjtu.simulation.dto.BottleneckDiagnosis;
import com.bjtu.simulation.dto.BottleneckEvidence;
import com.bjtu.simulation.dto.BottleneckSeverity;
import com.bjtu.simulation.dto.BottleneckType;
import com.bjtu.simulation.dto.DetectedBottleneck;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationSummary;

import org.springframework.stereotype.Service;

/**
 * RFC-012:派生瓶颈诊断后处理器(纯 if/else 闭式分发)。
 *
 * <p>输入只读 {@link SimulationSummary} + {@link SimConfig};不读 engine、不消耗 RNG、
 * 无反射、无 LLM。同输入两次 {@code analyze(...)} 字节级稳定。</p>
 *
 * <p>4 类瓶颈触发阈值统一 0.85 + 3 段 severity:
 * LOW [0.85,0.90)、MEDIUM [0.90,0.95)、HIGH [0.95,&infin;)。
 * v2 §4 起草时各类阈值不一致,本 RFC 统一收紧 — 扩展需独立 RFC。</p>
 *
 * <p>4 类公式:
 * <ul>
 *   <li>{@code WINDOW_SERVICE_CAPACITY}:max(util_i) for windowTypes[i] != "TAKEAWAY",
 *       {@code util_i = (servedCounts[i] * meanServiceSeconds) / endSeconds}</li>
 *   <li>{@code SEAT_CAPACITY}:{@code summary.getSeatUtilizationRate()}</li>
 *   <li>{@code TAKEAWAY_CAPACITY}:同 WINDOW 公式但只对 windowTypes[i] == "TAKEAWAY"</li>
 *   <li>{@code ARRIVAL_SURGE}:{@code maxTotalQueueSize / (windowCount * max(1, queueLimit))}
 *       — 与 {@code SimulationEngine.currentQueuePressure()} 同口径(v2 §4 误公式分母仅
 *       queueLimit,本 RFC 修订为 windowCount × queueLimit)</li>
 * </ul></p>
 *
 * <p>{@code meanServiceSeconds} 三段兜底:RandomBounds.serviceRange 中点 →
 * 默认 (45+180)/2 = 112.5 秒(与 {@code WaitExperienceProxyCalculator} 同口径)。</p>
 */
@Service
public class BottleneckAnalyzer {

    static final double THRESHOLD_TRIGGER = 0.85;
    static final double THRESHOLD_MEDIUM = 0.90;
    static final double THRESHOLD_HIGH = 0.95;

    private static final double DEFAULT_SERVICE_MIN_SECONDS = 45.0;
    private static final double DEFAULT_SERVICE_MAX_SECONDS = 180.0;

    private static final String METRIC_WINDOW_UTIL_MAX = "windowUtilizationMax";
    private static final String METRIC_SEAT_UTIL = "seatUtilizationRate";
    private static final String METRIC_TAKEAWAY_UTIL_MAX = "takeawayWindowUtilizationMax";
    private static final String METRIC_QUEUE_PRESSURE_MAX = "queuePressureMax";

    public BottleneckDiagnosis analyze(SimulationSummary summary, SimConfig config) {
        if (summary == null || config == null) {
            return new BottleneckDiagnosis(BottleneckType.BALANCED, null, List.of());
        }
        List<DetectedBottleneck> hits = new ArrayList<>(4);
        addWindowServiceCapacity(hits, summary, config);
        addSeatCapacity(hits, summary);
        addTakeawayCapacity(hits, summary, config);
        addArrivalSurge(hits, summary, config);
        sortBySeverityThenEnum(hits);
        BottleneckType primary = hits.isEmpty() ? BottleneckType.BALANCED : hits.get(0).getType();
        BottleneckType secondary = hits.size() >= 2 ? hits.get(1).getType() : null;
        return new BottleneckDiagnosis(primary, secondary, hits);
    }

    private void addWindowServiceCapacity(List<DetectedBottleneck> hits,
                                          SimulationSummary summary,
                                          SimConfig config) {
        long endSec = summary.getSimulationEndTimeSeconds();
        if (endSec <= 0) {
            return;
        }
        List<Integer> servedCounts = summary.getWindowServedCounts();
        if (servedCounts == null || servedCounts.isEmpty()) {
            return;
        }
        List<String> windowTypes = summary.getWindowTypes();
        if (windowTypes == null) {
            // 防御:无类型信息 → 保守跳过 WINDOW / TAKEAWAY 检测(T-12-19)
            return;
        }
        double meanService = resolveMeanServiceSeconds(config);
        if (meanService <= 0) {
            return;
        }
        int n = Math.min(servedCounts.size(), windowTypes.size());
        double maxUtil = Double.NEGATIVE_INFINITY;
        int maxIdx = -1;
        for (int i = 0; i < n; i++) {
            if (isTakeaway(windowTypes, i)) {
                continue;
            }
            int served = servedCounts.get(i) == null ? 0 : servedCounts.get(i);
            double util = (served * meanService) / (double) endSec;
            if (util > maxUtil) {
                maxUtil = util;
                maxIdx = i;
            }
        }
        if (maxIdx < 0) {
            return;
        }
        if (maxUtil >= THRESHOLD_TRIGGER) {
            hits.add(new DetectedBottleneck(
                    BottleneckType.WINDOW_SERVICE_CAPACITY,
                    severityOf(maxUtil),
                    new BottleneckEvidence(METRIC_WINDOW_UTIL_MAX, maxUtil, THRESHOLD_TRIGGER, maxIdx)));
        }
    }

    private void addSeatCapacity(List<DetectedBottleneck> hits, SimulationSummary summary) {
        if (summary.getTotalSeats() <= 0) {
            return;
        }
        double rate = summary.getSeatUtilizationRate();
        if (rate >= THRESHOLD_TRIGGER) {
            hits.add(new DetectedBottleneck(
                    BottleneckType.SEAT_CAPACITY,
                    severityOf(rate),
                    new BottleneckEvidence(METRIC_SEAT_UTIL, rate, THRESHOLD_TRIGGER, -1)));
        }
    }

    private void addTakeawayCapacity(List<DetectedBottleneck> hits,
                                     SimulationSummary summary,
                                     SimConfig config) {
        long endSec = summary.getSimulationEndTimeSeconds();
        if (endSec <= 0) {
            return;
        }
        List<Integer> servedCounts = summary.getWindowServedCounts();
        if (servedCounts == null || servedCounts.isEmpty()) {
            return;
        }
        List<String> windowTypes = summary.getWindowTypes();
        if (windowTypes == null) {
            return;
        }
        double meanService = resolveMeanServiceSeconds(config);
        if (meanService <= 0) {
            return;
        }
        int n = Math.min(servedCounts.size(), windowTypes.size());
        double maxUtil = Double.NEGATIVE_INFINITY;
        int maxIdx = -1;
        for (int i = 0; i < n; i++) {
            if (!isTakeaway(windowTypes, i)) {
                continue;
            }
            int served = servedCounts.get(i) == null ? 0 : servedCounts.get(i);
            double util = (served * meanService) / (double) endSec;
            if (util > maxUtil) {
                maxUtil = util;
                maxIdx = i;
            }
        }
        if (maxIdx < 0) {
            return;
        }
        if (maxUtil >= THRESHOLD_TRIGGER) {
            hits.add(new DetectedBottleneck(
                    BottleneckType.TAKEAWAY_CAPACITY,
                    severityOf(maxUtil),
                    new BottleneckEvidence(METRIC_TAKEAWAY_UTIL_MAX, maxUtil, THRESHOLD_TRIGGER, maxIdx)));
        }
    }

    private void addArrivalSurge(List<DetectedBottleneck> hits,
                                 SimulationSummary summary,
                                 SimConfig config) {
        SimConfig.BaseConfig baseConfig = config.getBaseConfig();
        if (baseConfig == null) {
            return;
        }
        int windowCount = baseConfig.getWindowCount();
        if (windowCount <= 0) {
            return;
        }
        int queueLimit = Math.max(1, config.getQueueLimit());
        double pressure = summary.getMaxTotalQueueSize() / (double) (windowCount * queueLimit);
        if (pressure >= THRESHOLD_TRIGGER) {
            hits.add(new DetectedBottleneck(
                    BottleneckType.ARRIVAL_SURGE,
                    severityOf(pressure),
                    new BottleneckEvidence(METRIC_QUEUE_PRESSURE_MAX, pressure, THRESHOLD_TRIGGER, -1)));
        }
    }

    /**
     * meanServiceSeconds 三段兜底链(不允许 NPE):
     * <ol>
     *   <li>config.randomBounds.serviceRange 非空 size&ge;2 且中点&gt;0 → 取中点</li>
     *   <li>(本 RFC 不引入 SimConfig.getServiceMean,跳到下一段)</li>
     *   <li>(45+180)/2 = 112.5 秒(与 WaitExperienceProxyCalculator 同口径)</li>
     * </ol>
     */
    private static double resolveMeanServiceSeconds(SimConfig config) {
        SimConfig.RandomBounds bounds = config.getRandomBounds();
        if (bounds != null) {
            List<Integer> range = bounds.getServiceRange();
            if (range != null && range.size() >= 2) {
                Integer min = range.get(0);
                Integer max = range.get(1);
                if (min != null && max != null) {
                    double mid = (min + max) / 2.0;
                    if (mid > 0) {
                        return mid;
                    }
                }
            }
        }
        return (DEFAULT_SERVICE_MIN_SECONDS + DEFAULT_SERVICE_MAX_SECONDS) / 2.0;
    }

    /**
     * windowTypes 安全判断(防御式):equalsIgnoreCase 守历史数据混合大小写;
     * null / 越界 / 空串均回退到 NORMAL 语义(即不算 takeaway)。
     */
    private static boolean isTakeaway(List<String> windowTypes, int idx) {
        if (windowTypes == null || idx < 0 || idx >= windowTypes.size()) {
            return false;
        }
        String t = windowTypes.get(idx);
        if (t == null) {
            return false;
        }
        return "TAKEAWAY".equalsIgnoreCase(t.trim());
    }

    private static BottleneckSeverity severityOf(double value) {
        if (value >= THRESHOLD_HIGH) {
            return BottleneckSeverity.HIGH;
        }
        if (value >= THRESHOLD_MEDIUM) {
            return BottleneckSeverity.MEDIUM;
        }
        return BottleneckSeverity.LOW;
    }

    /**
     * 排序规则(规则透明):
     * <ol>
     *   <li>severity 降序(HIGH &gt; MEDIUM &gt; LOW)</li>
     *   <li>severity 相同时按 BottleneckType 声明顺序
     *       (WINDOW_SERVICE_CAPACITY &gt; SEAT_CAPACITY &gt; TAKEAWAY_CAPACITY &gt; ARRIVAL_SURGE)</li>
     * </ol>
     */
    private static void sortBySeverityThenEnum(List<DetectedBottleneck> hits) {
        hits.sort(Comparator
                .comparing(DetectedBottleneck::getSeverity).reversed()
                .thenComparing(DetectedBottleneck::getType));
    }
}
