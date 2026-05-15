package com.bjtu.simulation.engine;

import com.bjtu.simulation.service.SimulationMath;

class TakeawayDecisionPolicy {
    private static final double MAX_MODEL_PACK_PROBABILITY = 0.75;
    private static final double TAKEAWAY_RATE_SOFT_CAP = 0.45;

    DecisionProbability resolve(double basePackProbability,
                                double studentPackPreference,
                                double queuePressure,
                                double seatUtilization,
                                double waitMinutes,
                                double weatherFactor,
                                int currentTakeawayCount,
                                int currentServedCount) {
        double base = SimulationMath.clamp(basePackProbability, 0.0, 1.0);
        double preference = SimulationMath.clamp(studentPackPreference, 0.0, 1.0);
        double queue = SimulationMath.clamp(queuePressure, 0.0, 1.0);
        double seat = SimulationMath.clamp(seatUtilization, 0.0, 1.0);
        double preferenceFactor = SimulationMath.clamp((preference - base) * 0.35, -0.06, 0.08);
        double seatPressureFactor = smoothStep(0.30, 0.90, seat) * 0.18;
        double waitPressureFactor = smoothStep(1.5, 12.0, waitMinutes) * 0.10;
        double queuePressureFactor = smoothStep(0.15, 0.85, queue) * 0.08;
        double weatherDelta = SimulationMath.clamp((weatherFactor - 1.0) * 0.08, -0.04, 0.08);

        double localCap = resolveLocalCap(base, seat, waitMinutes, queue);
        double modelProbability = SimulationMath.clamp(
                base + preferenceFactor + seatPressureFactor + waitPressureFactor + queuePressureFactor + weatherDelta,
                0.02,
                localCap);

        if (currentServedCount >= 20) {
            double runningRate = (double) currentTakeawayCount / currentServedCount;
            if (runningRate > TAKEAWAY_RATE_SOFT_CAP) {
                double overflow = SimulationMath.clamp((runningRate - TAKEAWAY_RATE_SOFT_CAP) / 0.20, 0.0, 1.0);
                modelProbability *= (1.0 - 0.45 * overflow);
            }
        }

        return new DecisionProbability(
                SimulationMath.clamp(modelProbability, 0.0, MAX_MODEL_PACK_PROBABILITY),
                preferenceFactor,
                seatPressureFactor,
                waitPressureFactor,
                queuePressureFactor,
                weatherDelta,
                decisionReason(seat, waitMinutes, queue));
    }

    private double resolveLocalCap(double base, double seat, double waitMinutes, double queue) {
        double localCap = base + 0.05;
        if (seat >= 0.98 || (seat >= 0.92 && waitMinutes >= 12.0)) {
            localCap = 0.65;
        } else if (seat >= 0.90) {
            localCap = 0.45;
        } else if (waitMinutes >= 18.0 && queue >= 0.90) {
            localCap = 0.35;
        }
        return SimulationMath.clamp(localCap, 0.02, MAX_MODEL_PACK_PROBABILITY);
    }

    private double smoothStep(double edge0, double edge1, double value) {
        double t = SimulationMath.clamp((value - edge0) / Math.max(1.0e-9, edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private String decisionReason(double seatUtilization, double waitMinutes, double queuePressure) {
        if (seatUtilization >= 0.98) {
            return "座位接近满载，打包概率进入高压上限";
        }
        if (seatUtilization >= 0.90) {
            return "座位占用率较高，增加打包倾向";
        }
        if (waitMinutes >= 12.0) {
            return "等待时间超过阈值，增加打包倾向";
        }
        if (queuePressure >= 0.85) {
            return "窗口排队压力较高，增加打包倾向";
        }
        return "常规压力场景，打包概率围绕基础概率小幅波动";
    }

    record DecisionProbability(double finalProbability,
                               double preferenceFactor,
                               double seatPressureFactor,
                               double waitPressureFactor,
                               double queuePressureFactor,
                               double weatherFactor,
                               String decisionReason) {
    }
}
