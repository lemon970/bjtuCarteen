package com.bjtu.simulation.controller;

import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.service.OptimizationTaskRecord;
import com.bjtu.simulation.service.OptimizationTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;

/**
 * 异步 batch optimize 接口。新 controller,旧 {@link SimulationOptimizationController}
 * 字面 0 改动以保留同步路径行为。
 *
 * <p>本期(PR-1)端点:
 * <ul>
 *   <li>POST /api/simulation/optimize/async — 立即返回 batch_task_id(202 Accepted)</li>
 *   <li>GET  /api/simulation/optimize/task/{id} — 状态轮询</li>
 *   <li>GET  /api/simulation/optimize/task/{id}/result — 完整结果(409 Conflict 直到完成)</li>
 * </ul>
 *
 * <p>本期 **不** 实现:SSE stream / DELETE cancel / 强制 timeout(留 PR-3)。
 */
@RestController
@RequestMapping("/api/simulation")
@CrossOrigin
@Validated
public class SimulationOptimizationAsyncController {

    private final OptimizationTaskService taskService;

    @Autowired
    public SimulationOptimizationAsyncController(OptimizationTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/optimize/async")
    public ResponseEntity<ApiResponse<JsonNode>> submit(
            @Valid @RequestBody(required = false) OptimizationRequest request) {
        try {
            OptimizationTaskRecord record = taskService.submit(request);
            ObjectNode payload = taskService.toStatusSnapshot(record);
            return ResponseEntity.accepted().body(ApiResponse.success(payload));
        } catch (RejectedExecutionException ree) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "too many running optimize batches, retry later"));
        }
    }

    @GetMapping("/optimize/task/{id}")
    public ResponseEntity<ApiResponse<JsonNode>> getStatus(@PathVariable("id") String batchTaskId) {
        return taskService.get(batchTaskId)
                .map(record -> ResponseEntity.ok(
                        ApiResponse.<JsonNode>success(taskService.toStatusSnapshot(record))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<JsonNode>error(404, "batch task not found")));
    }

    @GetMapping("/optimize/task/{id}/result")
    public ResponseEntity<ApiResponse<JsonNode>> getResult(@PathVariable("id") String batchTaskId) {
        var maybeRecord = taskService.get(batchTaskId);
        if (maybeRecord.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "batch task not found"));
        }
        OptimizationTaskRecord record = maybeRecord.get();
        if (!record.isResultAvailable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "task result not ready"));
        }
        return ResponseEntity.ok(ApiResponse.success(taskService.toResultSnapshot(record)));
    }
}
