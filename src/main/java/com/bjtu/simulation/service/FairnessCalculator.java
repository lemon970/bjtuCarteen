package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bjtu.simulation.dto.FairnessMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.springframework.stereotype.Service;

/**
 * RFC-011 §B:从 wait samples + windowServedCounts + windowTypes 派生 3 个 fairness 字段。
 *
 * <p>样本不足(party-weighted 总样本数 &lt; 50)时返回 null,由 SimulationRunService 通过
 * {@code @JsonInclude(NON_NULL)} 在 JSON 中省略整对 sub-DTO。</p>
 *
 * <p>{@code nonTakeawayWindowLoadCv} 显式不读 {@code WindowRole},直接对
 * {@code windowTypes != "TAKEAWAY"} 的所有非打包窗口算 CV — 与 PR-9D
 * {@code windowServedCountCv}(分母锁定 POPULAR + NORMAL + COLD)是不同口径。</p>
 */
@Service
public class FairnessCalculator {

    /** party-weighted 总样本下限,低于此值返回 null。 */
    static final long MIN_WEIGHTED_SAMPLES = 50L;

    /** cross_role_fairness 单一类别的 weighted 样本下限。 */
    static final int MIN_CATEGORY_WEIGHT = 5;

    public FairnessMetrics build(List<WaitTimeSample> samples,
                                 List<Integer> windowServedCounts,
                                 List<String> windowTypes) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        long weightedTotal = 0L;
        for (WaitTimeSample s : samples) {
            weightedTotal += Math.max(1, s.getPartySize());
        }
        if (weightedTotal < MIN_WEIGHTED_SAMPLES) {
            return null;
        }

        double waitGini = computeWaitGini(samples);
        double loadCv = computeNonTakeawayWindowLoadCv(windowServedCounts, windowTypes);
        double crossRole = computeCrossRoleFairness(samples);

        return new FairnessMetrics(waitGini, loadCv, crossRole, weightedTotal);
    }

    /**
     * 标准 Gini:对 party-weighted wait minutes 升序排序后,
     * G = (2 * Σ i*y_i) / (n * Σ y_i) - (n+1)/n,i 从 1 计。Σy=0 退化为 0。
     */
    static double computeWaitGini(List<WaitTimeSample> samples) {
        List<Double> values = new ArrayList<>();
        for (WaitTimeSample s : samples) {
            int w = Math.max(1, s.getPartySize());
            for (int i = 0; i < w; i++) {
                values.add(s.getWaitMinutes());
            }
        }
        if (values.isEmpty()) {
            return 0.0;
        }
        Collections.sort(values);
        int n = values.size();
        double sumY = 0.0;
        double sumIY = 0.0;
        for (int i = 0; i < n; i++) {
            double y = values.get(i);
            sumY += y;
            sumIY += (i + 1) * y;
        }
        if (sumY <= 0.0) {
            return 0.0;
        }
        double gini = (2.0 * sumIY) / (n * sumY) - (n + 1.0) / n;
        if (gini < 0.0) {
            return 0.0;
        }
        if (gini > 1.0) {
            return 1.0;
        }
        return gini;
    }

    /**
     * stddev(non-TAKEAWAY servedCounts) / mean(...);非打包窗口数 &lt; 2 或 mean=0 返回 0。
     * 总体方差(N 为分母,与 PR-9D windowServedCountCv 同口径)。
     */
    static double computeNonTakeawayWindowLoadCv(List<Integer> windowServedCounts,
                                                 List<String> windowTypes) {
        if (windowServedCounts == null || windowTypes == null
                || windowServedCounts.isEmpty() || windowTypes.isEmpty()) {
            return 0.0;
        }
        List<Integer> nonTakeaway = new ArrayList<>();
        int n = Math.min(windowServedCounts.size(), windowTypes.size());
        for (int i = 0; i < n; i++) {
            String t = windowTypes.get(i);
            if (t == null || !"TAKEAWAY".equalsIgnoreCase(t.trim())) {
                nonTakeaway.add(Math.max(0, windowServedCounts.get(i)));
            }
        }
        if (nonTakeaway.size() < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (int v : nonTakeaway) {
            mean += v;
        }
        mean /= nonTakeaway.size();
        if (mean <= 0.0) {
            return 0.0;
        }
        double variance = 0.0;
        for (int v : nonTakeaway) {
            double diff = v - mean;
            variance += diff * diff;
        }
        variance /= nonTakeaway.size();
        return Math.sqrt(variance) / mean;
    }

    /**
     * cross_role_fairness:三类(solo dine-in / group dine-in / takeaway)party-weighted
     * median wait 的 max - min。weighted 样本数 &lt; 5 的类别跳过;可用类别 &lt; 2 时返回 0。
     */
    static double computeCrossRoleFairness(List<WaitTimeSample> samples) {
        List<Double> solo = new ArrayList<>();
        List<Double> group = new ArrayList<>();
        List<Double> takeaway = new ArrayList<>();

        for (WaitTimeSample s : samples) {
            int w = Math.max(1, s.getPartySize());
            String t = s.getWindowType();
            boolean isTakeaway = t != null && "TAKEAWAY".equalsIgnoreCase(t.trim());
            List<Double> bucket;
            if (isTakeaway) {
                bucket = takeaway;
            } else if (s.getPartySize() == 1) {
                bucket = solo;
            } else {
                bucket = group;
            }
            for (int i = 0; i < w; i++) {
                bucket.add(s.getWaitMinutes());
            }
        }

        List<Double> medians = new ArrayList<>();
        if (solo.size() >= MIN_CATEGORY_WEIGHT) {
            medians.add(median(solo));
        }
        if (group.size() >= MIN_CATEGORY_WEIGHT) {
            medians.add(median(group));
        }
        if (takeaway.size() >= MIN_CATEGORY_WEIGHT) {
            medians.add(median(takeaway));
        }
        if (medians.size() < 2) {
            return 0.0;
        }

        double max = medians.get(0);
        double min = medians.get(0);
        for (double v : medians) {
            if (v > max) max = v;
            if (v < min) min = v;
        }
        return max - min;
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int n = copy.size();
        if ((n & 1) == 1) {
            return copy.get(n / 2);
        }
        return (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }
}
