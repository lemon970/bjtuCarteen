package com.bjtu.simulation.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

@Service
public class SimulationTaskService {

    /** 第七轮:已完成或失败任务保留 30 分钟后清理。 */
    static final long DEFAULT_RETENTION_MILLIS = 30L * 60L * 1000L;
    /** 最大任务数。超出后优先清理最旧的已终结任务。 */
    static final int DEFAULT_MAX_TASKS = 200;

    private final SimulationRunService simulationRunService;
    private final SimulationReportRepository reportRepository;
    private final ObjectMapper reportMapper;
    private final ConcurrentHashMap<String, SimulationTaskRecord> tasks = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    // 第七轮:streamExecutor 改为有界,避免无限制 SSE 并发吃光线程。
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            4,
            16,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final LongSupplier clock;
    private final long retentionMillis;
    private final int maxTasks;

    @Autowired
    public SimulationTaskService(SimulationRunService simulationRunService,
                                 SimulationReportRepository reportRepository) {
        this(simulationRunService, reportRepository, AppBeansConfig.createReportObjectMapper());
    }

    public SimulationTaskService(SimulationRunService simulationRunService,
                                 SimulationReportRepository reportRepository,
                                 ObjectMapper reportMapper) {
        this(simulationRunService, reportRepository, reportMapper,
                System::currentTimeMillis, DEFAULT_RETENTION_MILLIS, DEFAULT_MAX_TASKS);
    }

    /** Test-only constructor:允许注入 clock 和阈值用于验证 TTL 清理。 */
    SimulationTaskService(SimulationRunService simulationRunService,
                          SimulationReportRepository reportRepository,
                          ObjectMapper reportMapper,
                          LongSupplier clock,
                          long retentionMillis,
                          int maxTasks) {
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.reportMapper = reportMapper;
        this.clock = clock;
        this.retentionMillis = Math.max(1L, retentionMillis);
        this.maxTasks = Math.max(1, maxTasks);
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        streamExecutor.shutdownNow();
    }

    public SimulationTaskRecord submit(SimConfig config) {
        // 第七轮:每次新任务到来时,顺手清理已过 TTL 的终结任务,避免后台线程依赖。
        purgeExpired();
        if (tasks.size() >= maxTasks) {
            evictOldestTerminal();
        }

        String taskId = UUID.randomUUID().toString();
        String reportId = UUID.randomUUID().toString();
        SimulationTaskRecord record = new SimulationTaskRecord(taskId, reportId, config, clock.getAsLong());
        tasks.put(taskId, record);

        CompletableFuture.runAsync(() -> runTask(record), taskExecutor);
        return record;
    }

    public Optional<SimulationTaskRecord> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    int getTaskCount() {
        return tasks.size();
    }

    /**
     * 清理已终结(COMPLETED/FAILED/CANCELLED)且 completedAt 早于 (now - retentionMillis) 的任务。
     * 未完成的任务不会被清理,即使 submittedAt 久远也保留。
     */
    int purgeExpired() {
        long now = clock.getAsLong();
        long cutoff = now - retentionMillis;
        int removed = 0;
        for (var entry : new ArrayList<>(tasks.entrySet())) {
            SimulationTaskRecord record = entry.getValue();
            if (record == null || !record.isTerminal()) {
                continue;
            }
            long completedAt = record.getCompletedAtEpochMillis();
            if (completedAt > 0L && completedAt < cutoff) {
                tasks.remove(entry.getKey(), record);
                removed++;
            }
        }
        return removed;
    }

    /**
     * 任务表已达上限时,清理最旧的已终结任务为新任务腾位。
     * 若全部任务都未完成,不清理(保留运行中任务的可见性)。
     */
    private void evictOldestTerminal() {
        Optional<SimulationTaskRecord> oldest = tasks.values().stream()
                .filter(SimulationTaskRecord::isTerminal)
                .min(Comparator.comparingLong(SimulationTaskRecord::getCompletedAtEpochMillis));
        oldest.ifPresent(r -> tasks.remove(r.getTaskId(), r));
    }

    public ObjectNode toSnapshot(SimulationTaskRecord record) {
        ObjectNode data = reportMapper.createObjectNode();
        data.put("task_id", record.getTaskId());
        data.put("report_id", record.getReportId());
        data.put("status", record.getStatus());
        data.put("submitted_at_epoch_millis", record.getSubmittedAtEpochMillis());
        data.put("started_at_epoch_millis", record.getStartedAtEpochMillis());
        data.put("completed_at_epoch_millis", record.getCompletedAtEpochMillis());
        data.put("error_message", record.getErrorMessage());
        data.put("report_available", record.getReport() != null);
        if (record.getReport() != null) {
            data.set("summary", lightweightSummaryNode(record.getReport()));
        }
        return data;
    }

    private JsonNode lightweightSummaryNode(SimulationReport report) {
        JsonNode summary = reportMapper.valueToTree(report.getSummary());
        if (summary instanceof ObjectNode summaryObject) {
            summaryObject.remove("history");
            summaryObject.remove("timeline");
            summaryObject.remove("table_snapshots");
        }
        return summary;
    }

    public SseEmitter stream(String taskId) {
        SseEmitter emitter = new SseEmitter(10L * 60L * 1000L);
        Optional<SimulationTaskRecord> maybeRecord = get(taskId);
        if (maybeRecord.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("task not found"));
            } catch (IOException ignored) {
                // The client is already gone.
            }
            emitter.complete();
            return emitter;
        }

        SimulationTaskRecord record = maybeRecord.get();
        streamExecutor.submit(() -> emitUntilComplete(emitter, record));
        return emitter;
    }

    private void runTask(SimulationTaskRecord record) {
        try {
            record.markRunning();
            SimulationReport report = simulationRunService.run(record.getConfig(), record.getReportId());
            reportRepository.write(report);
            record.markCompleted(report);
        } catch (Exception e) {
            record.markFailed(e);
        }
    }

    private void emitUntilComplete(SseEmitter emitter, SimulationTaskRecord record) {
        try {
            while (true) {
                emitter.send(SseEmitter.event().name("status").data(toSnapshot(record)));
                if (record.isTerminal()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(500L);
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /** Test-only:从外部注入一个已存在的 record(便于复现 TTL 场景)。 */
    void registerForTest(SimulationTaskRecord record) {
        tasks.put(record.getTaskId(), record);
    }
}
