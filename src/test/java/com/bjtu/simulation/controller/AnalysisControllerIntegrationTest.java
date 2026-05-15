package com.bjtu.simulation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = "spring.main.banner-mode=off")
class AnalysisControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void runEndpointShouldReject400WhenReportIdMissing() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void runEndpointShouldReturn503WhenBinaryMissing() throws Exception {
        // No canteen-analyze.exe is present in CI / dev shells, so the service degrades to 503.
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void crossScenarioEndpointShouldReject400WhenScenariosTooFew() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/cross-scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioIds\":[\"only-one\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
