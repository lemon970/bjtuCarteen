package com.bjtu.simulation.dto;

/**
 * RFC-010C:一条 metric 在一条扫描轴上的曲线 + 标准化敏感系数。
 *
 * <p>{@code summarySensitivity = (max - min) / max(|centerY|, 1e-9)},其中
 * {@code centerY = meanAtPoint[points.length / 2]}(M 个扫描点的中位下标,对偶数 M 取后半段第一个)。
 * 用 {@code 1e-9} 守住 0 中心情况下的除零。</p>
 *
 * <p>3 个 PR-9D 字段(popularServedShare / coldServedShare / windowServedCountCv)在 STATIC_SPLIT
 * 路径下整条 curve 输出 null,通过 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略。本类作为 element
 * 时由 {@link AxisResult} 决定。</p>
 */
public class MetricSensitivityCurve {

    private final String metricName;
    private final double[] meanAtPoint;
    private final double summarySensitivity;

    public MetricSensitivityCurve(String metricName,
                                  double[] meanAtPoint,
                                  double summarySensitivity) {
        this.metricName = metricName;
        this.meanAtPoint = meanAtPoint;
        this.summarySensitivity = summarySensitivity;
    }

    public String getMetricName() {
        return metricName;
    }

    public double[] getMeanAtPoint() {
        return meanAtPoint;
    }

    public double getSummarySensitivity() {
        return summarySensitivity;
    }
}
