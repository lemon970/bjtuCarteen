package com.bjtu.simulation.controller;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.service.ExternalAnalysisService;
import com.bjtu.simulation.service.ExternalAnalysisService.AnalysisResult;
import com.bjtu.simulation.service.HistoricalDiagnosticsService;
import com.bjtu.simulation.service.HistoricalQualityScorer;
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
    private final HistoricalDiagnosticsService historicalDiagnosticsService;
    private final HistoricalQualityScorer historicalQualityScorer;
    private final ObjectMapper mapper;

    @Autowired
    public AnalysisController(ExternalAnalysisService externalAnalysisService,
                              HistoricalDiagnosticsService historicalDiagnosticsService,
                              HistoricalQualityScorer historicalQualityScorer) {
        this.externalAnalysisService = externalAnalysisService;
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
        boolean includeQuality = request != null && Boolean.TRUE.equals(request.getIncludeHistoricalQuality());
        return wrap(result, reportId, includeQuality);
    }

    private ResponseEntity<ApiResponse<JsonNode>> wrap(AnalysisResult result,
                                                        String reportId,
                                                        boolean includeQuality) {
        if (result.isAvailable()) {
            JsonNode payload = result.getPayload();
            JsonNode merged = mergeHistoricalQuality(payload, reportId, includeQuality);
            return ResponseEntity.ok(ApiResponse.success(merged));
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("available", false);
        body.put("reason", result.getReason() == null ? "unknown" : result.getReason());
        JsonNode merged = mergeHistoricalQuality(body, reportId, includeQuality);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "analysis unavailable", merged));
    }

    private JsonNode mergeHistoricalQuality(JsonNode payload, String reportId, boolean includeQuality) {
        if (!includeQuality) return payload;
        if (!(payload instanceof ObjectNode)) return payload;
        ObjectNode obj = (ObjectNode) payload;
        ObjectNode diagnostics = historicalDiagnosticsService.diagnose(reportId);
        ObjectNode quality = historicalQualityScorer.score(diagnostics, reportId);
        obj.set("historical_quality", quality);
        return obj;
    }

    public static class RunRequest {
        @JsonAlias("report_id")
        private String reportId;

        @JsonAlias("include_historical_quality")
        private Boolean includeHistoricalQuality;

        public String getReportId() { return reportId; }
        public void setReportId(String reportId) { this.reportId = reportId; }

        public Boolean getIncludeHistoricalQuality() { return includeHistoricalQuality; }
        public void setIncludeHistoricalQuality(Boolean includeHistoricalQuality) {
            this.includeHistoricalQuality = includeHistoricalQuality;
        }
    }
}
