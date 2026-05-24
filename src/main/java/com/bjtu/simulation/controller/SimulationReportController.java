package com.bjtu.simulation.controller;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.service.SimulationReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationReportController {
    private final SimulationReportRepository reportRepository;
    private final ObjectMapper mapper;
    private final ReportResponseBuilder responseBuilder;

    @Autowired
    public SimulationReportController(SimulationReportRepository reportRepository) {
        this.mapper = SimulationApiSupport.createReportMapper();
        this.reportRepository = reportRepository;
        this.responseBuilder = new ReportResponseBuilder(this.mapper, reportRepository);
    }

    public SimulationReportController() {
        this(new SimulationReportRepository(SimulationApiSupport.createReportMapper()));
    }

    SimulationReportController(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reportRepository = new SimulationReportRepository(mapper);
        this.responseBuilder = new ReportResponseBuilder(mapper, reportRepository);
    }

    @GetMapping("/report/latest")
    public ResponseEntity<ApiResponse<JsonNode>> getLatestReport() {
        try {
            Optional<JsonNode> latest = reportRepository.readLatest();
            if (latest.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "latest report not found"));
            }
            return ResponseEntity.ok(ApiResponse.success(responseBuilder.responseReportNode(latest.get(), false)));
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
            JsonNode reportNode = responseBuilder.responseReportNode(report.get(), includeHistory);
            if (includeHistory) {
                responseBuilder.attachHistoryIfAvailable(reportNode, reportId);
            }
            return ResponseEntity.ok(ApiResponse.success(reportNode));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, e.getMessage()));
        }
    }

    @GetMapping(value = "/report/{id}/csv", produces = "text/csv")
    public ResponseEntity<String> exportReportCsv(@PathVariable("id") String reportId) {
        if (!reportRepository.isSafeReportId(reportId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("invalid report id");
        }

        try {
            Optional<JsonNode> report = reportRepository.readById(reportId);
            if (report.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("report not found");
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"simulation-" + reportId + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(responseBuilder.buildCsv(report.get(), reportRepository.readHistoryById(reportId).orElse(mapper.createArrayNode()), reportId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getMessage());
        }
    }
}
