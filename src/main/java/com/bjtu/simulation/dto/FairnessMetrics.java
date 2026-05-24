package com.bjtu.simulation.dto;

/**
 * RFC-011 §B:公平性指标(派生,纯后处理)。
 *
 * <ul>
 *   <li>{@code waitGini}:对 party-weighted wait minutes 排序后标准 Gini 系数,
 *       {@code (2 * Σ i*y_i) / (n * Σ y_i) - (n+1)/n};所有 wait=0 时 (Σy=0) 守为 0。</li>
 *   <li>{@code nonTakeawayWindowLoadCv}:
 *       {@code stddev(servedCounts of windowTypes != "TAKEAWAY") / mean(...)};
 *       <strong>显式不读 {@code WindowRole}</strong>,直接对 {@code windowTypes != "TAKEAWAY"}
 *       的所有非打包窗口算 CV。字段名直接表达"非打包窗口集合",避免与
 *       {@code WindowRole.NORMAL}(POPULAR / NORMAL / COLD 三分中的 NORMAL 角色)混淆。</li>
 *   <li>{@code crossRoleFairness}:三类(solo dine-in / group dine-in / takeaway)
 *       party-weighted median wait 的 max - min。
 *       分类规则:<br>
 *       • solo_dine_in: {@code partySize == 1 && windowType != "TAKEAWAY"}<br>
 *       • group_dine_in: {@code partySize > 1 && windowType != "TAKEAWAY"}<br>
 *       • takeaway_window: {@code windowType == "TAKEAWAY"}<br>
 *       weighted sample count &lt; 5 的类别跳过;可用类别 &lt; 2 时返回 0。</li>
 * </ul>
 *
 * <p>样本不足(party-weighted 总样本数 &lt; 50)时,{@code SimulationRunService} 把整对
 * sub-DTO 写为 null,通过 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略。</p>
 */
public class FairnessMetrics {

    private final double waitGini;
    private final double nonTakeawayWindowLoadCv;
    private final double crossRoleFairness;
    private final long sampleCount;

    public FairnessMetrics(double waitGini,
                           double nonTakeawayWindowLoadCv,
                           double crossRoleFairness,
                           long sampleCount) {
        this.waitGini = round3(waitGini);
        this.nonTakeawayWindowLoadCv = round3(nonTakeawayWindowLoadCv);
        this.crossRoleFairness = round3(crossRoleFairness);
        this.sampleCount = Math.max(0L, sampleCount);
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public double getWaitGini() {
        return waitGini;
    }

    public double getNonTakeawayWindowLoadCv() {
        return nonTakeawayWindowLoadCv;
    }

    public double getCrossRoleFairness() {
        return crossRoleFairness;
    }

    public long getSampleCount() {
        return sampleCount;
    }
}
