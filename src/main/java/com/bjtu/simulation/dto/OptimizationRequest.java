package com.bjtu.simulation.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class OptimizationRequest {
    @Valid
    @JsonAlias({"config", "base_config"})
    private SimConfig config = new SimConfig();

    @Valid
    @JsonAlias({"configs", "scenarios"})
    private List<SimConfig> configs = new ArrayList<>();

    @JsonAlias("objective")
    private String objective = "minimize typical_wait_time_minutes";

    /**
     * 保留以兼容历史前端,已不参与逻辑;详见 ADR 005 / API.md §7。
     */
    @Deprecated
    @JsonAlias("constraints")
    private List<String> constraints = new ArrayList<>();

    @JsonAlias("max_candidates")
    @Min(value = 1, message = "maxCandidates must be >= 1")
    @Max(value = 500, message = "maxCandidates must be <= 500")
    private int maxCandidates = 100;

    /**
     * 保留以兼容历史前端,已不参与逻辑;详见 ADR 005 / API.md §7。
     */
    @Deprecated
    @JsonAlias("top_n")
    @Min(value = 1, message = "topN must be >= 1")
    @Max(value = 10, message = "topN must be <= 10")
    private int topN = 3;

    public SimConfig getConfig() {
        return config;
    }

    public void setConfig(SimConfig config) {
        this.config = config;
    }

    public List<SimConfig> getConfigs() {
        return configs;
    }

    public void setConfigs(List<SimConfig> configs) {
        this.configs = configs;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    @Deprecated
    public List<String> getConstraints() {
        return constraints;
    }

    @Deprecated
    public void setConstraints(List<String> constraints) {
        this.constraints = constraints;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    @Deprecated
    public int getTopN() {
        return topN;
    }

    @Deprecated
    public void setTopN(int topN) {
        this.topN = topN;
    }
}
