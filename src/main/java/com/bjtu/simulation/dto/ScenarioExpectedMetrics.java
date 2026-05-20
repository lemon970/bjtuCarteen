package com.bjtu.simulation.dto;

public record ScenarioExpectedMetrics(
        int expectedArrivals,
        String takeawayRateRange,
        String typicalWaitRange,
        String seatUtilizationRange) {
}
