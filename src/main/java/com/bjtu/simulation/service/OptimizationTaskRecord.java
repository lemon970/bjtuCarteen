package com.bjtu.simulation.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.bjtu.simulation.dto.SimConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 异步 batch optimize 任务记录。与 {@link SimulationTaskRecord} 平行存在,
 * 字段集合不同:多了 total / configs / itemResults / failed/completed counters。
 *
 * <p>线程安全:状态字段全部 volatile;计数器 AtomicInteger;item 写入用
 * AtomicReferenceArray 按 index 隔离。{@link #recordFirstFailure} 用 synchronized
 * 保证只记第一次失败。
 *
 * <p>本期(PR-1)所有 sub-task 在同一个 worker 线程内串行写入,理论上不会发生
 * 并发写;但 counter 用原子类、status 用 volatile 是为了让 status 接口 polling
 * 线程能稳定读到中间状态。
 */
public class OptimizationTaskRecord {

    static final String STATUS_QUEUED = "QUEUED";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private final String batchTaskId;
    private final String objectiveRaw;
    private final int total;
    private final List<SimConfig> configs;
    private final long submittedAtEpochMillis;

    private final AtomicReferenceArray<ObjectNode> itemResults;
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    private volatile String status = STATUS_QUEUED;
    private volatile long startedAtEpochMillis = 0L;
    private volatile long completedAtEpochMillis = 0L;
    private volatile long lastUpdatedAtEpochMillis;
    private volatile int currentIndex = 0;
    private volatile String firstFailureMessage = null;
    private volatile int firstFailureIndex = -1;

    public OptimizationTaskRecord(String batchTaskId,
                                  String objectiveRaw,
                                  List<SimConfig> configs,
                                  long submittedAtEpochMillis) {
        this.batchTaskId = batchTaskId;
        this.objectiveRaw = objectiveRaw;
        this.configs = configs;
        this.total = configs.size();
        this.submittedAtEpochMillis = submittedAtEpochMillis;
        this.lastUpdatedAtEpochMillis = submittedAtEpochMillis;
        this.itemResults = new AtomicReferenceArray<>(this.total);
    }

    public String getBatchTaskId() {
        return batchTaskId;
    }

    public String getObjectiveRaw() {
        return objectiveRaw;
    }

    public int getTotal() {
        return total;
    }

    public List<SimConfig> getConfigs() {
        return configs;
    }

    public long getSubmittedAtEpochMillis() {
        return submittedAtEpochMillis;
    }

    public String getStatus() {
        return status;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long getCompletedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    public long getLastUpdatedAtEpochMillis() {
        return lastUpdatedAtEpochMillis;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getCompletedCount() {
        return completedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public String getFirstFailureMessage() {
        return firstFailureMessage;
    }

    public int getFirstFailureIndex() {
        return firstFailureIndex;
    }

    public AtomicReferenceArray<ObjectNode> getItemResults() {
        return itemResults;
    }

    public boolean hasFailures() {
        return failedCount.get() > 0;
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    public boolean isResultAvailable() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
    }

    void markRunning(long now) {
        this.status = STATUS_RUNNING;
        this.startedAtEpochMillis = now;
        this.lastUpdatedAtEpochMillis = now;
    }

    void markCompleted(long now) {
        this.status = STATUS_COMPLETED;
        this.completedAtEpochMillis = now;
        this.lastUpdatedAtEpochMillis = now;
    }

    void markFailed(long now) {
        this.status = STATUS_FAILED;
        this.completedAtEpochMillis = now;
        this.lastUpdatedAtEpochMillis = now;
    }

    void touchLastUpdated(long now) {
        this.lastUpdatedAtEpochMillis = now;
    }

    void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    void setItemResult(int zeroBasedIndex, ObjectNode node) {
        itemResults.set(zeroBasedIndex, node);
    }

    void incrementCompleted() {
        completedCount.incrementAndGet();
    }

    void incrementFailed() {
        failedCount.incrementAndGet();
    }

    synchronized void recordFirstFailure(int index, Throwable error) {
        if (firstFailureMessage != null) {
            return;
        }
        String simpleName = error == null ? "UnknownError" : error.getClass().getSimpleName();
        String message = error == null ? null : error.getMessage();
        String suffix = (message == null || message.isBlank()) ? "" : ": " + message;
        this.firstFailureMessage = "config[" + (index - 1) + "]: " + simpleName + suffix;
        this.firstFailureIndex = index;
    }
}
