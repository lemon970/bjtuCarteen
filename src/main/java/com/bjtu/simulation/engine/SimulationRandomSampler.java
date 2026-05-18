package com.bjtu.simulation.engine;

import java.util.Random;

import com.bjtu.simulation.dto.SimConfig;

class SimulationRandomSampler {
    private final Random random;

    SimulationRandomSampler(Random random) {
        this.random = random;
    }

    double nextDouble() {
        return random.nextDouble();
    }

    int nextInt(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        int bound = (maxInclusive - minInclusive) + 1;
        return minInclusive + random.nextInt(bound);
    }

    long nextLong(long minInclusive, long maxExclusive) {
        if (maxExclusive <= minInclusive) {
            return minInclusive;
        }
        return random.nextLong(minInclusive, maxExclusive);
    }

    long sampleDurationSeconds(SimConfig.DistributionSpec spec, long fallbackMin, long fallbackMax) {
        SimConfig.DistributionSpec safeSpec = spec == null ? SimConfig.DistributionSpec.uniform() : spec;
        String type = normalizeDistributionType(safeSpec, "UNIFORM");
        boolean explicitDistributionParam = safeSpec.getMean() > 0 || safeSpec.getLambda() > 0 || safeSpec.getStd() > 0;
        boolean useFallbackBounds = "UNIFORM".equals(type) || !explicitDistributionParam;
        long min = safeSpec.getMin() > 0 ? safeSpec.getMin() : (useFallbackBounds ? fallbackMin : 1L);
        long max = safeSpec.getMax() > 0 ? safeSpec.getMax() : (useFallbackBounds ? fallbackMax : Long.MAX_VALUE / 4L);
        min = Math.max(0L, min);
        max = Math.max(min + 1L, max);

        double sampled;
        if ("NORMAL".equals(type)) {
            double mean = safeSpec.getMean() > 0 ? safeSpec.getMean() : (min + max) / 2.0;
            double std = safeSpec.getStd() > 0 ? safeSpec.getStd() : Math.max(1.0, (max - min) / 6.0);
            sampled = mean + random.nextGaussian() * std;
        } else if ("EXPONENTIAL".equals(type)) {
            double mean = safeSpec.getMean() > 0 ? safeSpec.getMean() : (min + max) / 2.0;
            if (safeSpec.getLambda() > 0) {
                mean = 1.0 / safeSpec.getLambda();
            }
            double u = Math.max(1.0e-12, 1.0 - nextDouble());
            sampled = -Math.log(u) * Math.max(1.0e-9, mean);
        } else if ("POISSON".equals(type)) {
            double lambda = safeSpec.getLambda() > 0 ? safeSpec.getLambda() : Math.max(0.0, safeSpec.getMean());
            sampled = samplePoisson(lambda <= 0.0 ? (min + max) / 2.0 : lambda);
        } else if ("FIXED".equals(type)) {
            sampled = safeSpec.getMean() > 0 ? safeSpec.getMean() : min;
        } else {
            long upperExclusive = max == Long.MAX_VALUE ? max : max + 1L;
            sampled = nextLong(min, upperExclusive);
        }

        long rounded = Math.round(sampled);
        if (rounded < min) {
            return min;
        }
        if (rounded > max) {
            return max;
        }
        return rounded;
    }

    int samplePoisson(double lambda) {
        if (lambda <= 0.0 || Double.isNaN(lambda) || Double.isInfinite(lambda)) {
            return 0;
        }
        if (lambda < 40.0) {
            double limit = Math.exp(-lambda);
            int k = 0;
            double product = 1.0;
            do {
                k++;
                product *= nextDouble();
            } while (product > limit);
            return Math.max(0, k - 1);
        }

        double sampled = lambda + random.nextGaussian() * Math.sqrt(lambda);
        return Math.max(0, (int) Math.round(sampled));
    }

    String normalizeDistributionType(SimConfig.DistributionSpec spec, String fallback) {
        String raw = spec == null ? null : spec.getType();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim().toUpperCase();
    }
}
