package com.bjtu.simulation.controller;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.ScenarioBatchRunRequest;
import com.bjtu.simulation.service.ExternalAnalysisService;
import com.bjtu.simulation.service.ExternalAnalysisService.AnalysisResult;
import com.bjtu.simulation.service.HistoricalDiagnosticsService;
import com.bjtu.simulation.service.HistoricalQualityScorer;
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
    private final HistoricalDiagnosticsService historicalDiagnosticsService;
    private final HistoricalQualityScorer historicalQualityScorer;
    private final ObjectMapper mapper;

    @Autowired
    public AnalysisController(ExternalAnalysisService externalAnalysisService,
                              ScenarioRunService scenarioRunService,
                              HistoricalDiagnosticsService historicalDiagnosticsService,
                              HistoricalQualityScorer historicalQualityScorer) {
        this.externalAnalysisService = externalAnalysisService;
        this.scenarioRunService = scenarioRunService;
        this.historicalDiagnosticsService = historicalDiagnosticsService;
        this.historicalQualityScorer = historicalQualityScorer;
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
        boolean includeDiagnostics = request != null && Boolean.TRUE.equals(request.getIncludeHistoricalDiagnostics());
        boolean includeQuality = request != null && Boolean.TRUE.equals(request.getIncludeHistoricalQuality());
        return wrap(result, reportId, includeDiagnostics, includeQuality);
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

    private ResponseEntity<ApiResponse<JsonNode>> wrap(AnalysisResult result,
                                                        String reportId,
                                                        boolean includeDiagnostics,
                                                        boolean includeQuality) {
        if (result.isAvailable()) {
            JsonNode payload = result.getPayload();
            JsonNode merged = mergeHistoricalSubtrees(payload, reportId, includeDiagnostics, includeQuality);
            return ResponseEntity.ok(ApiResponse.success(merged));
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("available", false);
        body.put("reason", result.getReason() == null ? "unknown" : result.getReason());
        JsonNode merged = mergeHistoricalSubtrees(body, reportId, includeDiagnostics, includeQuality);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "analysis unavailable", merged));
    }

    /**
     * 关键拓扑:两个 flag 都 true 时,只调一次 diagnose,把同一个 ObjectNode
     * 既合到响应又喂给 scorer。这是 RFC-003 §1.4 推荐方案 (A) 的实现:diagnostics
     * 子树和 quality.basis 字段同源,杜绝两边数字不一致。
     *
     * 默认全关:都不出 historical_diagnostics 也不出 historical_quality。
     * 仅 diagnostics:出 historical_diagnostics,不出 historical_quality。
     * 仅 quality:不出 historical_diagnostics,出 historical_quality(scorer 内部使用 diagnostics)。
     * 都开:同时出两者(同一份 diagnostics)。
     */
    private JsonNode mergeHistoricalSubtrees(JsonNode payload, String reportId,
                                             boolean includeDiagnostics, boolean includeQuality) {
        if (!includeDiagnostics && !includeQuality) return payload;
        if (!(payload instanceof ObjectNode)) return payload;
        ObjectNode obj = (ObjectNode) payload;
        ObjectNode diagnostics = historicalDiagnosticsService.diagnose(reportId);
        if (includeDiagnostics) obj.set("historical_diagnostics", diagnostics);
        if (includeQuality) {
            ObjectNode quality = historicalQualityScorer.score(diagnostics, reportId);
            obj.set("historical_quality", quality);
        }
        return obj;
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

        @JsonAlias("include_historical_diagnostics")
        private Boolean includeHistoricalDiagnostics;

        @JsonAlias("include_historical_quality")
        private Boolean includeHistoricalQuality;

        public String getReportId() { return reportId; }
        public void setReportId(String reportId) { this.reportId = reportId; }

        public Boolean getIncludeHistoricalDiagnostics() { return includeHistoricalDiagnostics; }
        public void setIncludeHistoricalDiagnostics(Boolean includeHistoricalDiagnostics) {
            this.includeHistoricalDiagnostics = includeHistoricalDiagnostics;
        }

        public Boolean getIncludeHistoricalQuality() { return includeHistoricalQuality; }
        public void setIncludeHistoricalQuality(Boolean includeHistoricalQuality) {
            this.includeHistoricalQuality = includeHistoricalQuality;
        }
    }
}
