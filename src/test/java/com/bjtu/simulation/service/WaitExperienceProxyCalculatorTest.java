package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WaitExperienceProxyMetrics;
import com.bjtu.simulation.model.WaitTimeSample;

import org.junit.jupiter.api.Test;

/**
 * RFC-011 §A:T-11A-1 ~ T-11A-11。
 *
 * <p>全部用合成 {@link WaitTimeSample}(不依赖真仿真路径),守住:
 * <ul>
 *   <li>party-weighted 总样本 &lt; 50 → 整对返回 null(T-11A-1 / T-11A-11)</li>
 *   <li>solo amplifier / anxiety / uncertainty / pre-process / blended index 公式手算对照</li>
 *   <li>同输入两次 build 字段全 == </li>
 * </ul></p>
 */
class WaitExperienceProxyCalculatorTest {

    private final WaitExperienceProxyCalculator calculator = new WaitExperienceProxyCalculator();

    /** baseline serviceRange=[60,180] → mean_service = 120s = 2 min;queueLimit=10。 */
    private SimConfig basicConfig() {
        SimConfig c = new SimConfig();
        c.setQueueLimit(10);
        c.getRandomBounds().setServiceRange(new ArrayList<>(List.of(60, 180)));
        return c;
    }

    private WaitTimeSample sample(double waitMinutes,
                                  int partySize,
                                  int windowId,
                                  String windowType,
                                  int queueLengthAtJoin) {
        long enter = 0L;
        long start = (long) Math.round(waitMinutes * 60.0);
        return new WaitTimeSample(enter, start, partySize, windowId, windowType,
                queueLengthAtJoin, WaitTimeSample.Phase.STEADY);
    }

    // ---- T-11A-1 ----

