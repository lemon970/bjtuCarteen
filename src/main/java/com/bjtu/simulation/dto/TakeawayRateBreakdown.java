package com.bjtu.simulation.dto;

/**
 * 打包率三段分解 — 按行为路径拆开,辅助理解"理论 vs 实际"的差距来源。
 *
 * 三段路径(均按到达学生数 arrivedCount 归一):
 * - initialIntentRate:学生到达时即决定打包(packProbability × weatherFactor 直接 sample)
 * - dynamicFlipRate:服务完成后基于压力翻转(weatherDrivenTakeawayCount 近似)
 * - noSeatForcedRate:堂食学生因无座被迫打包
 *
 * theoreticalRate 是纯意图期望;observedRate = takeawayCount / servedCount。
 */
public class TakeawayRateBreakdown {
    private final double initialIntentRate;
    private final double dynamicFlipRate;
    private final double noSeatForcedRate;
    private final double observedRate;
    private final double theoreticalRate;

    public TakeawayRateBreakdown(double initialIntentRate,
                                 double dynamicFlipRate,
                                 double noSeatForcedRate,
                                 double observedRate,
                                 double theoreticalRate) {
        this.initialIntentRate = round3(initialIntentRate);
        this.dynamicFlipRate = round3(dynamicFlipRate);
        this.noSeatForcedRate = round3(noSeatForcedRate);
        this.observedRate = round3(observedRate);
        this.theoreticalRate = round3(theoreticalRate);
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public double getInitialIntentRate() {
        return initialIntentRate;
    }

    public double getDynamicFlipRate() {
        return dynamicFlipRate;
    }

    public double getNoSeatForcedRate() {
        return noSeatForcedRate;
    }

    public double getObservedRate() {
        return observedRate;
    }

    public double getTheoreticalRate() {
        return theoreticalRate;
    }
}
