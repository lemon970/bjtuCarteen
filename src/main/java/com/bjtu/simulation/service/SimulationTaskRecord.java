package com.bjtu.simulation.service;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;

public class SimulationTaskRecord {
    private final String taskId;
    private final String reportId;
    private final SimConfig config;
    private final long submittedAtEpochMillis;

    private volatile String status = "PENDING";
    private volatile long startedAtEpochMillis = 0L;
    private volatile long completedAtEpochMillis = 0L;
    private volatile String errorMessage = "";
    private volatile SimulationReport report;

    public SimulationTaskRecord(String taskId, String reportId, SimConfig config, long submittedAtEpochMillis) {
        this.taskId = taskId;
        this.reportId = reportId;
        this.config = config;
        this.submittedAtEpochMillis = submittedAtEpochMillis;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getReportId() {
        return reportId;
    }

    public SimConfig getConfig() {
        return config;
    }

    public long getSubmittedAtEpochMillis() {
        return submittedAtEpochMillis;
    }

    public String getStatus() {
        return status;
    }

    public void markRunning() {
        this.status = "RUNNING";
        this.startedAtEpochMillis = System.currentTimeMillis();
    }

    public void markCompleted(SimulationReport report) {
        this.report = report;
        this.status = "COMPLETED";
        this.completedAtEpochMillis = System.currentTimeMillis();
    }

    public void markFailed(Exception error) {
        this.status = "FAILED";
        this.errorMessage = error == null ? "unknown error" : error.getMessage();
        this.completedAtEpochMillis = System.currentTimeMillis();
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long getCompletedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public SimulationReport getReport() {
        return report;
    }

    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
