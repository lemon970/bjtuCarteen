package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * RFC-010C:一条扫描轴。{@code parameter} 是要扫的白名单参数,{@code points} 是 M 个扫描值。
 *
 * <p>每个扫描点被 mutator 注入到 baseConfig 的相应 setter 中(double → setter 类型由 mutator 决定,
 * 整数字段用 {@code Math.round})。本类只承载数据,不做语义校验,语义校验交给
 * {@code SensitivityAnalysisService} 入口和 mutator。</p>
 */
public class ScanAxis {

    private WhitelistedParam parameter;

    @JsonAlias("scan_points")
    private double[] points;

    public ScanAxis() {
    }

    public ScanAxis(WhitelistedParam parameter, double[] points) {
        this.parameter = parameter;
        this.points = points;
    }

    public WhitelistedParam getParameter() {
        return parameter;
    }

    public void setParameter(WhitelistedParam parameter) {
        this.parameter = parameter;
    }

    public double[] getPoints() {
        return points;
    }

    public void setPoints(double[] points) {
        this.points = points;
    }
}
