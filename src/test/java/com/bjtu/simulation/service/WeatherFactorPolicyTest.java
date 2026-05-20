package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WeatherFactorPolicyTest {

    private static final double EPS = 1e-9;

    @Test
    void sunnyAndUnitFactorReturnsBaseline() {
        assertEquals(1.00, WeatherFactorPolicy.resolveEffectiveFactor("sunny", 1.0), EPS);
    }

    @Test
    void rainyAndUnitFactorReturnsCanonicalBaseline() {
        assertEquals(1.30, WeatherFactorPolicy.resolveEffectiveFactor("rainy", 1.0), EPS);
    }

    @Test
    void rainyTimesUserFactorMultiplies() {
        // rainy canonical 1.30 × user 1.25 = 1.625
        assertEquals(1.625, WeatherFactorPolicy.resolveEffectiveFactor("rainy", 1.25), EPS);
    }

    @Test
    void unknownWeatherFallsBackToDefault() {
        // unknown weather → canonical 1.00, user 1.5 → 1.5
        assertEquals(1.50, WeatherFactorPolicy.resolveEffectiveFactor("apocalypse", 1.5), EPS);
    }

    @Test
    void caseInsensitiveAndTrim() {
        assertEquals(1.30, WeatherFactorPolicy.resolveEffectiveFactor("  RAINY  ", 1.0), EPS);
        assertEquals(1.45, WeatherFactorPolicy.resolveEffectiveFactor("Snowy", 1.0), EPS);
    }

    @Test
    void nullWeatherFallsBackToDefault() {
        assertEquals(1.00, WeatherFactorPolicy.resolveEffectiveFactor(null, 1.0), EPS);
    }

    @Test
    void nonPositiveUserFactorIsTreatedAsOne() {
        assertEquals(1.30, WeatherFactorPolicy.resolveEffectiveFactor("rainy", 0.0), EPS);
        assertEquals(1.30, WeatherFactorPolicy.resolveEffectiveFactor("rainy", -2.0), EPS);
    }

    @Test
    void clampsExcessiveProductDownToCeiling() {
        // stormy 1.55 × 3.0 = 4.65 → clamped to 3.0
        assertEquals(3.0, WeatherFactorPolicy.resolveEffectiveFactor("stormy", 3.0), EPS);
    }

    @Test
    void getCanonicalFactorExposesBaseline() {
        assertEquals(1.30, WeatherFactorPolicy.getCanonicalFactor("rainy"), EPS);
        assertEquals(1.00, WeatherFactorPolicy.getCanonicalFactor("sunny"), EPS);
        assertEquals(1.00, WeatherFactorPolicy.getCanonicalFactor(null), EPS);
    }
}
