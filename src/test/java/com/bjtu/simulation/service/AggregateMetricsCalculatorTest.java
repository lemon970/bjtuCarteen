package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.bjtu.simulation.dto.AggregateMetrics;
import com.bjtu.simulation.dto.MetricStat;
import com.bjtu.simulation.dto.PerSeedMetric;

import org.junit.jupiter.api.Test;

/**
 * RFC-010B 验收:T-10B-1..9。
 *
 * <p>用合成 {@link PerSeedMetric} 列表(已知均值 / 方差 / percentile 期望值)直接喂给 calculator,
 * 不依赖 simulator,纯数学验证。STATIC_SPLIT 与 PREFERENCE_AWARE 的 null/非 null 分裂走 T-10B-8/9。</p>
 */
class AggregateMetricsCalculatorTest {

    private final AggregateMetricsCalculator calculator = new AggregateMetricsCalculator();

    /** 仅设 typicalWaitTimeMinutes 一个核心字段为 value,其余字段填占位值。3 PR-9D 字段 = pr9d。 */
    private PerSeedMetric synth(long seed, double value, Double pr9d) {
        return new PerSeedMetric(
                seed,
                "synth-" + seed,
                /* arrivedCount    */ (int) Math.round(value),
                /* servedCount     */ (int) Math.round(value),
                /* typicalWait     */ value,
                /* medianWait      */ value,
                /* p90Wait         */ value,
                /* seatUtil        */ 0.0,
                /* takeawayRate    */ 0.0,
                /* maxQueue        */ (int) Math.round(value),
                pr9d, pr9d, pr9d);
    }

