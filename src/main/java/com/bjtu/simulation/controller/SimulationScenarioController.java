package com.bjtu.simulation.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.ScenarioBatchRunRequest;
import com.bjtu.simulation.service.ScenarioRunService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationScenarioController {
    private final ScenarioRunService scenarioRunService;

    @Autowired
    public SimulationScenarioController(ScenarioRunService scenarioRunService) {
        this.scenarioRunService = scenarioRunService;
    }

    @GetMapping("/scenarios")
    public ResponseEntity<ApiResponse<JsonNode>> listScenarios() {
        return ResponseEntity.ok(ApiResponse.success(scenarioRunService.listScenarios()));
    }

    @PostMapping("/scenarios/run")
    public ResponseEntity<ApiResponse<JsonNode>> runScenarios(
            @RequestBody(required = false) ScenarioBatchRunRequest request,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory) {
        return ResponseEntity.ok(ApiResponse.success(scenarioRunService.runScenarios(request, includeHistory)));
    }
}
