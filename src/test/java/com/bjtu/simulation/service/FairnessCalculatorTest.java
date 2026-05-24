package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.FairnessMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.junit.jupiter.api.Test;

/**
 * RFC-011 §B:T-11B-1 ~ T-11B-8。
 *
 * <p>验收用合成 {@link WaitTimeSample},不依赖真仿真路径。守:
 * <ul>
 *   <li>Gini 三组手算对照(全相等→0;两极→>0.4;均匀→在 (0, 0.5))</li>
 *   <li>{@code nonTakeawayWindowLoadCv} 直接对 {@code windowTypes != "TAKEAWAY"} 的窗口算 CV,
 *       不读 {@code WindowRole}</li>
 *   <li>{@code crossRoleFairness} 三类分类规则写死;weighted &lt; 5 跳过;可用 &lt; 2 → 0</li>
 *   <li>party-weighted &lt; 50 → null</li>
 *   <li>同输入两次 build 字段全 ==</li>
 * </ul></p>
 */
class FairnessCalculatorTest {

    private final FairnessCalculator calculator = new FairnessCalculator();

    private WaitTimeSample sample(double waitMinutes,
                                  int partySize,
                                  int windowId,
                                  String windowType) {
        long enter = 0L;
        long start = (long) Math.round(waitMinutes * 60.0);
        return new WaitTimeSample(enter, start, partySize, windowId, windowType,
                0, WaitTimeSample.Phase.STEADY);
    }

    private List<Integer> ints(int... vs) {
        List<Integer> list = new ArrayList<>(vs.length);
        for (int v : vs) {
            list.add(v);
        }
        return list;
    }

    private List<String> types(String... vs) {
        return new ArrayList<>(List.of(vs));
    }

    // ---- T-11B-1 ----

