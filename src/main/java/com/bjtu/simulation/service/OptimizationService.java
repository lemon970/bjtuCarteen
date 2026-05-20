package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OptimizationService {
    private final SimulationRunService simulationRunService;
    private final SimulationConfigNormalizer configNormalizer;
    private final ObjectMapper reportMapper;

    @Autowired
    public OptimizationService(SimulationRunService simulationRunService,
                               SimulationConfigNormalizer configNormalizer) {
        this(simulationRunService, configNormalizer, AppBeansConfig.createReportObjectMapper());
    }

    public OptimizationService(SimulationRunService simulationRunService,
                               SimulationConfigNormalizer configNormalizer,
                               ObjectMapper reportMapper) {
        this.simulationRunService = simulationRunService;
        this.configNormalizer = configNormalizer;
        this.reportMapper = reportMapper;
    }

    public ObjectNode optimize(OptimizationRequest request) {
        OptimizationRequest safeRequest = request == null ? new OptimizationRequest() : request;
        Objective objective = parseObjective(safeRequest.getObjective());
        List<SimConfig> configs = explicitConfigs(safeRequest);

        ArrayNode results = reportMapper.createArrayNode();
        for (int i = 0; i < configs.size(); i++) {
            SimulationReport report = simulationRunService.run(configs.get(i), UUID.randomUUID().toString());
            results.add(toResultNode(i + 1, report, objective));
        }

        ObjectNode data = reportMapper.createObjectNode();
        data.put("mode", "batch_compare");
        data.put("deprecated_optimization", true);
        data.put("objective", objective.direction + " " + objective.metric);
        data.put("evaluated_configs", configs.size());
        data.set("results", results);
        return data;
    }

    private List<SimConfig> explicitConfigs(OptimizationRequest request) {
        List<SimConfig> source = request.getConfigs();
        if (source == null || source.isEmpty()) {
            return List.of(configNormalizer.normalize(cloneConfig(request.getConfig())));
        }

        int limit = Math.min(source.size(), Math.max(1, request.getMaxCandidates()));
        List<SimConfig> configs = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            configs.add(configNormalizer.normalize(cloneConfig(source.get(i))));
        }
        return configs;
    }

    private ObjectNode toResultNode(int index, SimulationReport report, Objective objective) {
        SimulationSummary summary = report.getSummary();
        ObjectNode node = reportMapper.createObjectNode();
        node.put("index", index);
        node.put("report_id", report.getReportId());
        node.set("config", reportMapper.valueToTree(report.getConfig()));
        node.set("summary", lightweightSummaryNode(summary));
        node.put("objective_value", metricValue(summary, objective.metric));
        return node;
    }

    private JsonNode lightweightSummaryNode(SimulationSummary summary) {
        JsonNode node = reportMapper.valueToTree(summary);
        if (node instanceof ObjectNode summaryObject) {
            summaryObject.remove("history");
            summaryObject.remove("timeline");
            summaryObject.remove("table_snapshots");
        }
        return node;
    }

    private Objective parseObjective(String objective) {
        String raw = objective == null || objective.isBlank() ? "minimize typical_wait_time_minutes" : objective.trim().toLowerCase();
        String[] parts = raw.split("\\s+");
        String direction = parts.length > 0 && "maximize".equals(parts[0]) ? "maximize" : "minimize";
        String metric = parts.length > 1 ? parts[1] : "typical_wait_time_minutes";
        return new Objective(direction, metric);
    }

    private double metricValue(SimulationSummary summary, String metric) {
        return switch (normalizeField(metric)) {
            case "served_count" -> summary.getServedCount();
            case "abandoned_count" -> summary.getAbandonedCount();
            case "max_queue_size" -> summary.getMaxQueueSize();
            case "max_total_queue_size" -> summary.getMaxTotalQueueSize();
            case "seat_utilization_rate" -> summary.getSeatUtilizationRate();
            case "takeaway_rate" -> summary.getTakeawayRate();
            case "raw_avg_wait_time_minutes", "avg_wait_time_minutes" -> summary.getAvgWaitTimeMinutes();
            case "steady_avg_wait_time_minutes" -> summary.getSteadyAvgWaitTimeMinutes();
            case "typical_wait_time_minutes" -> summary.getTypicalWaitTimeMinutes();
            case "median_wait_time_minutes" -> summary.getMedianWaitTimeMinutes();
            case "p75_wait_time_minutes" -> summary.getP75WaitTimeMinutes();
            case "p90_wait_time_minutes" -> summary.getP90WaitTimeMinutes();
            case "long_wait_rate" -> summary.getLongWaitRate();
            case "zero_wait_rate" -> summary.getZeroWaitRate();
            case "edge_wait_sample_rate" -> summary.getEdgeWaitSampleRate();
            default -> summary.getAvgWaitTimeMinutes();
        };
    }

    private String normalizeField(String field) {
        return field == null ? "" : field.trim().toLowerCase();
    }

    private SimConfig cloneConfig(SimConfig config) {
        return reportMapper.convertValue(config == null ? new SimConfig() : config, SimConfig.class);
    }

    private record Objective(String direction, String metric) {
    }
}
