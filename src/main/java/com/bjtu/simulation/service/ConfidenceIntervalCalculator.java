package com.bjtu.simulation.service;

import com.bjtu.simulation.dto.CiBounds;

import org.springframework.stereotype.Service;

/**
 * RFC-010B:Student t 95% 双边置信区间计算器(t-interval only)。
 *
 * <p>不引入 Apache Commons Math / Hipparchus 等新依赖。t 临界值通过硬编码常量数组提供
 * (df=1..29 双边 95%,α=0.025);N>30 退化为正态近似 z=1.96。</p>
 *
 * <p>t 表来源:任何标准统计教材均给出相同表(例:Casella &amp; Berger《Statistical Inference》
 * 2/e Appendix Table 2;Walpole 等《Probability &amp; Statistics for Engineers &amp; Scientists》
 * 9/e Table A.4)。</p>
 *
 * <p>本轮 ciMethod 字段固定为 "t",Bootstrap 移到 Future Work。</p>
 */
@Service
public class ConfidenceIntervalCalculator {

    /** Student t 双边 95%(α=0.025),下标 [df-1] = T_TABLE[i]。i=0 → df=1,i=28 → df=29。 */
    static final double[] T_TABLE_DF_1_TO_29 = new double[] {
            12.706, // df=1
            4.303,  // df=2
            3.182,  // df=3
            2.776,  // df=4
            2.571,  // df=5
            2.447,  // df=6
            2.365,  // df=7
            2.306,  // df=8
            2.262,  // df=9
            2.228,  // df=10
            2.201,  // df=11
            2.179,  // df=12
            2.160,  // df=13
            2.145,  // df=14
            2.131,  // df=15
            2.120,  // df=16
            2.110,  // df=17
            2.101,  // df=18
            2.093,  // df=19
            2.086,  // df=20
            2.080,  // df=21
            2.074,  // df=22
            2.069,  // df=23
            2.064,  // df=24
            2.060,  // df=25
            2.056,  // df=26
            2.052,  // df=27
            2.048,  // df=28
            2.045   // df=29
    };

    /** N>30 退化为正态近似。 */
    static final double Z_NORMAL_95 = 1.96;

    /** 本轮恒为 "t"。Bootstrap 落地后再扩枚举。 */
    public static final String METHOD_T = "t";

    public CiBounds compute(double[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must be non-empty");
        }
        int n = samples.length;
        double mean = mean(samples);
        if (n == 1) {
            return new CiBounds(mean, mean, METHOD_T);
        }
        double stddev = sampleStddev(samples, mean);
        double se = stddev / Math.sqrt(n);
        double t = tCritical(n);
        return new CiBounds(mean - t * se, mean + t * se, METHOD_T);
    }

    /** N>=2 时:df=N-1,N-1 ∈ [1, 29] 查表;N-1 >= 30 用 1.96。 */
    static double tCritical(int n) {
        int df = n - 1;
        if (df >= 1 && df <= 29) {
            return T_TABLE_DF_1_TO_29[df - 1];
        }
        return Z_NORMAL_95;
    }

    static double mean(double[] data) {
        double sum = 0.0;
        for (double v : data) {
            sum += v;
        }
        return sum / data.length;
    }

    /** 样本标准差,N-1 分母。N=1 由调用方提前拦截不会进这里。 */
    static double sampleStddev(double[] data, double mean) {
        double ss = 0.0;
        for (double v : data) {
            double d = v - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (data.length - 1));
    }
}
