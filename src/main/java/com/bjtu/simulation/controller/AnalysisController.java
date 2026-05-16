package com.bjtu.simulation.controller;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.ScenarioBatchRunRequest;
import com.bjtu.simulation.service.ExternalAnalysisService;
import com.bjtu.simulation.service.ExternalAnalysisService.AnalysisResult;
import com.bjtu.simulation.service.ScenarioRunService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@CrossOrigin
@Validated
public class AnalysisController {

    private final ExternalAnalysisService externalAnalysisService;
    private final ScenarioRunService scenarioRunService;
    private final ObjectMapper mapper;

    @Autowired
    public AnalysisController(ExternalAnalysisService externalAnalysisService,
                              ScenarioRunService scenarioRunService) {
        this.externalAnalysisService = externalAnalysisService;
        this.scenarioRunService = scenarioRunService;
        this.mapper = SimulationApiSupport.createReportMapper();
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<JsonNode>> runForReport(@RequestBody(required = false) RunRequest request) {
        String reportId = request == null ? null : request.getReportId();
        if (reportId == null || reportId.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "report_id is required"));
        }
        AnalysisResult result = externalAnalysisService.runForReport(reportId);
        return wrap(result);
    }

    @PostMapping("/cross-scenario")
    public ResponseEntity<ApiResponse<JsonNode>> runForScenarios(@RequestBody(required = false) ScenarioBatchRunRequest request) {
        if (request == null || request.getScenarioIds() == null || request.getScenarioIds().size() < 2) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "scenario_ids must contain at least 2 ids"));
        }
        ObjectNode batchSummary = scenarioRunService.runScenarios(request);
        List<String> reportIds = collectReportIds(batchSummary);
        if (reportIds.size() < 2) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "scenario run produced fewer than 2 reports"));
        }
        AnalysisResult result = externalAnalysisService.runForReports(reportIds);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("scenarios", batchSummary);
        envelope.set("analysis", buildAnalysisNode(result));
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    private ResponseEntity<ApiResponse<JsonNode>> wrap(AnalysisResult result) {
        if (result.isAvailable()) {
            return ResponseEntity.ok(ApiResponse.success(result.getPayload()));
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("available", false);
        body.put("reason", result.getReason() == null ? "unknown" : result.getReason());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "analysis unavailable", body));
    }

    private JsonNode buildAnalysisNode(AnalysisResult result) {
        if (result.isAvailable()) {
            return result.getPayload();
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("available", false);
        node.put("reason", result.getReason() == null ? "unknown" : result.getReason());
        return node;
    }

    private List<String> collectReportIds(ObjectNode batchSummary) {
        List<String> ids = new ArrayList<>();
        JsonNode results = batchSummary == null ? null : batchSummary.path("results");
        if (results != null && results.isArray()) {
            for (JsonNode entry : results) {
                String id = entry.path("report_id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    public static class RunRequest {
        @JsonAlias("report_id")
        private String reportId;

        public String getReportId() { return reportId; }
        public void setReportId(String reportId) { this.reportId = reportId; }
    }
}
