package com.bjtu.simulation.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WaitExperienceProxyMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.springframework.stereotype.Service;

/**
 * RFC-011 §A:从 wait samples + SimConfig 计算 5 个 wait_experience_proxy 字段。
 *
 * <p><strong>启发式代理 / proxy</strong> — 仅用于同一模型内相对比较;不可解释为真实感知
 * 等待时间或心理量表分数。系数 0.15(solo amplifier,Pruyn-Smidts 1998)、0.7(anxiety
 * threshold,Maister)、0.25(index 等权)均为文献估计。</p>
 *
 * <p>样本不足(party-weighted 总样本数 &lt; 50)时返回 null,由 SimulationRunService 通过
 * {@code @JsonInclude(NON_NULL)} 在 JSON 中省略整对 sub-DTO。</p>
 */
@Service
public class WaitExperienceProxyCalculator {

    /** party-weighted 总样本下限,低于此值返回 null。RFC-011 v2 钉死 50。 */
    static final long MIN_WEIGHTED_SAMPLES = 50L;

    /** Solo amplifier 系数(Pruyn-Smidts 1998 估计)。 */
    static final double SOLO_AMPLIFIER_COEFFICIENT = 0.15;

    /** Anxiety threshold,队长压力超此阈值后开始放大体验(Maister)。 */
    static final double ANXIETY_THRESHOLD = 0.7;

    /** {@code SimConfig.randomBounds.serviceRange} 缺失时使用的 baseline(秒)。 */
    private static final double DEFAULT_SERVICE_MIN_SECONDS = 45.0;
    private static final double DEFAULT_SERVICE_MAX_SECONDS = 180.0;

    public WaitExperienceProxyMetrics build(List<WaitTimeSample> samples, SimConfig config) {
        if (samples == null || samples.isEmpty() || config == null) {
            return null;
        }
        long weightedTotal = 0L;
        for (WaitTimeSample s : samples) {
            weightedTotal += Math.max(1, s.getPartySize());
        }
        if (weightedTotal < MIN_WEIGHTED_SAMPLES) {
            return null;
        }

        int queueLimit = Math.max(1, config.getQueueLimit());

        double weightedSumWait = 0.0;
        long soloWeighted = 0L;
        double anxietySum = 0.0;

        // per-window 桶,数组结构:[Σ wait*w, Σ wait^2*w, Σ w]
        Map<Integer, double[]> buckets = new HashMap<>();

        for (WaitTimeSample s : samples) {
            int w = Math.max(1, s.getPartySize());
            double wait = s.getWaitMinutes();
            weightedSumWait += wait * w;

            if (s.getPartySize() == 1) {
                soloWeighted += w;
            }

            double pressure = (double) s.getQueueLengthAtJoin() / queueLimit;
            double over = Math.max(0.0, pressure - ANXIETY_THRESHOLD);
            anxietySum += over * w;

            double[] agg = buckets.computeIfAbsent(s.getWindowId(), k -> new double[3]);
            agg[0] += wait * w;
            agg[1] += wait * wait * w;
            agg[2] += w;
        }

        double meanWaitMinutes = weightedSumWait / weightedTotal;
        double anxiety = anxietySum / weightedTotal;
        double soloShare = (double) soloWeighted / weightedTotal;
        double soloAdjusted = meanWaitMinutes * (1.0 + SOLO_AMPLIFIER_COEFFICIENT * soloShare);

        double meanServiceMinutes = resolveMeanServiceMinutes(config);
        double preDenom = meanWaitMinutes + meanServiceMinutes;
        double preProcessShare = preDenom > 0.0 ? meanWaitMinutes / preDenom : 0.0;

        // 桶间按 partySize 加权(权重 = 桶内 weighted count)取 CV 平均;mean=0 桶 cv=0。
        double weightedCvSum = 0.0;
        double weightedCvDenom = 0.0;
        for (double[] agg : buckets.values()) {
            double bucketWeight = agg[2];
            if (bucketWeight <= 0.0) {
                continue;
            }
            double mean = agg[0] / bucketWeight;
            double meanSq = agg[1] / bucketWeight;
            double variance = Math.max(0.0, meanSq - mean * mean);
            double stddev = Math.sqrt(variance);
            double cv = mean > 0.0 ? stddev / mean : 0.0;
            weightedCvSum += cv * bucketWeight;
            weightedCvDenom += bucketWeight;
        }
        double uncertainty = weightedCvDenom > 0.0 ? weightedCvSum / weightedCvDenom : 0.0;

        double clipUnc = clamp(uncertainty, 0.0, 1.0);
        double clipAnx = clamp(anxiety, 0.0, 1.0);
        double soloComponent = soloAdjusted / (soloAdjusted + 1.0);
        double index = 0.25 * preProcessShare
                + 0.25 * clipUnc
                + 0.25 * clipAnx
                + 0.25 * soloComponent;

        return new WaitExperienceProxyMetrics(
                preProcessShare,
                uncertainty,
                anxiety,
                soloAdjusted,
                index,
                weightedTotal);
    }

    private double resolveMeanServiceMinutes(SimConfig config) {
        SimConfig.RandomBounds bounds = config.getRandomBounds();
        if (bounds == null
                || bounds.getServiceRange() == null
                || bounds.getServiceRange().size() < 2) {
            return ((DEFAULT_SERVICE_MIN_SECONDS + DEFAULT_SERVICE_MAX_SECONDS) / 2.0) / 60.0;
        }
        int min = bounds.getServiceRange().get(0);
        int max = bounds.getServiceRange().get(1);
        return ((min + max) / 2.0) / 60.0;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }
}