    private List<PerSeedMetric> dataset(double[] values, Double pr9d) {
        List<PerSeedMetric> out = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            out.add(synth(i, values[i], pr9d));
        }
        return out;
    }

    private double[] range(int from, int toInclusive) {
        double[] data = new double[toInclusive - from + 1];
        for (int i = 0; i < data.length; i++) {
            data[i] = from + i;
        }
        return data;
    }

    // ---- T-10B-1 ----

    @Test
    void t10b1_n5Formula() {
        AggregateMetrics agg = calculator.aggregate(dataset(new double[]{1, 2, 3, 4, 5}, null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        double mean = 3.0;
        double stddev = Math.sqrt(2.5);
        double se = stddev / Math.sqrt(5);
        double t = 2.776;

        assertEquals(5, agg.getSampleCount());
        assertEquals(mean, s.getMean(), 1e-9);
        assertEquals(stddev, s.getStddev(), 1e-9);
        assertEquals(3.0, s.getMedian(), 1e-9);
        assertEquals(1.4, s.getP10(), 1e-9);
        assertEquals(4.6, s.getP90(), 1e-9);
        assertEquals(mean - t * se, s.getCi95Lower(), 1e-9);
        assertEquals(mean + t * se, s.getCi95Upper(), 1e-9);
        assertEquals("t", s.getCiMethod());
    }

    // ---- T-10B-2 ----

    @Test
    void t10b2_n15Formula() {
        AggregateMetrics agg = calculator.aggregate(dataset(range(1, 15), null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        double mean = 8.0;
        double stddev = Math.sqrt(280.0 / 14.0); // sample variance for 1..15 = 20
        double se = stddev / Math.sqrt(15);
        double t = 2.145; // df=14

        assertEquals(mean, s.getMean(), 1e-9);
        assertEquals(stddev, s.getStddev(), 1e-9);
        assertEquals(8.0, s.getMedian(), 1e-9);
        // p10: i=0.1*14=1.4 → sorted[1]+0.4*(sorted[2]-sorted[1]) = 2+0.4 = 2.4
        assertEquals(2.4, s.getP10(), 1e-9);
        // p90: i=0.9*14=12.6 → sorted[12]+0.6*(sorted[13]-sorted[12]) = 13+0.6 = 13.6
        assertEquals(13.6, s.getP90(), 1e-9);
        assertEquals(mean - t * se, s.getCi95Lower(), 1e-9);
        assertEquals(mean + t * se, s.getCi95Upper(), 1e-9);
    }

    // ---- T-10B-3 ----

    @Test
    void t10b3_n30Formula() {
        AggregateMetrics agg = calculator.aggregate(dataset(range(1, 30), null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        // 1..30: sum=465, mean=15.5, sum_sq_dev = sum((i-15.5)^2) for i=1..30 = 2247.5
        // sample variance = 2247.5/29
        double mean = 15.5;
        double stddev = Math.sqrt(2247.5 / 29.0);
        double se = stddev / Math.sqrt(30);
        double t = 2.045; // df=29

        assertEquals(mean, s.getMean(), 1e-9);
        assertEquals(stddev, s.getStddev(), 1e-9);
        assertEquals(mean - t * se, s.getCi95Lower(), 1e-9);
        assertEquals(mean + t * se, s.getCi95Upper(), 1e-9);
    }

    // ---- T-10B-4 ----

    @Test
    void t10b4_nGreaterThan30FallsBackToZNormal() {
        AggregateMetrics agg = calculator.aggregate(dataset(range(1, 50), null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        // 反推 t:width = 2 * t * se ⇒ t = (upper - lower) / (2 * se)
        double mean = s.getMean();
        double stddev = s.getStddev();
        double se = stddev / Math.sqrt(50);
        double tEffective = (s.getCi95Upper() - s.getCi95Lower()) / (2.0 * se);

        assertEquals(1.96, tEffective, 1e-9, "N>30 应退化为 z=1.96");
        assertEquals(mean - 1.96 * se, s.getCi95Lower(), 1e-9);
        assertEquals(mean + 1.96 * se, s.getCi95Upper(), 1e-9);
    }

    // ---- T-10B-5 ----

    @Test
    void t10b5_n1Degenerate() {
        AggregateMetrics agg = calculator.aggregate(dataset(new double[]{42.0}, null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        assertEquals(1, agg.getSampleCount());
        assertEquals(42.0, s.getMean(), 0.0);
        assertEquals(0.0, s.getStddev(), 0.0);
        assertEquals(42.0, s.getMedian(), 0.0);
        assertEquals(42.0, s.getP10(), 0.0);
        assertEquals(42.0, s.getP90(), 0.0);
        assertEquals(42.0, s.getCi95Lower(), 0.0);
        assertEquals(42.0, s.getCi95Upper(), 0.0);
        assertEquals("t", s.getCiMethod());
    }

    // ---- T-10B-6 ----

    @Test
    void t10b6_n2MinimalT() {
        AggregateMetrics agg = calculator.aggregate(dataset(new double[]{10.0, 20.0}, null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();

        // mean=15, stddev=sqrt(50)≈7.071, se=stddev/√2, df=1 → t=12.706
        double mean = 15.0;
        double stddev = Math.sqrt(50.0);
        double se = stddev / Math.sqrt(2);
        double t = 12.706;

        assertEquals(mean, s.getMean(), 1e-9);
        assertEquals(stddev, s.getStddev(), 1e-9);
        assertEquals(mean - t * se, s.getCi95Lower(), 1e-9);
        assertEquals(mean + t * se, s.getCi95Upper(), 1e-9);
    }

    // ---- T-10B-7 ----

    @Test
    void t10b7_percentileLinearMatchesNumpy() {
        // [10,20,30,...,100],N=10。numpy default(R type 7):
        // p10: i = 0.1*9 = 0.9 → lo=0, hi=1, 10 + 0.9*10 = 19
        // median: i = 0.5*9 = 4.5 → lo=4, hi=5, 50 + 0.5*10 = 55
        // p90: i = 0.9*9 = 8.1 → lo=8, hi=9, 90 + 0.1*10 = 91
        AggregateMetrics agg = calculator.aggregate(dataset(
                new double[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100}, null));
        MetricStat s = agg.getTypicalWaitTimeMinutes();
        assertEquals(19.0, s.getP10(), 1e-9);
        assertEquals(55.0, s.getMedian(), 1e-9);
        assertEquals(91.0, s.getP90(), 1e-9);
    }

    // ---- T-10B-8 ----

    @Test
    void t10b8_staticSplitAllNullPr9dYieldsNullStat() {
        AggregateMetrics agg = calculator.aggregate(dataset(new double[]{1, 2, 3}, null));
        assertNull(agg.getPopularServedShare(),
                "STATIC_SPLIT(全 null) 路径下 popularServedShare 必须为 null");
        assertNull(agg.getColdServedShare(),
                "STATIC_SPLIT(全 null) 路径下 coldServedShare 必须为 null");
        assertNull(agg.getWindowServedCountCv(),
                "STATIC_SPLIT(全 null) 路径下 windowServedCountCv 必须为 null");
        // 8 核心字段仍非 null
        assertNotNull(agg.getArrivedCount());
        assertNotNull(agg.getTypicalWaitTimeMinutes());
    }

    @Test
    void t10b8b_preferenceAwareAllNonNullPr9dYieldsStat() {
        AggregateMetrics agg = calculator.aggregate(dataset(new double[]{1, 2, 3}, 0.5));
        assertNotNull(agg.getPopularServedShare());
        assertNotNull(agg.getColdServedShare());
        assertNotNull(agg.getWindowServedCountCv());
        // 全部 0.5 → mean=0.5, stddev=0
        assertEquals(0.5, agg.getPopularServedShare().getMean(), 1e-9);
        assertEquals(0.0, agg.getPopularServedShare().getStddev(), 1e-9);
    }

    // ---- T-10B-9 ----

    @Test
    void t10b9_partialNullFailsFast() {
        // 显式构造混合 null/非 null 列表
        List<PerSeedMetric> mixed = new ArrayList<>();
        mixed.add(synth(0, 1.0, 0.5));   // 非 null
        mixed.add(synth(1, 2.0, null));  // null
        mixed.add(synth(2, 3.0, 0.7));   // 非 null

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> calculator.aggregate(mixed));
        assertTrue(ex.getMessage().contains("inconsistent nullness"),
                () -> "异常 message 必须含 'inconsistent nullness',实际:" + ex.getMessage());
    }

    @Test
    void emptyOrNullMetricsThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> calculator.aggregate(null));
        assertThrows(IllegalArgumentException.class, () -> calculator.aggregate(Arrays.asList()));
    }
}