    @Test
    void t11a1_emptyOrTooFewReturnsNull() {
        assertNull(calculator.build(null, basicConfig()),
                "samples=null 应返回 null");
        assertNull(calculator.build(List.of(), basicConfig()),
                "空 samples 应返回 null");
        List<WaitTimeSample> tooFew = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            tooFew.add(sample(5.0, 1, 0, "NORMAL", 0));
        }
        assertNull(calculator.build(tooFew, basicConfig()),
                "49 个 partySize=1 sample(weighted=49)应返回 null");
    }

    // ---- T-11A-2 ----

    @Test
    void t11a2_soloAmplifierAllSolo() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(5.0, 1, 0, "NORMAL", 0));
        }
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        // solo_share = 1.0,solo_adjusted = 5 * (1 + 0.15 * 1.0) = 5.75
        assertEquals(5.75, m.getSoloAdjustedWaitMinutes(), 1e-3);
        assertEquals(60L, m.getSampleCount());
    }

    // ---- T-11A-3 ----

    @Test
    void t11a3_soloAmplifierMixed() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(10.0, 1, 0, "NORMAL", 0));
        }
        for (int i = 0; i < 30; i++) {
            samples.add(sample(10.0, 2, 0, "NORMAL", 0));
        }
        // weighted total = 30 + 60 = 90;solo_share = 30/90 ≈ 0.3333
        // mean_wait = (30*10 + 30*10*2) / 90 = (300 + 600) / 90 = 10
        // solo_adjusted = 10 * (1 + 0.15 * 0.3333) = 10.5
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        assertEquals(10.5, m.getSoloAdjustedWaitMinutes(), 1e-3);
        assertEquals(90L, m.getSampleCount());
    }

    // ---- T-11A-4 ----

    @Test
    void t11a4_anxietyTriggerAt07() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(5.0, 1, 0, "NORMAL", 8));
        }
        // pressure = 8/10 = 0.8;over = 0.8 - 0.7 = 0.1
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        assertEquals(0.1, m.getAnxietyPressureIndex(), 1e-3);
    }

    // ---- T-11A-5 ----

    @Test
    void t11a5_anxietyBelowThreshold() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(5.0, 1, 0, "NORMAL", 5));
        }
        // pressure = 0.5 < 0.7 → over = 0
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        assertEquals(0.0, m.getAnxietyPressureIndex(), 1e-9);
    }

    // ---- T-11A-6 ----

    @Test
    void t11a6_uncertaintyCVPerWindow() {
        List<WaitTimeSample> samples = new ArrayList<>();
        // window 0:30 个 wait=10.0 → bucket cv=0
        for (int i = 0; i < 30; i++) {
            samples.add(sample(10.0, 1, 0, "NORMAL", 0));
        }
        // window 1:30 个 wait=5/15 交替 → bucket mean=10, std=5, cv=0.5
        for (int i = 0; i < 30; i++) {
            double w = (i % 2 == 0) ? 5.0 : 15.0;
            samples.add(sample(w, 1, 1, "NORMAL", 0));
        }
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        // 桶间按 weighted count(各 30)等权 → cv = (0 + 0.5) / 2 = 0.25
        assertEquals(0.25, m.getWaitUncertaintyScore(), 1e-3);
    }

    // ---- T-11A-7 ----

    @Test
    void t11a7_preProcessShareWithBaselineService() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(10.0, 1, 0, "NORMAL", 0));
        }
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        // mean_wait=10 min;serviceRange=[60,180] → mean_service=120s=2 min
        // share = 10 / (10 + 2) = 0.8333...
        assertEquals(0.833, m.getPreProcessWaitShare(), 1e-3);
    }

    // ---- T-11A-8 ----

    @Test
    void t11a8_partyWeightingHonored() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(sample(10.0, 2, 0, "NORMAL", 0));
        }
        for (int i = 0; i < 30; i++) {
            samples.add(sample(20.0, 1, 0, "NORMAL", 0));
        }
        // weighted total = 30*2 + 30*1 = 90
        // weighted mean wait = (60 * 10 + 30 * 20) / 90 = (600 + 600) / 90 = 13.333
        // solo_share = 30 / 90 ≈ 0.333
        // solo_adjusted = 13.333 * (1 + 0.15 * 0.333) = 13.333 * 1.05 = 14.0
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);
        assertEquals(90L, m.getSampleCount());
        assertEquals(14.0, m.getSoloAdjustedWaitMinutes(), 1e-2,
                "weighted mean wait=13.333,solo_share≈0.333,solo_adjusted ≈ 14.0;若退化为 samples.size() 加权会变成不同值");
    }

    // ---- T-11A-9 ----

    @Test
    void t11a9_indexBlendsFour() {
        // 构造已知 4 component:
        //   pre=0.833(同 t11a7);uncertainty=0(单窗口 wait 全 10);
        //   anxiety=0.1(queueLen=8 / queueLimit=10);solo_adjusted=11.5(60 个 partySize=1, wait=10)。
        //   solo_component = 11.5 / 12.5 = 0.92
        //   index = 0.25*0.833 + 0.25*0 + 0.25*0.1 + 0.25*0.92
        //         = 0.2083 + 0 + 0.025 + 0.23 = 0.4633...
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(10.0, 1, 0, "NORMAL", 8));
        }
        WaitExperienceProxyMetrics m = calculator.build(samples, basicConfig());
        assertNotNull(m);

        double pre = m.getPreProcessWaitShare();
        double unc = clamp(m.getWaitUncertaintyScore(), 0.0, 1.0);
        double anx = clamp(m.getAnxietyPressureIndex(), 0.0, 1.0);
        double soloAdj = m.getSoloAdjustedWaitMinutes();
        double soloComp = soloAdj / (soloAdj + 1.0);
        double expectedIndex = 0.25 * pre + 0.25 * unc + 0.25 * anx + 0.25 * soloComp;
        double expectedRounded = Math.round(expectedIndex * 1000.0) / 1000.0;

        assertEquals(expectedRounded, m.getWaitExperienceProxyIndex(), 1e-3,
                () -> "index 公式应等于 0.25*pre + 0.25*clip(unc,0,1) + 0.25*clip(anx,0,1) + 0.25*solo_adj/(solo_adj+1);"
                        + "expected=" + expectedRounded
                        + ",actual=" + m.getWaitExperienceProxyIndex()
                        + ",pre=" + pre + ",unc=" + unc + ",anx=" + anx + ",soloAdj=" + soloAdj);
    }

    // ---- T-11A-10 ----

    @Test
    void t11a10_deterministicSameInput() {
        List<WaitTimeSample> samples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            samples.add(sample(7.5, 1, i % 3, "NORMAL", i % 11));
        }
        WaitExperienceProxyMetrics m1 = calculator.build(samples, basicConfig());
        WaitExperienceProxyMetrics m2 = calculator.build(samples, basicConfig());
        assertEquals(m1.getPreProcessWaitShare(), m2.getPreProcessWaitShare(), 0.0);
        assertEquals(m1.getWaitUncertaintyScore(), m2.getWaitUncertaintyScore(), 0.0);
        assertEquals(m1.getAnxietyPressureIndex(), m2.getAnxietyPressureIndex(), 0.0);
        assertEquals(m1.getSoloAdjustedWaitMinutes(), m2.getSoloAdjustedWaitMinutes(), 0.0);
        assertEquals(m1.getWaitExperienceProxyIndex(), m2.getWaitExperienceProxyIndex(), 0.0);
        assertEquals(m1.getSampleCount(), m2.getSampleCount());
    }

    // ---- T-11A-11 ----

    @Test
    void t11a11_weightedCountBoundary() {
        // 49 个 partySize=1 → weighted=49 → null
        List<WaitTimeSample> tooFew = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            tooFew.add(sample(5.0, 1, 0, "NORMAL", 0));
        }
        assertNull(calculator.build(tooFew, basicConfig()),
                "49 个 partySize=1 sample → weighted=49 < 50 → 整对返回 null");

        // 25 个 partySize=2 → weighted=50 → 非 null
        List<WaitTimeSample> exactly50 = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            exactly50.add(sample(5.0, 2, 0, "NORMAL", 0));
        }
        WaitExperienceProxyMetrics m = calculator.build(exactly50, basicConfig());
        assertNotNull(m,
                "25 个 partySize=2 sample → weighted=50 ≥ 50 → 必须非 null,守 N<50 用 party-weighted count");
        assertEquals(50L, m.getSampleCount());
        // 全 partySize=2,solo_share=0,solo_adjusted=5*(1+0)=5.0
        assertEquals(5.0, m.getSoloAdjustedWaitMinutes(), 1e-3);
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
