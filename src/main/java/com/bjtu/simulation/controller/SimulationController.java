package com.bjtu.simulation.controller;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.service.OptimizationService;
import com.bjtu.simulation.service.SimulationReportRepository;
import com.bjtu.simulation.service.SimulationRunService;
import com.bjtu.simulation.service.SimulationTaskRecord;
import com.bjtu.simulation.service.SimulationTaskService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationController {
    private final ObjectMapper reportMapper;
    private final SimulationRunService simulationRunService;
    private final SimulationReportRepository reportRepository;
    private final SimulationTaskService taskService;
    private final OptimizationService optimizationService;

    public SimulationController() {
        this(createReportMapper());
    }

    SimulationController(ObjectMapper reportMapper) {
        this(reportMapper, new SimulationRunService(), new SimulationReportRepository(reportMapper));
    }

    SimulationController(ObjectMapper reportMapper,
                         SimulationRunService simulationRunService,
                         SimulationReportRepository reportRepository) {
        this.reportMapper = reportMapper;
        this.simulationRunService = simulationRunService;
        this.reportRepository = reportRepository;
        this.taskService = new SimulationTaskService(simulationRunService, reportRepository, reportMapper);
        this.optimizationService = new OptimizationService(
                simulationRunService,
                new com.bjtu.simulation.service.SimulationConfigNormalizer(),
                reportMapper);
    }

    @PostMapping({"/run", "/start"})
    public ResponseEntity<ApiResponse<JsonNode>> startWithOptions(
            @Valid @RequestBody(required = false) SimConfig inputConfig,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory) {
        SimulationReport report = simulationRunService.run(inputConfig);
        reportRepository.write(report);
        JsonNode reportNode = responseReportNode(reportMapper.valueToTree(report), includeHistory);
        return ResponseEntity.ok(ApiResponse.success(reportNode));
    }

    public ResponseEntity<ApiResponse<JsonNode>> start(SimConfig inputConfig) {
        return startWithOptions(inputConfig, false);
    }

    @PostMapping("/run/async")
    public ResponseEntity<ApiResponse<JsonNode>> submitAsync(@Valid @RequestBody(required = false) SimConfig inputConfig) {
        SimulationTaskRecord task = taskService.submit(inputConfig);
        return ResponseEntity.accepted().body(ApiResponse.success(taskService.toSnapshot(task)));
    }

    @GetMapping("/task/{id}/status")
    public ResponseEntity<ApiResponse<JsonNode>> getTaskStatus(@PathVariable("id") String taskId) {
        Optional<SimulationTaskRecord> task = taskService.get(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "task not found"));
        }
        return ResponseEntity.ok(ApiResponse.success(taskService.toSnapshot(task.get())));
    }

    @GetMapping("/task/{id}/stream")
    public SseEmitter streamTask(@PathVariable("id") String taskId) {
        return taskService.stream(taskId);
    }

    @PostMapping("/optimize")
    public ResponseEntity<ApiResponse<JsonNode>> optimize(@Valid @RequestBody(required = false) OptimizationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(optimizationService.optimize(request)));
    }

    @GetMapping("/report/latest")
    public ResponseEntity<ApiResponse<JsonNode>> getLatestReport() {
        try {
            Optional<JsonNode> latest = reportRepository.readLatest();
            if (latest.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "latest report not found"));
            }
            return ResponseEntity.ok(ApiResponse.success(responseReportNode(latest.get(), false)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/report/list")
    public ResponseEntity<ApiResponse<JsonNode>> getReportList() {
        try {
            return ResponseEntity.ok(ApiResponse.success(reportRepository.listReports()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping("/report/{id}")
    public ResponseEntity<ApiResponse<JsonNode>> getReportByIdWithOptions(
            @PathVariable("id") String reportId,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory) {
        if (!reportRepository.isSafeReportId(reportId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "invalid report id"));
        }

        try {
            Optional<JsonNode> report = reportRepository.readById(reportId);
            if (report.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "report not found"));
            }
            JsonNode reportNode = responseReportNode(report.get(), includeHistory);
            if (includeHistory) {
                attachHistoryIfAvailable(reportNode, reportId);
            }
            return ResponseEntity.ok(ApiResponse.success(reportNode));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    public ResponseEntity<ApiResponse<JsonNode>> getReportById(String reportId) {
        return getReportByIdWithOptions(reportId, false);
    }

    @GetMapping("/report/{id}/timeline")
    public ResponseEntity<ApiResponse<JsonNode>> getReportTimelinePage(
            @PathVariable("id") String reportId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "500") int pageSize) {
        return getReportSummaryArrayPage(reportId, "timeline", page, pageSize);
    }

    @GetMapping("/report/{id}/history")
    public ResponseEntity<ApiResponse<JsonNode>> getReportHistoryPage(
            @PathVariable("id") String reportId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "500") int pageSize) {
        return getReportSummaryArrayPage(reportId, "history", page, pageSize);
    }

    private ResponseEntity<ApiResponse<JsonNode>> getReportSummaryArrayPage(String reportId,
                                                                           String collectionName,
                                                                           int page,
                                                                           int pageSize) {
        if (!reportRepository.isSafeReportId(reportId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "invalid report id"));
        }
        if (page < 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "page must be >= 1"));
        }
        if (pageSize < 1 || pageSize > 5000) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "page_size must be in [1, 5000]"));
        }

        try {
            Optional<JsonNode> report = reportRepository.readById(reportId);
            if (report.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "report not found"));
            }

            JsonNode source = report.get().path("summary").path(collectionName);
            if (!source.isArray() && "history".equals(collectionName)) {
                Optional<JsonNode> history = reportRepository.readHistoryById(reportId);
                if (history.isPresent()) {
                    source = history.get();
                }
            }
            if (!source.isArray()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, collectionName + " not found"));
            }

            return ResponseEntity.ok(ApiResponse.success(buildArrayPage(reportId, collectionName, source, page, pageSize)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    private ObjectNode buildArrayPage(String reportId, String collectionName, JsonNode source, int page, int pageSize) {
        int totalItems = source.size();
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / pageSize);
        int fromIndex = Math.min((page - 1) * pageSize, totalItems);
        int toIndex = Math.min(fromIndex + pageSize, totalItems);

        ArrayNode items = reportMapper.createArrayNode();
        for (int i = fromIndex; i < toIndex; i++) {
            items.add(source.get(i));
        }

        ObjectNode data = reportMapper.createObjectNode();
        data.put("report_id", reportId);
        data.put("collection", collectionName);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("total_items", totalItems);
        data.put("total_pages", totalPages);
        data.put("has_next", page < totalPages);
        data.put("has_previous", page > 1 && totalPages > 0);
        data.set("items", items);
        return data;
    }

    private JsonNode responseReportNode(JsonNode reportNode, boolean includeHistory) {
        JsonNode copy = reportNode.deepCopy();
        if (!includeHistory && copy instanceof ObjectNode reportObject) {
            JsonNode summary = reportObject.path("summary");
            if (summary instanceof ObjectNode summaryObject) {
                summaryObject.remove("history");
                stripTimelineTableSnapshots(summaryObject);
            }
        }
        return copy;
    }

    private void attachHistoryIfAvailable(JsonNode reportNode, String reportId) {
        if (!(reportNode instanceof ObjectNode reportObject)) {
            return;
        }
        JsonNode summary = reportObject.path("summary");
        if (!(summary instanceof ObjectNode summaryObject) || summaryObject.path("history").isArray()) {
            return;
        }
        Optional<JsonNode> history = reportRepository.readHistoryById(reportId);
        history.ifPresent(node -> {
            if (node.isArray()) {
                summaryObject.set("history", node);
            }
        });
    }

    private void stripTimelineTableSnapshots(ObjectNode summaryObject) {
        JsonNode timeline = summaryObject.path("timeline");
        if (!timeline.isArray()) {
            return;
        }
        for (JsonNode item : timeline) {
            if (item instanceof ObjectNode itemObject) {
                itemObject.remove("table_snapshots");
            }
        }
    }

    private static ObjectMapper createReportMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }
}
