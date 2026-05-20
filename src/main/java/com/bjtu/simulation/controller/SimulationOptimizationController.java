package com.bjtu.simulation.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.service.OptimizationService;
import com.bjtu.simulation.service.SimulationConfigNormalizer;
import com.bjtu.simulation.service.SimulationRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationOptimizationController {
    private final OptimizationService optimizationService;

    @Autowired
    public SimulationOptimizationController(OptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    public SimulationOptimizationController() {
        this(SimulationApiSupport.createReportMapper());
    }

    SimulationOptimizationController(ObjectMapper mapper) {
        this.optimizationService = new OptimizationService(
                new SimulationRunService(),
                new SimulationConfigNormalizer(),
                mapper);
    }

    @PostMapping("/optimize")
    public ResponseEntity<ApiResponse<JsonNode>> optimize(@Valid @RequestBody(required = false) OptimizationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(optimizationService.optimize(request)));
    }
}
