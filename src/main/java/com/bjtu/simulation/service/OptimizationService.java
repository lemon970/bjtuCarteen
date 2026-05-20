package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
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
    private final OptimizationResultBuilder resultBuilder;

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
        this.resultBuilder = new OptimizationResultBuilder(reportMapper);
    }

    public ObjectNode optimize(OptimizationRequest request) {
        OptimizationRequest safeRequest = request == null ? new OptimizationRequest() : request;
        OptimizationResultBuilder.Objective objective = resultBuilder.parseObjective(safeRequest.getObjective());
        List<SimConfig> configs = explicitConfigs(safeRequest);

        ArrayNode results = reportMapper.createArrayNode();
        for (int i = 0; i < configs.size(); i++) {
            SimulationReport report = simulationRunService.run(configs.get(i), UUID.randomUUID().toString());
            results.add(resultBuilder.buildItemNode(i + 1, report, objective));
        }

        ObjectNode data = reportMapper.createObjectNode();
        data.put("mode", "batch_compare");
        data.put("deprecated_optimization", true);
        data.put("objective", objective.direction() + " " + objective.metric());
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

    private SimConfig cloneConfig(SimConfig config) {
        return reportMapper.convertValue(config == null ? new SimConfig() : config, SimConfig.class);
    }
}
