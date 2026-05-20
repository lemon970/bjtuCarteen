package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.WaitTimeBucket;
import com.bjtu.simulation.dto.WaitTimeInsight;
import com.bjtu.simulation.dto.WaitTimeMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.springframework.stereotype.Service;

@Service
public class WaitTimeMetricsCalculator {
    private static final double LONG_WAIT_THRESHOLD_MINUTES = 10.0;
    private static final double ZERO_WAIT_THRESHOLD_MINUTES = 0.05;

    public WaitTimeMetrics build(List<WaitTimeSample> samples,
                                 int maxTotalQueueSize,
                                 double seatUtilizationRate) {
        if (samples == null || samples.isEmpty()) {
            return WaitTimeMetrics.empty();
        }

        List<Double> allValues = weightedValues(samples);
        List<WaitTimeSample> steadySamples = samples.stream()
                .filter(sample -> sample.getPhase() == WaitTimeSample.Phase.STEADY)
                .toList();
        List<Double> steadyValues = weightedValues(steadySamples.isEmpty() ? samples : steadySamples);

        int allCount = allValues.size();
        int steadyCount = steadyValues.size();
        int edgeCount = Math.max(0, allCount - weightedCount(steadySamples));

        double rawAvg = SimulationMath.round3(SimulationMath.mean(allValues));
        double steadyAvg = SimulationMath.round3(SimulationMath.mean(steadyValues));
        double typical = SimulationMath.round3(SimulationMath.trimmedMean(steadyValues, 0.10));
        double median = SimulationMath.round3(SimulationMath.percentile(steadyValues, 0.50));
        double p75 = SimulationMath.round3(SimulationMath.percentile(steadyValues, 0.75));
        double p90 = SimulationMath.round3(SimulationMath.percentile(steadyValues, 0.90));
        double longWaitRate = SimulationMath.rate(countAtLeast(allValues, LONG_WAIT_THRESHOLD_MINUTES), allCount);
        double zeroWaitRate = SimulationMath.rate(countAtMost(allValues, ZERO_WAIT_THRESHOLD_MINUTES), allCount);
        double edgeWaitSampleRate = SimulationMath.rate(edgeCount, allCount);

        List<WaitTimeBucket> distribution = buildDistribution(allValues);
        WaitTimeInsight insight = buildInsight(typical, median, p90, longWaitRate, edgeWaitSampleRate, maxTotalQueueSize, seatUtilizationRate);

        return new WaitTimeMetrics(
                rawAvg,
                steadyAvg,
                typical,
                median,
                p75,
                p90,
                longWaitRate,
                zeroWaitRate,
                edgeWaitSampleRate,
                distribution,
                insight);
    }

    private List<Double> weightedValues(List<WaitTimeSample> samples) {
        List<Double> values = new ArrayList<>();
        if (samples == null) {
            return values;
        }
        for (WaitTimeSample sample : samples) {
            int count = Math.max(1, sample.getPartySize());
            for (int i = 0; i < count; i++) {
                values.add(sample.getWaitMinutes());
            }
        }
        return values;
    }

    private int weightedCount(List<WaitTimeSample> samples) {
        int total = 0;
        if (samples == null) {
            return total;
        }
        for (WaitTimeSample sample : samples) {
            total += Math.max(1, sample.getPartySize());
        }
        return total;
    }

    private int countAtLeast(List<Double> values, double threshold) {
        int count = 0;
        for (double value : values) {
            if (value >= threshold) {
                count++;
            }
        }
        return count;
    }

    private int countAtMost(List<Double> values, double threshold) {
        int count = 0;
        for (double value : values) {
            if (value <= threshold) {
                count++;
            }
        }
        return count;
    }

    private List<WaitTimeBucket> buildDistribution(List<Double> values) {
        int[] counts = new int[5];
        for (double value : values) {
            if (value < 2.0) {
                counts[0]++;
            } else if (value < 5.0) {
                counts[1]++;
            } else if (value < 10.0) {
                counts[2]++;
            } else if (value < 15.0) {
                counts[3]++;
            } else {
                counts[4]++;
            }
        }

        int total = values.size();
        return List.of(
                new WaitTimeBucket("0-2", counts[0], SimulationMath.rate(counts[0], total)),
                new WaitTimeBucket("2-5", counts[1], SimulationMath.rate(counts[1], total)),
                new WaitTimeBucket("5-10", counts[2], SimulationMath.rate(counts[2], total)),
                new WaitTimeBucket("10-15", counts[3], SimulationMath.rate(counts[3], total)),
                new WaitTimeBucket("15+", counts[4], SimulationMath.rate(counts[4], total)));
    }

    private WaitTimeInsight buildInsight(double typical,
                                         double median,
                                         double p90,
                                         double longWaitRate,
                                         double edgeWaitSampleRate,
                                         int maxTotalQueueSize,
                                         double seatUtilizationRate) {
        String status;
        String primary;
        if (typical <= 5.0) {
            status = "normal";
            primary = "典型等待处于正常范围";
        } else if (typical <= 10.0) {
            status = "warning";
            primary = "稳态等待时间进入轻度拥堵区间";
        } else if (typical <= 15.0) {
            status = "warning";
            primary = "稳态等待时间进入明显拥堵区间";
        } else {
            status = "critical";
            primary = "稳态等待时间进入严重排队区间";
        }

        List<String> reasons = new ArrayList<>();
        if (p90 - median > 8.0) {
            reasons.add("P90 明显高于 P50，少数学生承担了较长等待。");
        }
        if (edgeWaitSampleRate > 0.25) {
            reasons.add("开头/结尾样本占比较高，全量均值容易被边界阶段拉偏。");
        }
        if (longWaitRate > 0.20) {
            reasons.add("长等待人群比例超过 20%，需要检查窗口服务能力。");
        }
        if (maxTotalQueueSize >= 50 && typical > 5.0) {
            reasons.add("峰值队列较高，等待上升主要与窗口前排队淤积有关。");
        }
        if (seatUtilizationRate >= 0.85 && typical <= 5.0) {
            reasons.add("座位压力较高，但当前等待未明显升高，两者不应混合归因。");
        }
        if (reasons.isEmpty()) {
            reasons.add("当前等待分布较集中，未发现明显异常尾部。");
        }

        String message = "当前典型等待 " + SimulationMath.round3(typical)
                + " 分钟，P90 为 " + SimulationMath.round3(p90)
                + " 分钟。" + reasons.get(0);
        return new WaitTimeInsight(status, primary, reasons, null, message);
    }
}
