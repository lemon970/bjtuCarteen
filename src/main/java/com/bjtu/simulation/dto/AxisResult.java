package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC-010C:一条扫描轴的完整结果。
 *
 * <p>{@code metrics} 长度严格为 11(8 核心 + 3 PR-9D);3 个 PR-9D 字段在 STATIC_SPLIT 路径下整条
 * curve 为 null,通过下面 3 个具名字段的 {@code @JsonInclude(NON_NULL)} 省略。8 核心字段不会为 null。</p>
 */
public class AxisResult {

    private final WhitelistedParam parameter;
    private final double[] points;

    private final MetricSensitivityCurve arrivedCount;
    private final MetricSensitivityCurve servedCount;
    private final MetricSensitivityCurve typicalWaitTimeMinutes;
    private final MetricSensitivityCurve medianWaitTimeMinutes;
    private final MetricSensitivityCurve p90WaitTimeMinutes;
    private final MetricSensitivityCurve seatUtilizationRate;
    private final MetricSensitivityCurve takeawayRate;
    private final MetricSensitivityCurve maxTotalQueueSize;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricSensitivityCurve popularServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricSensitivityCurve coldServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricSensitivityCurve windowServedCountCv;

    public AxisResult(WhitelistedParam parameter,
                      double[] points,
                      MetricSensitivityCurve arrivedCount,
                      MetricSensitivityCurve servedCount,
                      MetricSensitivityCurve typicalWaitTimeMinutes,
                      MetricSensitivityCurve medianWaitTimeMinutes,
                      MetricSensitivityCurve p90WaitTimeMinutes,
                      MetricSensitivityCurve seatUtilizationRate,
                      MetricSensitivityCurve takeawayRate,
                      MetricSensitivityCurve maxTotalQueueSize,
                      MetricSensitivityCurve popularServedShare,
                      MetricSensitivityCurve coldServedShare,
                      MetricSensitivityCurve windowServedCountCv) {
        this.parameter = parameter;
        this.points = points;
        this.arrivedCount = arrivedCount;
        this.servedCount = servedCount;
        this.typicalWaitTimeMinutes = typicalWaitTimeMinutes;
        this.medianWaitTimeMinutes = medianWaitTimeMinutes;
        this.p90WaitTimeMinutes = p90WaitTimeMinutes;
        this.seatUtilizationRate = seatUtilizationRate;
        this.takeawayRate = takeawayRate;
        this.maxTotalQueueSize = maxTotalQueueSize;
        this.popularServedShare = popularServedShare;
        this.coldServedShare = coldServedShare;
        this.windowServedCountCv = windowServedCountCv;
    }

    public WhitelistedParam getParameter() {
        return parameter;
    }

    public double[] getPoints() {
        return points;
    }

    public MetricSensitivityCurve getArrivedCount() {
        return arrivedCount;
    }

    public MetricSensitivityCurve getServedCount() {
        return servedCount;
    }

    public MetricSensitivityCurve getTypicalWaitTimeMinutes() {
        return typicalWaitTimeMinutes;
    }

    public MetricSensitivityCurve getMedianWaitTimeMinutes() {
        return medianWaitTimeMinutes;
    }

    public MetricSensitivityCurve getP90WaitTimeMinutes() {
        return p90WaitTimeMinutes;
    }

    public MetricSensitivityCurve getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public MetricSensitivityCurve getTakeawayRate() {
        return takeawayRate;
    }

    public MetricSensitivityCurve getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    public MetricSensitivityCurve getPopularServedShare() {
        return popularServedShare;
    }

    public MetricSensitivityCurve getColdServedShare() {
        return coldServedShare;
    }

    public MetricSensitivityCurve getWindowServedCountCv() {
        return windowServedCountCv;
    }
}
