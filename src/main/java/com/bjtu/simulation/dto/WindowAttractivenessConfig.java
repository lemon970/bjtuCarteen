package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * RFC-009 §4.1 窗口吸引力配置块。
 *
 * <p>仅当 {@link QueueChoiceModel#PREFERENCE_AWARE} 启用时生效;
 * {@link QueueChoiceModel#STATIC_SPLIT} 默认下整体被忽略。</p>
 *
 * <p>注意:V1 中 attractiveness 仅作为加权抽样权重(§4.2),不进入
 * {@code WindowSelectionPolicy} 选择评分。</p>
 */
public class WindowAttractivenessConfig {

    @JsonAlias("popular_window_ratio")
    @DecimalMin(value = "0.0", message = "popularWindowRatio must be in [0, 1]")
    @DecimalMax(value = "1.0", message = "popularWindowRatio must be in [0, 1]")
    private double popularWindowRatio = 0.25;

    @JsonAlias("cold_window_ratio")
    @DecimalMin(value = "0.0", message = "coldWindowRatio must be in [0, 1]")
    @DecimalMax(value = "1.0", message = "coldWindowRatio must be in [0, 1]")
    private double coldWindowRatio = 0.25;

    @JsonAlias("popular_attractiveness")
    @DecimalMin(value = "0.0", inclusive = false, message = "popularAttractiveness must be > 0")
    @DecimalMax(value = "5.0", message = "popularAttractiveness must be <= 5")
    private double popularAttractiveness = 1.4;

    @JsonAlias("normal_attractiveness")
    @DecimalMin(value = "0.0", inclusive = false, message = "normalAttractiveness must be > 0")
    @DecimalMax(value = "5.0", message = "normalAttractiveness must be <= 5")
    private double normalAttractiveness = 1.0;

    @JsonAlias("cold_attractiveness")
    @DecimalMin(value = "0.0", inclusive = false, message = "coldAttractiveness must be > 0")
    @DecimalMax(value = "5.0", message = "coldAttractiveness must be <= 5")
    private double coldAttractiveness = 0.8;

    public WindowAttractivenessConfig() {
    }

    public double getPopularWindowRatio() {
        return popularWindowRatio;
    }

    public void setPopularWindowRatio(double popularWindowRatio) {
        this.popularWindowRatio = popularWindowRatio;
    }

    public double getColdWindowRatio() {
        return coldWindowRatio;
    }

    public void setColdWindowRatio(double coldWindowRatio) {
        this.coldWindowRatio = coldWindowRatio;
    }

    public double getPopularAttractiveness() {
        return popularAttractiveness;
    }

    public void setPopularAttractiveness(double popularAttractiveness) {
        this.popularAttractiveness = popularAttractiveness;
    }

    public double getNormalAttractiveness() {
        return normalAttractiveness;
    }

    public void setNormalAttractiveness(double normalAttractiveness) {
        this.normalAttractiveness = normalAttractiveness;
    }

    public double getColdAttractiveness() {
        return coldAttractiveness;
    }

    public void setColdAttractiveness(double coldAttractiveness) {
        this.coldAttractiveness = coldAttractiveness;
    }
}
