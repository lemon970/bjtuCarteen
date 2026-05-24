package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC-010B:{@link BatchRunReport#getAggregate()} 的聚合视图。11 个 metric 各一组 {@link MetricStat}。
 *
 * <p>3 个 PR-9D 字段(popularServedShare / coldServedShare / windowServedCountCv)在 STATIC_SPLIT
 * 下整组 MetricStat 为 null,通过 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略
 * (与 {@link PerSeedMetric} 的 boxed Double 模式同源)。</p>
 *
 * <p>本轮 ciMethod 全部为 "t"(t-interval)。Bootstrap 移到 Future Work。</p>
 */
public class AggregateMetrics {

    private final int sampleCount;

    private final MetricStat arrivedCount;
    private final MetricStat servedCount;
    private final MetricStat typicalWaitTimeMinutes;
    private final MetricStat medianWaitTimeMinutes;
    private final MetricStat p90WaitTimeMinutes;
    private final MetricStat seatUtilizationRate;
    private final MetricStat takeawayRate;
    private final MetricStat maxTotalQueueSize;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricStat popularServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricStat coldServedShare;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final MetricStat windowServedCountCv;

    public AggregateMetrics(int sampleCount,
                            MetricStat arrivedCount,
                            MetricStat servedCount,
                            MetricStat typicalWaitTimeMinutes,
                            MetricStat medianWaitTimeMinutes,
                            MetricStat p90WaitTimeMinutes,
                            MetricStat seatUtilizationRate,
                            MetricStat takeawayRate,
                            MetricStat maxTotalQueueSize,
                            MetricStat popularServedShare,
                            MetricStat coldServedShare,
                            MetricStat windowServedCountCv) {
        this.sampleCount = sampleCount;
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

    public int getSampleCount() {
        return sampleCount;
    }

    public MetricStat getArrivedCount() {
        return arrivedCount;
    }

    public MetricStat getServedCount() {
        return servedCount;
    }

    public MetricStat getTypicalWaitTimeMinutes() {
        return typicalWaitTimeMinutes;
    }

    public MetricStat getMedianWaitTimeMinutes() {
        return medianWaitTimeMinutes;
    }

    public MetricStat getP90WaitTimeMinutes() {
        return p90WaitTimeMinutes;
    }

    public MetricStat getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public MetricStat getTakeawayRate() {
        return takeawayRate;
    }

    public MetricStat getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    public MetricStat getPopularServedShare() {
        return popularServedShare;
    }

    public MetricStat getColdServedShare() {
        return coldServedShare;
    }

    public MetricStat getWindowServedCountCv() {
        return windowServedCountCv;
    }
}
