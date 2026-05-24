package com.bjtu.simulation.dto;

/**
 * RFC-010B:CI 计算器的内部返回值,(lower, upper, method) 三元组。
 *
 * <p>不参与 {@link BatchRunReport} JSON 序列化(仅为 calculator 内部传值);
 * 落到 {@link MetricStat} 时拆开成 ci95Lower / ci95Upper / ciMethod。</p>
 */
public class CiBounds {

    private final double lower;
    private final double upper;
    private final String method;

    public CiBounds(double lower, double upper, String method) {
        this.lower = lower;
        this.upper = upper;
        this.method = method;
    }

    public double getLower() {
        return lower;
    }

    public double getUpper() {
        return upper;
    }

    public String getMethod() {
        return method;
    }
}
