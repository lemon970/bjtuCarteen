package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9C T2a:weighted sampler 大样本单元测试。
 *
 * <p>用 N=100_000 大样本验证 cumulative-weight 抽样的经验概率与理论分布一致;
 * 不依赖仿真主流程,执行时间 < 200ms。集成层 T2b 只断言方向,精度由本测试守住。</p>
 */
class WindowAttractivenessSamplerTest {

    /** RFC-009 §7.2 默认参数:8 普通窗口(2 popular / 4 normal / 2 cold)+ 2 打包窗口(中性 1.0)。 */
    private static final double[] WEIGHTS_DEFAULT_10 = {
            1.4, 1.4, 1.0, 1.0, 1.0, 1.0, 0.8, 0.8, 1.0, 1.0
    };

    @Test
    void empiricalDistributionMatchesTheoryWithinOnePercent() {
        int n = 100_000;
        int[] counts = sampleCounts(WEIGHTS_DEFAULT_10, n, new Random(20260521L));

        double total = 0.0;
        for (double w : WEIGHTS_DEFAULT_10) {
            total += w;
        }
        for (int i = 0; i < WEIGHTS_DEFAULT_10.length; i++) {
            double empirical = counts[i] / (double) n;
            double theoretical = WEIGHTS_DEFAULT_10[i] / total;
            assertTrue(Math.abs(empirical - theoretical) < 0.01,
                    () -> "weight idx error: empirical vs theoretical too far apart");
        }
    }

    @Test
    void normalWindowGroupNormalizationMatchesRev3Section72() {
        int n = 100_000;
        int[] counts = sampleCounts(WEIGHTS_DEFAULT_10, n, new Random(20260521L));

        // idx 0..1 = popular, 2..5 = normal, 6..7 = cold, 8..9 = takeaway
        int popular = counts[0] + counts[1];
        int normal = counts[2] + counts[3] + counts[4] + counts[5];
        int cold = counts[6] + counts[7];
        int takeaway = counts[8] + counts[9];

        int normalGroup = popular + normal + cold;
        double popShare = popular / (double) normalGroup;
        double normShare = normal / (double) normalGroup;
        double coldShare = cold / (double) normalGroup;

        // §7.2 理论:popular≈0.333, normal≈0.476, cold≈0.190
        assertTrue(Math.abs(popShare - 0.333) < 0.01, () -> "popular share = " + popShare);
        assertTrue(Math.abs(normShare - 0.476) < 0.01, () -> "normal share = " + normShare);
        assertTrue(Math.abs(coldShare - 0.190) < 0.01, () -> "cold share = " + coldShare);

        // 打包整体占比 ≈ 0.192
        double takeawayShare = takeaway / (double) n;
        assertTrue(Math.abs(takeawayShare - 0.192) < 0.01,
                () -> "takeaway share = " + takeawayShare);
    }

    @Test
    void sameSeedYieldsByteEqualCounts() {
        int n = 100_000;
        int[] a = sampleCounts(WEIGHTS_DEFAULT_10, n, new Random(20260521L));
        int[] b = sampleCounts(WEIGHTS_DEFAULT_10, n, new Random(20260521L));
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "counts[" + i + "] must be byte-equal under same seed");
        }
    }

    @Test
    void zeroWeightShouldThrow() {
        double[] bad = {1.0, 0.0, 1.0};
        Random r = new Random(0L);
        Throwable t = catching(() -> WindowAttractivenessSampler.sample(bad, r));
        assertNotNull(t, "expected IllegalArgumentException but no throwable captured");
        assertTrue(t instanceof IllegalArgumentException, t.getClass().getName());
    }

    @Test
    void emptyWeightsShouldThrow() {
        double[] empty = new double[0];
        Random r = new Random(0L);
        Throwable t = catching(() -> WindowAttractivenessSampler.sample(empty, r));
        assertNotNull(t, "expected IllegalArgumentException but no throwable captured");
        assertTrue(t instanceof IllegalArgumentException, t.getClass().getName());
    }

    @Test
    void uniformWeightsShouldDegenerateToUniform() {
        double[] uniform = {1.0, 1.0, 1.0, 1.0};
        int n = 80_000;
        int[] counts = sampleCounts(uniform, n, new Random(20260521L));
        for (int i = 0; i < uniform.length; i++) {
            final int idx = i;
            double empirical = counts[i] / (double) n;
            assertTrue(Math.abs(empirical - 0.25) < 0.01,
                    () -> "uniform sampling drift at " + idx + " = " + (counts[idx] / (double) n));
        }
    }

    // ---- helpers ----

    private int[] sampleCounts(double[] weights, int n, Random random) {
        int[] counts = new int[weights.length];
        for (int i = 0; i < n; i++) {
            counts[WindowAttractivenessSampler.sample(weights, random)]++;
        }
        return counts;
    }

    private Throwable catching(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
