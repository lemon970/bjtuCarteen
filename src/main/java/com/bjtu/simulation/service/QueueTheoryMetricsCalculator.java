package com.bjtu.simulation.service;

import java.util.List;

import com.bjtu.simulation.dto.QueueTheoryMetrics;
import com.bjtu.simulation.dto.SimConfig;

import org.springframework.stereotype.Service;

@Service
public class QueueTheoryMetricsCalculator {

    public QueueTheoryMetrics build(SimConfig config, int windowCount) {
        int servers = Math.max(1, windowCount);
        double arrivalRatePerHour = Math.max(0.0, config.getArrivalRate());
        double serviceMeanSeconds = expectedServiceMeanSeconds(config);
        double serviceRatePerHour = serviceMeanSeconds <= 0 ? 0.0 : 3600.0 / serviceMeanSeconds;
        double utilization = serviceRatePerHour <= 0 ? 0.0 : arrivalRatePerHour / (servers * serviceRatePerHour);
        String modelType = servers == 1 ? "M/M/1" : "M/M/c";

        boolean enabled = isPoissonLike(config.getArrivalDist()) && isExponentialLike(config.getNormalServiceDist());
        if (!enabled || arrivalRatePerHour <= 0 || serviceRatePerHour <= 0) {
            return new QueueTheoryMetrics(enabled, false, modelType, servers, arrivalRatePerHour, serviceRatePerHour, utilization,
                    null, null, null, null);
        }
        if (utilization >= 1.0) {
            return new QueueTheoryMetrics(true, false, modelType, servers, arrivalRatePerHour, serviceRatePerHour, utilization,
                    null, null, null, null);
        }

        double traffic = arrivalRatePerHour / serviceRatePerHour;
        double sum = 0.0;
        for (int n = 0; n < servers; n++) {
            sum += Math.pow(traffic, n) / factorial(n);
        }
        double tail = Math.pow(traffic, servers) / (factorial(servers) * (1.0 - utilization));
        double p0 = 1.0 / (sum + tail);
        double expectedQueueLength = p0
                * Math.pow(traffic, servers)
                * utilization
                / (factorial(servers) * Math.pow(1.0 - utilization, 2.0));
        double expectedSystemLength = expectedQueueLength + traffic;
        double expectedQueueWaitMinutes = expectedQueueLength / arrivalRatePerHour * 60.0;
        double expectedSystemWaitMinutes = expectedSystemLength / arrivalRatePerHour * 60.0;

        return new QueueTheoryMetrics(true, true, modelType, servers, arrivalRatePerHour, serviceRatePerHour, utilization,
                expectedQueueLength, expectedSystemLength, expectedQueueWaitMinutes, expectedSystemWaitMinutes);
    }

    private boolean isPoissonLike(SimConfig.DistributionSpec spec) {
        return spec != null && "POISSON".equalsIgnoreCase(spec.getType());
    }

    private boolean isExponentialLike(SimConfig.DistributionSpec spec) {
        return spec != null && "EXPONENTIAL".equalsIgnoreCase(spec.getType());
    }

    private double expectedServiceMeanSeconds(SimConfig config) {
        SimConfig.DistributionSpec spec = config.getNormalServiceDist();
        if (spec != null) {
            if (spec.getLambda() > 0) {
                return 1.0 / spec.getLambda();
            }
            if (spec.getMean() > 0) {
                return spec.getMean();
            }
        }
        List<Integer> range = config.getRandomBounds().getServiceRange();
        if (range == null || range.size() < 2) {
            return 180.0;
        }
        return (Math.max(1, range.get(0)) + Math.max(1, range.get(1))) / 2.0;
    }

    private double factorial(int value) {
        double result = 1.0;
        for (int i = 2; i <= Math.max(1, value); i++) {
            result *= i;
        }
        return result;
    }
}
