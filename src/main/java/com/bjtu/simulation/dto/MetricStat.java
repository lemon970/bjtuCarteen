package com.bjtu.simulation.dto;

/**
 * RFC-010B:单个 metric 在 N 个 seed 上的描述性统计 + 95% CI。
 *
 * <p>8 个字段:mean / stddev / median / p10 / p90 / ci95Lower / ci95Upper / ciMethod。
 * stddev 是**样本**标准差(N-1 分母);N=1 时 stddev=0、ci95Lower=ci95Upper=mean。
 * percentile 使用 linear interpolation(R type 7 / numpy default)。
 * 浮点字段保留 {@code double} 全精度,不 round(下游 RFC-010C sensitivity 比 CI 宽度敏感)。</p>
 */
public class MetricStat {

    private final double mean;
    private final double stddev;
    private final double median;
    private final double p10;
    private final double p90;
    private final double ci95Lower;
    private final double ci95Upper;
    private final String ciMethod;

    public MetricStat(double mean,
                      double stddev,
                      double median,
                      double p10,
                      double p90,
                      double ci95Lower,
                      double ci95Upper,
                      String ciMethod) {
        this.mean = mean;
        this.stddev = stddev;
        this.median = median;
        this.p10 = p10;
        this.p90 = p90;
        this.ci95Lower = ci95Lower;
        this.ci95Upper = ci95Upper;
        this.ciMethod = ciMethod;
    }

    public double getMean() {
        return mean;
    }

    public double getStddev() {
        return stddev;
    }

    public double getMedian() {
        return median;
    }

    public double getP10() {
        return p10;
    }

    public double getP90() {
        return p90;
    }

    public double getCi95Lower() {
        return ci95Lower;
    }

    public double getCi95Upper() {
        return ci95Upper;
    }

    public String getCiMethod() {
        return ciMethod;
    }
}
