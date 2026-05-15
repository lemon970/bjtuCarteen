package com.bjtu.simulation.dto;

public record ScenarioPreset(
        String id,
        String name,
        String purpose,
        String category,
        SimConfig config,
        ScenarioExpectedMetrics expectedMetrics) {
}
