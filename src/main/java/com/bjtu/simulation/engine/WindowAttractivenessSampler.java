package com.bjtu.simulation.engine;

import java.util.Random;

/**
 * RFC-009 §5.2 加权抽样器。
 *
 * <p>使用 cumulative-weight 二分查找,O(log W),W 为窗口数(通常 < 16)。
 * 每次抽样消费 {@link Random#nextDouble()} 一次,与原均匀 {@code random.nextInt}
 * 的随机消耗量相同,不会让主流随机序列错位。</p>
 *
 * <p>纯函数(无可变状态),便于 T2a 单元测试以大样本验证理论分布。</p>
 */
final class WindowAttractivenessSampler {

    private WindowAttractivenessSampler() {
    }

    /**
     * 按权重抽样返回 {@code [0, weights.length)} 中的下标。
     *
     * <p>实现要点:</p>
     * <ul>
     *   <li>预计算 cumulative 和 totalWeight。</li>
     *   <li>{@code u = random.nextDouble() * totalWeight},二分定位首个 cumulative >= u 的下标。</li>
     *   <li>所有权重必须 > 0(由 SimulationConfigNormalizer 校验)。</li>
     * </ul>
     */
    static int sample(double[] weights, Random random) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        double[] cumulative = buildCumulative(weights);
        double total = cumulative[cumulative.length - 1];
        if (!(total > 0.0)) {
            throw new IllegalArgumentException("totalWeight must be > 0");
        }
        double u = random.nextDouble() * total;
        return binarySearch(cumulative, u);
    }

    /** 重载:复用 SimulationRandomSampler,语义等价于上面的 Random 版本。 */
    static int sample(double[] weights, SimulationRandomSampler sampler) {
        if (sampler == null) {
            throw new IllegalArgumentException("sampler must not be null");
        }
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        double[] cumulative = buildCumulative(weights);
        double total = cumulative[cumulative.length - 1];
        if (!(total > 0.0)) {
            throw new IllegalArgumentException("totalWeight must be > 0");
        }
        double u = sampler.nextDouble() * total;
        return binarySearch(cumulative, u);
    }

    private static double[] buildCumulative(double[] weights) {
        double[] cumulative = new double[weights.length];
        double sum = 0.0;
        for (int i = 0; i < weights.length; i++) {
            double w = weights[i];
            if (!(w > 0.0)) {
                throw new IllegalArgumentException(
                        "weights[" + i + "] must be > 0 (got " + w + ")");
            }
            sum += w;
            cumulative[i] = sum;
        }
        return cumulative;
    }

    private static int binarySearch(double[] cumulative, double u) {
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] >= u) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
