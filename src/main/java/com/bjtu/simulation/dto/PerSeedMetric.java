package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC-010A:单 seed 提取的 11 个核心 metric 快照。
 *
 * <p>3 个 PR-9D 字段(popularServedShare / coldServedShare / windowServedCountCv)在 STATIC_SPLIT
 * 路径下为 null,通过 boxed {@code Double} + {@code @JsonInclude(NON_NULL)} 在 JSON 中省略。</p>
 */
public class PerSeedMetric {

    private final long seed;
    private final String reportId;

    private final int arrivedCount;
    private final int servedCount;
    private final double typicalWaitTimeMinutes;
    private final double medianWaitTimeMinutes;
    private final double p90WaitTimeMinutes;
    private final double seatUtilizationRate;
    private final double takeawayRate;
    private final int maxTotalQueueSize;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Double popularServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Double coldServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Double windowServedCountCv;

    public PerSeedMetric(long seed,
                         String reportId,
                         int arrivedCount,
                         int servedCount,
                         double typicalWaitTimeMinutes,
                         double medianWaitTimeMinutes,
                         double p90WaitTimeMinutes,
                         double seatUtilizationRate,
                         double takeawayRate,
                         int maxTotalQueueSize,
                         Double popularServedShare,
                         Double coldServedShare,
                         Double windowServedCountCv) {
        this.seed = seed;
        this.reportId = reportId;
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

    public long getSeed() {
        return seed;
    }

    public String getReportId() {
        return reportId;
    }

    public int getArrivedCount() {
        return arrivedCount;
    }

    public int getServedCount() {
        return servedCount;
    }

    public double getTypicalWaitTimeMinutes() {
        return typicalWaitTimeMinutes;
    }

    public double getMedianWaitTimeMinutes() {
        return medianWaitTimeMinutes;
    }

    public double getP90WaitTimeMinutes() {
        return p90WaitTimeMinutes;
    }

    public double getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public double getTakeawayRate() {
        return takeawayRate;
    }

    public int getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    public Double getPopularServedShare() {
        return popularServedShare;
    }

    public Double getColdServedShare() {
        return coldServedShare;
    }

    public Double getWindowServedCountCv() {
        return windowServedCountCv;
    }
}
