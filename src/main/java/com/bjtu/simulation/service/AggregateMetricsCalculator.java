package com.bjtu.simulation.service;

import java.util.Arrays;
import java.util.List;

import com.bjtu.simulation.dto.AggregateMetrics;
import com.bjtu.simulation.dto.CiBounds;
import com.bjtu.simulation.dto.MetricStat;
import com.bjtu.simulation.dto.PerSeedMetric;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RFC-010B:从 {@code List<PerSeedMetric>} 聚合出 {@link AggregateMetrics}。
 *
 * <p>对每个 metric 字段提取成 {@code double[]},调用 {@code summarize} 算 mean / stddev / median /
 * p10 / p90,再调 {@link ConfidenceIntervalCalculator#compute(double[])} 算 95% CI。
 * percentile 使用 R type 7 / numpy default 的 linear interpolation。</p>
 *
 * <p>3 个 PR-9D 字段(popularServedShare / coldServedShare / windowServedCountCv)的 nullness
 * 必须在 N 个样本里**全 null** 或 **全非 null**,否则抛 {@link IllegalStateException}
 * (工程上不应发生:同一份 baseConfig 不会跑出 STATIC_SPLIT 与 PREFERENCE_AWARE 混合的报告)。</p>
 *
 * <p>纯函数,可独立于 Spring 容器调用(零参构造支持);Spring 注入路径走带 ciCalculator 的构造器。</p>
 */
@Service
public class AggregateMetricsCalculator {

    private final ConfidenceIntervalCalculator ciCalculator;

    public AggregateMetricsCalculator() {
        this(new ConfidenceIntervalCalculator());
    }

    @Autowired
    public AggregateMetricsCalculator(ConfidenceIntervalCalculator ciCalculator) {
        this.ciCalculator = ciCalculator;
    }

    public AggregateMetrics aggregate(List<PerSeedMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("metrics must be non-empty");
        }
        int n = metrics.size();

        // 8 核心字段:总是有值
        MetricStat arrivedCount = summarize(extractInt(metrics, m -> m.getArrivedCount()));
        MetricStat servedCount = summarize(extractInt(metrics, m -> m.getServedCount()));
        MetricStat typicalWait = summarize(extractDouble(metrics, m -> m.getTypicalWaitTimeMinutes()));
        MetricStat medianWait = summarize(extractDouble(metrics, m -> m.getMedianWaitTimeMinutes()));
        MetricStat p90Wait = summarize(extractDouble(metrics, m -> m.getP90WaitTimeMinutes()));
        MetricStat seatUtil = summarize(extractDouble(metrics, m -> m.getSeatUtilizationRate()));
        MetricStat takeawayRate = summarize(extractDouble(metrics, m -> m.getTakeawayRate()));
        MetricStat maxQueue = summarize(extractInt(metrics, m -> m.getMaxTotalQueueSize()));

        // 3 PR-9D 字段:全 null → 输出 null;全非 null → 聚合;部分 null → fail-fast
        MetricStat popularServedShare = summarizeBoxed(metrics, m -> m.getPopularServedShare(),
                "popularServedShare");
        MetricStat coldServedShare = summarizeBoxed(metrics, m -> m.getColdServedShare(),
                "coldServedShare");
        MetricStat windowServedCountCv = summarizeBoxed(metrics, m -> m.getWindowServedCountCv(),
                "windowServedCountCv");

        return new AggregateMetrics(n,
                arrivedCount, servedCount, typicalWait, medianWait, p90Wait,
                seatUtil, takeawayRate, maxQueue,
                popularServedShare, coldServedShare, windowServedCountCv);
    }

    @FunctionalInterface
    private interface IntExtractor {
        int apply(PerSeedMetric m);
    }

    @FunctionalInterface
    private interface DoubleExtractor {
        double apply(PerSeedMetric m);
    }

    @FunctionalInterface
    private interface BoxedExtractor {
        Double apply(PerSeedMetric m);
    }

    private double[] extractInt(List<PerSeedMetric> metrics, IntExtractor f) {
        double[] out = new double[metrics.size()];
        for (int i = 0; i < metrics.size(); i++) {
            out[i] = f.apply(metrics.get(i));
        }
        return out;
    }

    private double[] extractDouble(List<PerSeedMetric> metrics, DoubleExtractor f) {
        double[] out = new double[metrics.size()];
        for (int i = 0; i < metrics.size(); i++) {
            out[i] = f.apply(metrics.get(i));
        }
        return out;
    }

    private MetricStat summarizeBoxed(List<PerSeedMetric> metrics, BoxedExtractor f, String fieldName) {
        int nullCount = 0;
        int nonNullCount = 0;
        double[] data = new double[metrics.size()];
        for (int i = 0; i < metrics.size(); i++) {
            Double v = f.apply(metrics.get(i));
            if (v == null) {
                nullCount++;
            } else {
                nonNullCount++;
                data[i] = v;
            }
        }
        if (nullCount == metrics.size()) {
            return null;
        }
        if (nonNullCount == metrics.size()) {
            return summarize(data);
        }
        throw new IllegalStateException("inconsistent nullness for " + fieldName
                + ": null=" + nullCount + ", nonNull=" + nonNullCount
                + " across " + metrics.size() + " samples");
    }

    /** 单 metric 描述性统计。data 不会被持久化,允许就地排序。 */
    MetricStat summarize(double[] data) {
        double mean = ConfidenceIntervalCalculator.mean(data);
        double stddev = data.length == 1 ? 0.0
                : ConfidenceIntervalCalculator.sampleStddev(data, mean);

        double[] sorted = data.clone();
        Arrays.sort(sorted);
        double median = percentileLinear(sorted, 0.5);
        double p10 = percentileLinear(sorted, 0.1);
        double p90 = percentileLinear(sorted, 0.9);

        CiBounds ci = ciCalculator.compute(data);
        return new MetricStat(mean, stddev, median, p10, p90,
                ci.getLower(), ci.getUpper(), ci.getMethod());
    }

    /**
     * R type 7 / numpy default linear interpolation。
     *
     * <p>{@code i = q * (N - 1)};{@code lo = floor(i)};{@code hi = ceil(i)};
     * {@code value = sorted[lo] + (i - lo) * (sorted[hi] - sorted[lo])}。N=1 直接返回 sorted[0]。</p>
     */
    static double percentileLinear(double[] sorted, double q) {
        int n = sorted.length;
        if (n == 1) {
            return sorted[0];
        }
        double idx = q * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = idx - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }
}
