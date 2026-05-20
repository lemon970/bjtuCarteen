package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TakeawayDecisionPolicyTest {

    private final TakeawayDecisionPolicy policy = new TakeawayDecisionPolicy();

    @Test
    void pressureFactorsShouldUseSmoothNonZeroScale() {
        TakeawayDecisionPolicy.DecisionProbability probability = policy.resolve(
                0.15,
                0.15,
                0.70,
                0.85,
                8.0,
                1.0,
                0,
                30);

        assertEquals(0.176, probability.seatPressureFactor(), 0.002);
        assertEquals(0.068, probability.waitPressureFactor(), 0.002);
        assertEquals(0.071, probability.queuePressureFactor(), 0.002);
        assertTrue(probability.finalProbability() >= 0.15);
        assertTrue(probability.finalProbability() <= 0.45);
    }

    @Test
    void factorsShouldIncreaseMonotonicallyWithPressure() {
        TakeawayDecisionPolicy.DecisionProbability low = policy.resolve(
                0.15,
                0.15,
                0.30,
                0.50,
                3.0,
                1.0,
                0,
                30);
        TakeawayDecisionPolicy.DecisionProbability high = policy.resolve(
                0.15,
                0.15,
                0.85,
                0.93,
                13.0,
                1.0,
                0,
                30);

        assertTrue(high.seatPressureFactor() > low.seatPressureFactor());
        assertTrue(high.waitPressureFactor() > low.waitPressureFactor());
        assertTrue(high.queuePressureFactor() > low.queuePressureFactor());
        assertTrue(high.finalProbability() > low.finalProbability());
    }
}
