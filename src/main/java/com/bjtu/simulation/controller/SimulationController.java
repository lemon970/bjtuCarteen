package com.bjtu.simulation.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.service.SimulationReportRepository;
import com.bjtu.simulation.service.SimulationRunService;
import com.bjtu.simulation.service.SimulationTaskRecord;
import com.bjtu.simulation.service.SimulationTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationController {
    private final ObjectMapper mapper;
    private final SimulationRunService simulationRunService;
    private final SimulationReportRepository reportRepository;
    private final SimulationTaskService taskService;
    private final ReportResponseBuilder responseBuilder;

    public SimulationController() {
        this(SimulationApiSupport.createReportMapper());
    }

    @Autowired
    public SimulationController(SimulationRunService simulationRunService,
                                SimulationReportRepository reportRepository,
                                SimulationTaskService taskService) {
        this.mapper = SimulationApiSupport.createReportMapper();
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.taskService = taskService;
        this.responseBuilder = new ReportResponseBuilder(this.mapper, reportRepository);
    }

    SimulationController(ObjectMapper mapper) {
        this(mapper, new SimulationRunService(), new SimulationReportRepository(mapper));
    }

    SimulationController(ObjectMapper mapper,
                         SimulationRunService simulationRunService,
                         SimulationReportRepository reportRepository) {
        this.mapper = mapper;
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.taskService = new SimulationTaskService(simulationRunService, reportRepository, mapper);
        this.responseBuilder = new ReportResponseBuilder(mapper, reportRepository);
    }

    @PostMapping({"/run", "/start"})
    public ResponseEntity<ApiResponse<JsonNode>> startWithOptions(
            @Valid @RequestBody(required = false) SimConfig inputConfig,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory) {
        SimulationReport report = simulationRunService.run(inputConfig);
        reportRepository.write(report);
        JsonNode reportNode = responseBuilder.responseReportNode(mapper.valueToTree(report), includeHistory);
        return ResponseEntity.ok(ApiResponse.success(reportNode));
    }

    @PostMapping("/run/async")
    public ResponseEntity<ApiResponse<JsonNode>> submitAsync(@Valid @RequestBody(required = false) SimConfig inputConfig) {
        SimulationTaskRecord task = taskService.submit(inputConfig);
        return ResponseEntity.accepted().body(ApiResponse.success(taskService.toSnapshot(task)));
    }

    @GetMapping("/task/{id}/status")
    public ResponseEntity<ApiResponse<JsonNode>> getTaskStatus(@PathVariable("id") String taskId) {
        return taskService.get(taskId)
                .map(task -> ResponseEntity.ok(ApiResponse.<JsonNode>success(taskService.toSnapshot(task))))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<JsonNode>error(404, "task not found")));
    }

    @GetMapping("/task/{id}/stream")
    public SseEmitter streamTask(@PathVariable("id") String taskId) {
        return taskService.stream(taskId);
    }
}
