package com.bjtu.simulation.dto;

public class SimulationReport {
    private final String reportVersion;
    private final String reportId;
    private final long effectiveSeed;
    private final SimConfig config;
    private final SimulationSummary summary;
    private final String generatedAt;
    private final long generatedAtEpochMillis;

    public SimulationReport(String reportVersion,
                            String reportId,
                            long effectiveSeed,
                            SimConfig config,
                            SimulationSummary summary,
                            String generatedAt,
                            long generatedAtEpochMillis) {
        this.reportVersion = reportVersion;
        this.reportId = reportId;
        this.effectiveSeed = effectiveSeed;
        this.config = config;
        this.summary = summary;
        this.generatedAt = generatedAt;
        this.generatedAtEpochMillis = generatedAtEpochMillis;
    }

    public String getReportVersion() {
        return reportVersion;
    }

    public String getReportId() {
        return reportId;
    }

    public long getEffectiveSeed() {
        return effectiveSeed;
    }

    public SimConfig getConfig() {
        return config;
    }

    public SimulationSummary getSummary() {
        return summary;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public long getGeneratedAtEpochMillis() {
        return generatedAtEpochMillis;
    }
}