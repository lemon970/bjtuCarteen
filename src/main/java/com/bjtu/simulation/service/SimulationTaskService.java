package com.bjtu.simulation.service;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final SimulationRunService simulationRunService;
    private final SimulationReportRepository reportRepository;
    private final ObjectMapper reportMapper;
    private final ConcurrentHashMap<String, SimulationTaskRecord> tasks = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    @Autowired
    public SimulationTaskService(SimulationRunService simulationRunService,
                                 SimulationReportRepository reportRepository) {
        this(simulationRunService, reportRepository, AppBeansConfig.createReportObjectMapper());
    }

    public SimulationTaskService(SimulationRunService simulationRunService,
                                 SimulationReportRepository reportRepository,
                                 ObjectMapper reportMapper) {
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.reportMapper = reportMapper;
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        streamExecutor.shutdownNow();
    }

    public SimulationTaskRecord submit(SimConfig config) {
        String taskId = UUID.randomUUID().toString();
        String reportId = UUID.randomUUID().toString();
        SimulationTaskRecord record = new SimulationTaskRecord(taskId, reportId, config, System.currentTimeMillis());
        tasks.put(taskId, record);

        CompletableFuture.runAsync(() -> runTask(record), taskExecutor);
        return record;
    }

    public Optional<SimulationTaskRecord> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
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
}
