package com.bjtu.simulation.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

public class ScenarioBatchRunRequest {
    @JsonAlias("scenario_ids")
    private List<String> scenarioIds = new ArrayList<>();

    private JsonNode overrides;

    public List<String> getScenarioIds() {
        return scenarioIds;
    }

    public void setScenarioIds(List<String> scenarioIds) {
        this.scenarioIds = scenarioIds == null ? new ArrayList<>() : scenarioIds;
    }

    public JsonNode getOverrides() {
        return overrides;
    }

    public void setOverrides(JsonNode overrides) {
        this.overrides = overrides;
    }
}
