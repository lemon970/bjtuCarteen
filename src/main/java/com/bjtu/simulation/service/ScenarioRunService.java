package com.bjtu.simulation.service;

import java.util.List;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.ScenarioBatchRunRequest;
import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ScenarioRunService {
    private final ScenarioPresetCatalog catalog;
    private final SimulationRunService simulationRunService;
    private final SimulationReportRepository reportRepository;
    private final ObjectMapper mapper;

    @Autowired
    public ScenarioRunService(ScenarioPresetCatalog catalog,
                              SimulationRunService simulationRunService,
                              SimulationReportRepository reportRepository) {
        this(catalog, simulationRunService, reportRepository, AppBeansConfig.createReportObjectMapper());
    }

    public ScenarioRunService(ScenarioPresetCatalog catalog,
                              SimulationRunService simulationRunService,
                              SimulationReportRepository reportRepository,
                              ObjectMapper mapper) {
        this.catalog = catalog;
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.mapper = mapper;
    }

    public ObjectNode listScenarios() {
        ObjectNode data = mapper.createObjectNode();
        data.put("count", catalog.list().size());
        data.set("scenarios", mapper.valueToTree(catalog.list()));
        return data;
    }

    public ObjectNode runScenarios(ScenarioBatchRunRequest request) {
        List<String> scenarioIds = request == null ? List.of() : request.getScenarioIds();
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            scenarioIds = catalog.list().stream().map(ScenarioPreset::id).toList();
        }

        ArrayNode results = mapper.createArrayNode();
        for (String scenarioId : scenarioIds) {
            ScenarioPreset preset = catalog.find(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown scenario id: " + scenarioId));
            SimConfig config = cloneConfig(preset.config());
            applyOverrides(config, request == null ? null : request.getOverrides());

            String reportId = preset.id() + "-" + UUID.randomUUID();
            SimulationReport report = simulationRunService.run(config, reportId);
            reportRepository.write(report);

            ObjectNode item = mapper.createObjectNode();
            item.put("scenario_id", preset.id());
            item.put("scenario_name", preset.name());
            item.put("purpose", preset.purpose());
            item.set("expected_metrics", mapper.valueToTree(preset.expectedMetrics()));
            item.put("report_id", report.getReportId());
            item.set("config", mapper.valueToTree(report.getConfig()));
            item.set("summary", mapper.valueToTree(report.getSummary()));
            results.add(item);
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("scenario_count", results.size());
        data.set("results", results);
        data.set("comparison_summary", buildComparison(results));
        return data;
    }

    private SimConfig cloneConfig(SimConfig config) {
        return mapper.convertValue(config, SimConfig.class);
    }

    private void applyOverrides(SimConfig config, JsonNode overrides) {
        if (overrides == null || overrides.isNull() || overrides.isMissingNode() || !overrides.isObject()) {
            return;
        }
        try {
            JsonNode base = mapper.valueToTree(config);
            deepMerge((ObjectNode) base, overrides);
            SimConfig merged = mapper.treeToValue(base, SimConfig.class);
            copyInto(config, merged);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid scenario overrides", e);
        }
    }

    private void deepMerge(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (existing instanceof ObjectNode existingObject && incoming != null && incoming.isObject()) {
                deepMerge(existingObject, incoming);
            } else {
                target.set(entry.getKey(), incoming);
            }
        });
    }

    private void copyInto(SimConfig target, SimConfig source) {
        target.setSimulationName(source.getSimulationName());
        target.setDuration(source.getDuration());
        target.setArrivalRate(source.getArrivalRate());
        target.setQueueLimit(source.getQueueLimit());
        target.setPackProbability(source.getPackProbability());
        target.setGroupArrivalProb(source.getGroupArrivalProb());
        target.setPartySize(source.getPartySize());
        target.setWalkTimeMean(source.getWalkTimeMean());
        target.setCongestionPenalty(source.getCongestionPenalty());
        target.setSeed(source.getSeed());
        target.setArrivalDist(source.getArrivalDist());
        target.setNormalServiceDist(source.getNormalServiceDist());
        target.setWindowServiceDist(source.getWindowServiceDist());
        target.setDiningTimeDist(source.getDiningTimeDist());
        target.setBaseConfig(source.getBaseConfig());
        target.setWeatherConfig(source.getWeatherConfig());
        target.setRandomBounds(source.getRandomBounds());
        target.setPeakConfig(source.getPeakConfig());
    }

    private ObjectNode buildComparison(ArrayNode results) {
        ObjectNode comparison = mapper.createObjectNode();
        String bestWaitId = "";
        String highestSeatId = "";
        double bestWait = Double.MAX_VALUE;
        double highestSeat = -1.0;
        for (JsonNode result : results) {
            JsonNode summary = result.path("summary");
            double typicalWait = summary.path("typical_wait_time_minutes").asDouble(Double.MAX_VALUE);
            double seatRate = summary.path("seat_utilization_rate").asDouble(-1.0);
            if (typicalWait < bestWait) {
                bestWait = typicalWait;
                bestWaitId = result.path("scenario_id").asText("");
            }
            if (seatRate > highestSeat) {
                highestSeat = seatRate;
                highestSeatId = result.path("scenario_id").asText("");
            }
        }
        comparison.put("best_typical_wait_scenario_id", bestWaitId);
        comparison.put("best_typical_wait_minutes", SimulationMath.round3(bestWait == Double.MAX_VALUE ? 0.0 : bestWait));
        comparison.put("highest_seat_utilization_scenario_id", highestSeatId);
        comparison.put("highest_seat_utilization_rate", SimulationMath.round3(Math.max(0.0, highestSeat)));
        return comparison;
    }
}
