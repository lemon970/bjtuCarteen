package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bjtu.simulation.dto.CiBounds;

import org.junit.jupiter.api.Test;

/**
 * RFC-010B 验收:T-10B-CI-1..3。
 *
 * <p>校验 t-interval 公式正确性 + t 表常量 spot check + N=1 退化路径。</p>
 */
class ConfidenceIntervalCalculatorTest {

    private final ConfidenceIntervalCalculator ci = new ConfidenceIntervalCalculator();

    // ---- T-10B-CI-1 ----

    @Test
    void ci1_n5FormulaMatchesTextbook() {
        // [1,2,3,4,5]:mean=3, stddev=√(10/4)=√2.5, se=√2.5/√5
        // df=4 → t=2.776
        double[] data = {1.0, 2.0, 3.0, 4.0, 5.0};
        CiBounds bounds = ci.compute(data);

        double mean = 3.0;
        double stddev = Math.sqrt(2.5);
        double se = stddev / Math.sqrt(5);
        double t = 2.776;
        double expectedLower = mean - t * se;
        double expectedUpper = mean + t * se;

        assertEquals(expectedLower, bounds.getLower(), 1e-9);
        assertEquals(expectedUpper, bounds.getUpper(), 1e-9);
        assertEquals("t", bounds.getMethod());
    }

    // ---- T-10B-CI-2 ----

    @Test
    void ci2_tTableSpotCheckFiveDfs() {
        // df=1, df=4, df=9, df=14, df=29 → 12.706 / 2.776 / 2.262 / 2.145 / 2.045
        assertEquals(12.706, ConfidenceIntervalCalculator.tCritical(2), 0.0,
                "df=1 (N=2) → t 表期望 12.706");
        assertEquals(2.776, ConfidenceIntervalCalculator.tCritical(5), 0.0,
                "df=4 (N=5) → t 表期望 2.776");
        assertEquals(2.262, ConfidenceIntervalCalculator.tCritical(10), 0.0,
                "df=9 (N=10) → t 表期望 2.262");
        assertEquals(2.145, ConfidenceIntervalCalculator.tCritical(15), 0.0,
                "df=14 (N=15) → t 表期望 2.145");
        assertEquals(2.045, ConfidenceIntervalCalculator.tCritical(30), 0.0,
                "df=29 (N=30) → t 表期望 2.045");
        // N>30 退化为 1.96
        assertEquals(1.96, ConfidenceIntervalCalculator.tCritical(31), 0.0,
                "N=31 → 退化为 1.96");
        assertEquals(1.96, ConfidenceIntervalCalculator.tCritical(100), 0.0,
                "N=100 → 退化为 1.96");
    }

    // ---- T-10B-CI-3 ----

    @Test
    void ci3_n1Degenerate() {
        CiBounds bounds = ci.compute(new double[]{42.0});
        assertEquals(42.0, bounds.getLower(), 0.0);
        assertEquals(42.0, bounds.getUpper(), 0.0);
        assertEquals("t", bounds.getMethod());
    }

    @Test
    void ciEmptyOrNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ci.compute(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> ci.compute(null));
    }
}