    @Test
    void t11b1_giniZeroOnEqual() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(5.0, 1, 0, "NORMAL"));
        }
        FairnessMetrics m = calculator.build(samples, ints(60), types("NORMAL"));
        assertNotNull(m);
        assertEquals(0.0, m.getWaitGini(), 1e-9, "wait=5 全相等 → Gini=0");
    }

    // ---- T-11B-2 ----

    @Test
    void t11b2_giniLargeOnPolarized() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(0.0, 1, 0, "NORMAL"));
        }
        for (int i = 0; i < 30; i++) {
            samples.add(sample(10.0, 1, 0, "NORMAL"));
        }
        FairnessMetrics m = calculator.build(samples, ints(60), types("NORMAL"));
        assertNotNull(m);
        // 30 个 0 + 30 个 10 → Gini = 0.5
        assertTrue(m.getWaitGini() > 0.4,
                () -> "polarized [30×0, 30×10] 应 Gini > 0.4,实际 = " + m.getWaitGini());
    }

    // ---- T-11B-3 ----

    @Test
    void t11b3_giniMildOn1To5Uniform() {
        List<WaitTimeSample> samples = new ArrayList<>();
        // 60 个均匀分布在 [1, 5] 的值
        for (int i = 0; i < 60; i++) {
            double w = 1.0 + 4.0 * i / 59.0;
            samples.add(sample(w, 1, 0, "NORMAL"));
        }
        FairnessMetrics m = calculator.build(samples, ints(60), types("NORMAL"));
        assertNotNull(m);
        double gini = m.getWaitGini();
        // 连续 uniform[a,b] 的 Gini = (b-a)/(3(a+b)) = 4/18 ≈ 0.222
        assertTrue(gini > 0.0 && gini < 0.5,
                () -> "uniform[1,5] 应 Gini ∈ (0, 0.5),实际 = " + gini);
    }

    // ---- T-11B-4 ----

    @Test
    void t11b4_nonTakeawayLoadCV() {
        // 全相等 → CV=0
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(1.0, 1, 0, "NORMAL"));
        }
        FairnessMetrics zero = calculator.build(samples,
                ints(10, 10, 10, 10), types("NORMAL", "NORMAL", "NORMAL", "NORMAL"));
        assertNotNull(zero);
        assertEquals(0.0, zero.getNonTakeawayWindowLoadCv(), 1e-9);

        // [10, 30] → mean=20, std=10, CV=0.5
        FairnessMetrics half = calculator.build(samples,
                ints(10, 30), types("NORMAL", "NORMAL"));
        assertNotNull(half);
        assertEquals(0.5, half.getNonTakeawayWindowLoadCv(), 1e-3);
    }

    // ---- T-11B-5 ----

    @Test
    void t11b5_nonTakeawayLoadExcludesTakeaway() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(1.0, 1, 0, "NORMAL"));
        }
        // servedCounts=[10,10,1000],windowTypes=[NORMAL,NORMAL,TAKEAWAY] → CV 仅前 2 算 = 0
        FairnessMetrics m = calculator.build(samples,
                ints(10, 10, 1000), types("NORMAL", "NORMAL", "TAKEAWAY"));
        assertNotNull(m);
        assertEquals(0.0, m.getNonTakeawayWindowLoadCv(), 1e-9,
                "TAKEAWAY 必须从分母排除,字段名直接表达此语义,不读 WindowRole");
    }

    // ---- T-11B-6 ----

    @Test
    void t11b6_crossRoleSpread() {
        List<WaitTimeSample> samples = new ArrayList<>();
        // solo dine-in:20 个 partySize=1, windowType=NORMAL, wait=2
        for (int i = 0; i < 20; i++) {
            samples.add(sample(2.0, 1, 0, "NORMAL"));
        }
        // group dine-in:10 个 partySize=2, windowType=NORMAL, wait=8 → weighted=20
        for (int i = 0; i < 10; i++) {
            samples.add(sample(8.0, 2, 0, "NORMAL"));
        }
        // takeaway window:20 个 partySize=1, windowType=TAKEAWAY, wait=4
        for (int i = 0; i < 20; i++) {
            samples.add(sample(4.0, 1, 1, "TAKEAWAY"));
        }
        FairnessMetrics m = calculator.build(samples,
                ints(20, 20), types("NORMAL", "TAKEAWAY"));
        assertNotNull(m);
        // medians: solo=2, group=8, takeaway=4 → max-min = 8-2 = 6
        assertEquals(6.0, m.getCrossRoleFairness(), 1e-3,
                "三类 median wait = {solo:2, group:8, takeaway:4},max-min=6");
    }

    // ---- T-11B-7 ----

    @Test
    void t11b7_nullBelow50() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(5.0, 1, 0, "NORMAL"));
        }
        assertNull(calculator.build(samples, ints(30), types("NORMAL")),
                "30 个 partySize=1 → weighted=30 < 50 → null");
    }

    // ---- T-11B-8 ----

    @Test
    void t11b8_deterministicSameInput() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int ps = (i % 3 == 0) ? 2 : 1;
            String t = (i % 5 == 0) ? "TAKEAWAY" : "NORMAL";
            samples.add(sample(3.0 + (i % 7), ps, i % 4, t));
        }
        FairnessMetrics m1 = calculator.build(samples, ints(15, 12, 18, 10),
                types("NORMAL", "NORMAL", "NORMAL", "TAKEAWAY"));
        FairnessMetrics m2 = calculator.build(samples, ints(15, 12, 18, 10),
                types("NORMAL", "NORMAL", "NORMAL", "TAKEAWAY"));
        assertNotNull(m1);
        assertNotNull(m2);
        assertEquals(m1.getWaitGini(), m2.getWaitGini(), 0.0);
        assertEquals(m1.getNonTakeawayWindowLoadCv(), m2.getNonTakeawayWindowLoadCv(), 0.0);
        assertEquals(m1.getCrossRoleFairness(), m2.getCrossRoleFairness(), 0.0);
        assertEquals(m1.getSampleCount(), m2.getSampleCount());
    }
}
