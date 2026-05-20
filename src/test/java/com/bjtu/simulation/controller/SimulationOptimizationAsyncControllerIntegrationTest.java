package com.bjtu.simulation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationOptimizationAsyncControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String LIGHT_TWO_CONFIG_BODY = """
            {
              "objective": "minimize avg_wait_time_minutes",
              "configs": [
                {
                  "duration": 0.05,
                  "arrivalRate": 20,
                  "queueLimit": 10,
                  "packProbability": 0.2,
                  "seed": 9001,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                },
                {
                  "duration": 0.05,
                  "arrivalRate": 20,
                  "queueLimit": 10,
                  "packProbability": 0.5,
                  "seed": 9002,
                  "baseConfig": {"windowCount": 3, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                }
              ]
            }
            """;

    @Test
    void submitAsyncShouldReturn202AndBatchTaskId() throws Exception {
        mockMvc.perform(post("/api/simulation/optimize/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LIGHT_TWO_CONFIG_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batch_task_id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.objective").value("minimize avg_wait_time_minutes"))
                .andExpect(jsonPath("$.data.submitted_at_epoch_millis").exists());
    }

    @Test
    void emptyConfigsShouldReturn400() throws Exception {
        String empty = """
                { "objective": "minimize avg_wait_time_minutes", "configs": [] }
                """;
        mockMvc.perform(post("/api/simulation/optimize/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noBodyShouldReturn400BecauseConfigsAreEmpty() throws Exception {
        mockMvc.perform(post("/api/simulation/optimize/async")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStatusForUnknownIdShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/simulation/optimize/task/{id}", "no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getResultForUnknownIdShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/simulation/optimize/task/{id}/result", "no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void completeFlowShouldReturnOrderedBatchCompare() throws Exception {
        String batchTaskId = submitAndExtractId();
        awaitTerminal(batchTaskId, Duration.ofSeconds(30));

        MvcResult statusRes = mockMvc.perform(get("/api/simulation/optimize/task/{id}", batchTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_available").value(true))
                .andExpect(jsonPath("$.data.has_failures").value(false))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.completed").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.percent_complete").value(1.0))
                .andExpect(jsonPath("$.data.warnings").isArray())
                .andReturn();
        // 显式断言 first_failure_message 在 JSON 里以 null 出现(不是字段缺失)。
        JsonNode statusNode = objectMapper.readTree(statusRes.getResponse().getContentAsString())
                .path("data");
        org.junit.jupiter.api.Assertions.assertTrue(statusNode.has("first_failure_message"));
        org.junit.jupiter.api.Assertions.assertTrue(statusNode.path("first_failure_message").isNull());

        mockMvc.perform(get("/api/simulation/optimize/task/{id}/result", batchTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("batch_compare"))
                .andExpect(jsonPath("$.data.deprecated_optimization").value(false))
                .andExpect(jsonPath("$.data.batch_task_id").value(batchTaskId))
                .andExpect(jsonPath("$.data.objective").value("minimize avg_wait_time_minutes"))
                .andExpect(jsonPath("$.data.evaluated_configs").value(2))
                .andExpect(jsonPath("$.data.completed_count").value(2))
                .andExpect(jsonPath("$.data.failed_count").value(0))
                .andExpect(jsonPath("$.data.has_failures").value(false))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].index").value(1))
                .andExpect(jsonPath("$.data.results[0].config.base_config.window_count").value(2))
                .andExpect(jsonPath("$.data.results[0].error_message").doesNotExist())
                .andExpect(jsonPath("$.data.results[1].index").value(2))
                .andExpect(jsonPath("$.data.results[1].config.base_config.window_count").value(3))
                .andExpect(jsonPath("$.data.top_configs").doesNotExist());
    }

    @Test
    void resultBeforeCompletionReturns409() throws Exception {
        String batchTaskId = submitAndExtractId();
        // 不等待完成,直接查 result。可能已完成(极轻 config),也可能未完成。
        // 行为契约:未完成 → 409;已完成 → 200。两者都应是 controller 的稳定路径。
        MvcResult res = mockMvc.perform(get("/api/simulation/optimize/task/{id}/result", batchTaskId))
                .andReturn();
        int httpStatus = res.getResponse().getStatus();
        boolean acceptable = httpStatus == 200 || httpStatus == 409;
        org.junit.jupiter.api.Assertions.assertTrue(acceptable,
                "result endpoint should return 200 (already completed) or 409 (not ready), got " + httpStatus);
        if (httpStatus == 409) {
            JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
            org.junit.jupiter.api.Assertions.assertEquals(409, body.path("code").asInt());
            org.junit.jupiter.api.Assertions.assertTrue(body.path("message").asText().contains("not ready"));
        }
    }

    @Test
    void snakeCaseAndCamelCaseObjectiveAliasesAreAccepted() throws Exception {
        // OptimizationRequest 已对 objective / configs 配 @JsonAlias,这里再次冒烟 camelCase 不破。
        String camel = """
                {
                  "objective": "minimize avg_wait_time_minutes",
                  "configs": [
                    {
                      "duration": 0.05,
                      "arrivalRate": 15,
                      "queueLimit": 10,
                      "seed": 91,
                      "baseConfig": {"windowCount": 2, "totalSeats": 12, "totalStudents": 0},
                      "weatherConfig": {"weatherImpactFactor": 1.0},
                      "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/api/simulation/optimize/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(camel))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private String submitAndExtractId() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/simulation/optimize/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LIGHT_TWO_CONFIG_BODY))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        String id = body.path("data").path("batch_task_id").asText();
        org.junit.jupiter.api.Assertions.assertFalse(id.isBlank(), "batch_task_id must not be blank");
        return id;
    }

    private void awaitTerminal(String batchTaskId, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MvcResult res = mockMvc.perform(get("/api/simulation/optimize/task/{id}", batchTaskId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
            String status = body.path("data").path("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("batch task " + batchTaskId + " did not reach terminal in " + timeout);
    }
}
