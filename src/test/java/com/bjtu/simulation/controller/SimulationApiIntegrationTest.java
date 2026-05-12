package com.bjtu.simulation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

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
class SimulationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void staticFrontendEntryShouldBeServed() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/frontend/")));

        mockMvc.perform(get("/frontend/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/frontend/index.html"));

        mockMvc.perform(get("/frontend/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Canteen Simulation Console")));
    }

    @Test
    void invalidPackProbabilityShouldReturn400Envelope() throws Exception {
        String json = """
                {
                  "duration": 1.0,
                  "arrivalRate": 60,
                  "packProbability": 2.0,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                }
                """;

        mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void validRunShouldReturnWrappedReport() throws Exception {
        String json = """
                {
                  "duration": 0.2,
                  "arrivalRate": 30,
                  "queueLimit": 10,
                  "packProbability": 0.2,
                  "seed": 123,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                }
                """;

        mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report_version").value("1.8.0"))
                .andExpect(jsonPath("$.data.summary.arrived_count").exists())
                .andExpect(jsonPath("$.data.summary.pending_seat_decision_count").exists())
                .andExpect(jsonPath("$.data.summary.window_types").isArray())
                .andExpect(jsonPath("$.data.summary.takeaway_window_count").exists())
                .andExpect(jsonPath("$.data.summary.takeaway_window_served_count").exists())
                .andExpect(jsonPath("$.data.summary.takeaway_rate").exists())
                .andExpect(jsonPath("$.data.summary.takeaway_window_ratio").exists())
                .andExpect(jsonPath("$.data.summary.takeaway_window_served_rate").exists())
                .andExpect(jsonPath("$.data.summary.timeline").isArray())
                .andExpect(jsonPath("$.data.summary.history").doesNotExist())
                .andExpect(jsonPath("$.data.summary.timeline[0].time_seconds").exists())
                .andExpect(jsonPath("$.data.summary.timeline[0].window_queue_sizes").isArray())
                .andExpect(jsonPath("$.data.summary.timeline[0].window_types").isArray())
                .andExpect(jsonPath("$.data.summary.timeline[0].dining_student_count").exists())
                .andExpect(jsonPath("$.data.summary.timeline[0].queueing_student_count").exists())
                .andExpect(jsonPath("$.data.summary.timeline[0].seat_utilization_rate").exists())
                .andExpect(jsonPath("$.data.summary.timeline[0].table_snapshots").doesNotExist())
                .andExpect(jsonPath("$.data.summary.avg_movement_time_minutes").exists())
                .andExpect(jsonPath("$.data.summary.queue_theory_metrics.model_type").exists());
    }

    @Test
    void asyncRunShouldReturnTaskStatusAndEventuallyProduceReport() throws Exception {
        String json = """
                {
                  "duration": 0.1,
                  "arrivalRate": 10,
                  "queueLimit": 10,
                  "packProbability": 0.2,
                  "seed": 901,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/simulation/run/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.task_id").exists())
                .andExpect(jsonPath("$.data.report_id").exists())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String taskId = response.path("data").path("task_id").asText();

        JsonNode status = waitForTerminalTask(taskId);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", status.path("data").path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(status.path("data").path("report_available").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(status.path("data").path("summary").path("arrived_count").isNumber());
    }

    @Test
    void triggerConfigShouldBeIgnoredByStaticSimulationMode() throws Exception {
        String json = """
                {
                  "duration": 0.1,
                  "arrivalRate": 20,
                  "queueLimit": 10,
                  "packProbability": 0.2,
                  "seed": 902,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]},
                  "triggers": [
                    {
                      "metric": "arrived_count",
                      "operator": ">",
                      "threshold": 0,
                      "action": "OPEN_EXTRA_WINDOW",
                      "action_value": "TAKEAWAY",
                      "max_firings": 1
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.config.triggers").doesNotExist())
                .andExpect(jsonPath("$.data.summary.interventions").doesNotExist())
                .andExpect(jsonPath("$.data.summary.window_types.length()").value(2));
    }

    @Test
    void optimizeEndpointShouldRunExplicitBatchWithoutSearching() throws Exception {
        String json = """
                {
                  "objective": "minimize avg_wait_time_minutes",
                  "configs": [
                    {
                      "duration": 0.1,
                      "arrivalRate": 30,
                      "queueLimit": 10,
                      "packProbability": 0.2,
                      "seed": 903,
                      "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                      "weatherConfig": {"weatherImpactFactor": 1.0},
                      "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                    },
                    {
                      "duration": 0.1,
                      "arrivalRate": 30,
                      "queueLimit": 10,
                      "packProbability": 0.5,
                      "seed": 903,
                      "baseConfig": {"windowCount": 3, "totalSeats": 20, "totalStudents": 0},
                      "weatherConfig": {"weatherImpactFactor": 1.0},
                      "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/simulation/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("batch_compare"))
                .andExpect(jsonPath("$.data.deprecated_optimization").value(true))
                .andExpect(jsonPath("$.data.objective").value("minimize avg_wait_time_minutes"))
                .andExpect(jsonPath("$.data.evaluated_configs").value(2))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results[0].index").value(1))
                .andExpect(jsonPath("$.data.results[0].config.base_config.window_count").value(2))
                .andExpect(jsonPath("$.data.results[0].summary.arrived_count").exists())
                .andExpect(jsonPath("$.data.results[1].config.base_config.window_count").value(3))
                .andExpect(jsonPath("$.data.top_configs").doesNotExist());
    }

    @Test
    void snakeCaseRequestShouldBeAccepted() throws Exception {
        String json = """
                {
                  "simulation_name": "snake-case-contract-test",
                  "duration": 0.1,
                  "arrival_rate": 30,
                  "queue_limit": 10,
                  "pack_probability": 0.2,
                  "seed": 456,
                  "base_config": {"window_count": 2, "takeaway_window_count": 1, "takeaway_service_time_multiplier": 1.2, "total_seats": 20, "total_students": 0},
                  "weather_config": {"current_weather": "sunny", "weather_impact_factor": 1.0},
                  "random_bounds": {
                    "arrival_interval": 0,
                    "service_range": [60, 120],
                    "dining_range": [600, 900],
                    "preference_range": [0.1, 0.3]
                  },
                  "peak_config": {
                    "class_peak_enabled": false,
                    "class_peak_start_minute": 15,
                    "class_peak_end_minute": 25,
                    "class_peak_multiplier": 5.0,
                    "class_peak_windows": [
                      {"start_minute": 10, "end_minute": 20, "multiplier": 4.0},
                      {"start_minute": 18, "end_minute": 28, "multiplier": 5.0}
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.config.simulation_name").value("snake-case-contract-test"))
                .andExpect(jsonPath("$.data.config.base_config.takeaway_window_count").value(1))
                .andExpect(jsonPath("$.data.config.base_config.takeaway_service_time_multiplier").value(1.2))
                .andExpect(jsonPath("$.data.config.peak_config.class_peak_windows[0].start_minute").value(10))
                .andExpect(jsonPath("$.data.config.peak_config.class_peak_windows[1].multiplier").value(5.0))
                .andExpect(jsonPath("$.data.config.random_bounds.service_range[0]").value(60))
                .andExpect(jsonPath("$.data.config.random_bounds.dining_range[1]").value(900));
    }

    @Test
    void reportListShouldReturnWrappedHistoryRecords() throws Exception {
        mockMvc.perform(get("/api/simulation/report/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").exists())
                .andExpect(jsonPath("$.data.reports").isArray());
    }

    @Test
    void reportTimelineAndHistoryShouldSupportPagination() throws Exception {
        String reportId = createReportAndGetId();

        mockMvc.perform(get("/api/simulation/report/{id}/timeline", reportId)
                        .param("page", "1")
                        .param("page_size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report_id").value(reportId))
                .andExpect(jsonPath("$.data.collection").value("timeline"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(2))
                .andExpect(jsonPath("$.data.total_items").exists())
                .andExpect(jsonPath("$.data.total_pages").exists())
                .andExpect(jsonPath("$.data.has_next").exists())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].time_seconds").exists());

        mockMvc.perform(get("/api/simulation/report/{id}/history", reportId)
                        .param("page", "1")
                        .param("page_size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report_id").value(reportId))
                .andExpect(jsonPath("$.data.collection").value("history"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(3))
                .andExpect(jsonPath("$.data.total_items").exists())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void fullReportShouldOmitHistoryByDefaultAndReturnItWhenRequested() throws Exception {
        String reportId = createReportAndGetId();

        mockMvc.perform(get("/api/simulation/report/{id}", reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.timeline").isArray())
                .andExpect(jsonPath("$.data.summary.history").doesNotExist());

        mockMvc.perform(get("/api/simulation/report/{id}", reportId)
                        .param("include_history", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.timeline").isArray())
                .andExpect(jsonPath("$.data.summary.history").isArray());
    }

    @Test
    void thousandStudentRunShouldReturnLightweightReport() throws Exception {
        String json = """
                {
                  "duration": 0.4,
                  "arrivalRate": 10000,
                  "queueLimit": 1000,
                  "packProbability": 1.0,
                  "seed": 1000,
                  "baseConfig": {"windowCount": 10, "totalSeats": 0, "totalStudents": 1000},
                  "randomBounds": {"arrivalInterval": 1, "serviceRange": [1, 2], "diningRange": [60, 61]},
                  "normalServiceDist": {"type": "FIXED", "mean": 1},
                  "windowServiceDist": {"type": "FIXED", "mean": 1}
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.arrived_count").value(1000))
                .andExpect(jsonPath("$.data.summary.history").doesNotExist())
                .andExpect(jsonPath("$.data.summary.timeline").isArray())
                .andExpect(jsonPath("$.data.summary.timeline[0].table_snapshots").doesNotExist())
                .andReturn();

        int responseBytes = result.getResponse().getContentAsByteArray().length;
        org.junit.jupiter.api.Assertions.assertTrue(responseBytes < 2_000_000,
                "1000-student default response should remain below 2 MB, actual=" + responseBytes);
    }

    @Test
    void reportPageEndpointShouldRejectInvalidPagination() throws Exception {
        String reportId = createReportAndGetId();

        mockMvc.perform(get("/api/simulation/report/{id}/timeline", reportId)
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("page must be >= 1"));

        mockMvc.perform(get("/api/simulation/report/{id}/history", reportId)
                        .param("page_size", "5001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("page_size must be in [1, 5000]"));
    }

    private String createReportAndGetId() throws Exception {
        String json = """
                {
                  "duration": 0.2,
                  "arrivalRate": 30,
                  "queueLimit": 10,
                  "packProbability": 0.2,
                  "seed": 789,
                  "baseConfig": {"windowCount": 2, "totalSeats": 20, "totalStudents": 0},
                  "weatherConfig": {"weatherImpactFactor": 1.0},
                  "randomBounds": {"arrivalInterval": 0, "serviceRange": [60, 120], "diningRange": [600, 900]}
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.path("data").path("report_id").asText();
    }

    private JsonNode waitForTerminalTask(String taskId) throws Exception {
        JsonNode status = null;
        for (int i = 0; i < 20; i++) {
            MvcResult statusResult = mockMvc.perform(get("/api/simulation/task/{id}/status", taskId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andReturn();
            status = objectMapper.readTree(statusResult.getResponse().getContentAsString());
            String value = status.path("data").path("status").asText();
            if ("COMPLETED".equals(value) || "FAILED".equals(value)) {
                return status;
            }
            Thread.sleep(100L);
        }
        return status;
    }
}
