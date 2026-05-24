package com.bjtu.simulation.dto;

/**
 * RFC-011 §A:基于 Maister 8 命题中可量化 4 项的"等待体验代理"指标。
 *
 * <p><strong>代理 / proxy / 启发式</strong> — 仅用于同一模型内相对比较(同 baseConfig 不同
 * 参数扫描点之间);<strong>禁止</strong>解释为真实感知等待时间、真实满意度或任何心理量表分数。
 * 系数 0.25 / 0.15 / 0.7 均为文献估计(Maister 1984、Pruyn-Smidts 1998),未经人类受试者
 * 校准。如需经心理学量表(NASA-TLX、Servqual)校准的"真实感知等待",必须独立 RFC + 真实数据。</p>
 *
 * <p>字段映射(详见 {@code service/WaitExperienceProxyCalculator}):</p>
 * <ul>
 *   <li>{@code preProcessWaitShare}: Maister "Pre-process > In-process",
 *       {@code mean_wait / (mean_wait + mean_service)},
 *       {@code mean_service} 用 {@code SimConfig.randomBounds.serviceRange} 中点近似。</li>
 *   <li>{@code waitUncertaintyScore}: Maister "Uncertain > Known",
 *       per-windowId 桶内 wait 的 CV(stddev/mean),桶间按 partySize 加权平均。</li>
 *   <li>{@code anxietyPressureIndex}: Maister "Anxiety amplifies",
 *       {@code mean(max(0, queueLengthAtJoin / queueLimit - 0.7))},party 加权。</li>
 *   <li>{@code soloAdjustedWaitMinutes}: Maister "Solo > Group",
 *       {@code mean_wait * (1 + 0.15 * solo_share)};**字段名直接对应公式输出语义** —
 *       它是"按 solo 比例放大后的平均等待分钟数"(单位:分钟),不是放大倍率本身。</li>
 *   <li>{@code waitExperienceProxyIndex}:
 *       {@code 0.25*pre + 0.25*clip(unc,0,1) + 0.25*clip(anx,0,1) + 0.25*solo_adj/(solo_adj+1)};
 *       **启发式 blend,仅用于同一模型内相对比较**。</li>
 * </ul>
 *
 * <p>样本不足(party-weighted 总样本数 &lt; 50)时,{@code SimulationRunService} 把整对
 * sub-DTO 写为 null,通过 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略,而不是把这里的
 * 字段填 0。</p>
 */
public class WaitExperienceProxyMetrics {

    private final double preProcessWaitShare;
    private final double waitUncertaintyScore;
    private final double anxietyPressureIndex;
    private final double soloAdjustedWaitMinutes;
    private final double waitExperienceProxyIndex;
    private final long sampleCount;

    public WaitExperienceProxyMetrics(double preProcessWaitShare,
                                      double waitUncertaintyScore,
                                      double anxietyPressureIndex,
                                      double soloAdjustedWaitMinutes,
                                      double waitExperienceProxyIndex,
                                      long sampleCount) {
        this.preProcessWaitShare = round3(preProcessWaitShare);
        this.waitUncertaintyScore = round3(waitUncertaintyScore);
        this.anxietyPressureIndex = round3(anxietyPressureIndex);
        this.soloAdjustedWaitMinutes = round3(soloAdjustedWaitMinutes);
        this.waitExperienceProxyIndex = round3(waitExperienceProxyIndex);
        this.sampleCount = Math.max(0L, sampleCount);
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public double getPreProcessWaitShare() {
        return preProcessWaitShare;
    }

    public double getWaitUncertaintyScore() {
        return waitUncertaintyScore;
    }

    public double getAnxietyPressureIndex() {
        return anxietyPressureIndex;
    }

    public double getSoloAdjustedWaitMinutes() {
        return soloAdjustedWaitMinutes;
    }

    public double getWaitExperienceProxyIndex() {
        return waitExperienceProxyIndex;
    }

    public long getSampleCount() {
        return sampleCount;
    }
}
