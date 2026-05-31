package com.bjtu.simulation.dto;

/**
 * RFC-012:派生瓶颈触发证据(immutable POJO)。
 *
 * <p>每个被检测到的 {@link DetectedBottleneck} 必带一份 evidence,列出触发诊断的指标名、
 * 实测值、阈值,以及(适用时)具体窗口 idx,确保规则透明可审计。</p>
 *
 * <p>{@code metricName} 取以下 4 个固定字符串之一,字面值原样输出(不经 snake_case 转换):</p>
 * <ul>
 *   <li>{@code "windowUtilizationMax"} — 非 TAKEAWAY 窗口最大 utilization</li>
 *   <li>{@code "seatUtilizationRate"} — 座位利用率(直接读 summary)</li>
 *   <li>{@code "takeawayWindowUtilizationMax"} — TAKEAWAY 窗口最大 utilization</li>
 *   <li>{@code "queuePressureMax"} — 队列压力 maxTotalQueueSize / (windowCount * queueLimit)</li>
 * </ul>
 *
 * <p>{@code observedValue} 已通过 round3 截断(与既有 metrics 一致)。{@code threshold}
 * 恒等 0.85,不做 round。{@code windowId} 在 SEAT_CAPACITY / ARRIVAL_SURGE 路径填 -1。</p>
 */
public class BottleneckEvidence {

    private final String metricName;
    private final double observedValue;
    private final double threshold;
    private final int windowId;

    public BottleneckEvidence(String metricName,
                              double observedValue,
                              double threshold,
                              int windowId) {
        this.metricName = metricName;
        this.observedValue = round3(observedValue);
        this.threshold = threshold;
        this.windowId = windowId;
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public String getMetricName() {
        return metricName;
    }

    public double getObservedValue() {
        return observedValue;
    }

    public double getThreshold() {
        return threshold;
    }

    public int getWindowId() {
        return windowId;
    }
}
