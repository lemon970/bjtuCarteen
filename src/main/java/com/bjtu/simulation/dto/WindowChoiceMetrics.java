package com.bjtu.simulation.dto;

/**
 * RFC-009 §9 PREFERENCE_AWARE 报告专属指标。
 *
 * <p>仅在 {@code queueChoiceModel == PREFERENCE_AWARE} 时由 {@code SimulationRunService} 写入
 * {@link SimulationSummary#getWindowChoiceMetrics()};STATIC_SPLIT 报告通过
 * {@code @JsonInclude(NON_NULL)} 完全省略 {@code window_choice_metrics} 顶级字段,
 * 保证默认报告外观字节级稳定(详见 RFC §11 T7 / §10 验收清单)。</p>
 *
 * <p>Share 类指标的分母统一锁定**普通窗口集合**(POPULAR + NORMAL + COLD),
 * 打包窗口不计入分母(RFC §9.2 守恒约束)。</p>
 */
public class WindowChoiceMetrics {

    private final String queueChoiceModel;

    private final int popularWindowCount;
    private final int normalWindowCount;
    private final int coldWindowCount;
    private final int takeawayWindowCount;

    private final double popularPreferenceShare;
    private final double normalPreferenceShare;
    private final double coldPreferenceShare;

    private final double popularServedShare;
    private final double normalServedShare;
    private final double coldServedShare;

    private final double popularAvgWaitMinutes;
    private final double normalAvgWaitMinutes;
    private final double coldAvgWaitMinutes;

    private final int maxWindowQueueGap;
    private final double windowServedCountCv;

    public WindowChoiceMetrics(String queueChoiceModel,
                               int popularWindowCount,
                               int normalWindowCount,
                               int coldWindowCount,
                               int takeawayWindowCount,
                               double popularPreferenceShare,
                               double normalPreferenceShare,
                               double coldPreferenceShare,
                               double popularServedShare,
                               double normalServedShare,
                               double coldServedShare,
                               double popularAvgWaitMinutes,
                               double normalAvgWaitMinutes,
                               double coldAvgWaitMinutes,
                               int maxWindowQueueGap,
                               double windowServedCountCv) {
        this.queueChoiceModel = queueChoiceModel;
        this.popularWindowCount = popularWindowCount;
        this.normalWindowCount = normalWindowCount;
        this.coldWindowCount = coldWindowCount;
        this.takeawayWindowCount = takeawayWindowCount;
        this.popularPreferenceShare = round3(popularPreferenceShare);
        this.normalPreferenceShare = round3(normalPreferenceShare);
        this.coldPreferenceShare = round3(coldPreferenceShare);
        this.popularServedShare = round3(popularServedShare);
        this.normalServedShare = round3(normalServedShare);
        this.coldServedShare = round3(coldServedShare);
        this.popularAvgWaitMinutes = round3(popularAvgWaitMinutes);
        this.normalAvgWaitMinutes = round3(normalAvgWaitMinutes);
        this.coldAvgWaitMinutes = round3(coldAvgWaitMinutes);
        this.maxWindowQueueGap = Math.max(0, maxWindowQueueGap);
        this.windowServedCountCv = round3(windowServedCountCv);
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public String getQueueChoiceModel() {
        return queueChoiceModel;
    }

    public int getPopularWindowCount() {
        return popularWindowCount;
    }

    public int getNormalWindowCount() {
        return normalWindowCount;
    }

    public int getColdWindowCount() {
        return coldWindowCount;
    }

    public int getTakeawayWindowCount() {
        return takeawayWindowCount;
    }

    public double getPopularPreferenceShare() {
        return popularPreferenceShare;
    }

    public double getNormalPreferenceShare() {
        return normalPreferenceShare;
    }

    public double getColdPreferenceShare() {
        return coldPreferenceShare;
    }

    public double getPopularServedShare() {
        return popularServedShare;
    }

    public double getNormalServedShare() {
        return normalServedShare;
    }

    public double getColdServedShare() {
        return coldServedShare;
    }

    public double getPopularAvgWaitMinutes() {
        return popularAvgWaitMinutes;
    }

    public double getNormalAvgWaitMinutes() {
        return normalAvgWaitMinutes;
    }

    public double getColdAvgWaitMinutes() {
        return coldAvgWaitMinutes;
    }

    public int getMaxWindowQueueGap() {
        return maxWindowQueueGap;
    }

    public double getWindowServedCountCv() {
        return windowServedCountCv;
    }
}
