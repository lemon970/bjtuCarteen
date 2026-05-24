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
    void runEndpointShouldReturn503WhenReportMissing() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void runEndpointShouldAcceptSnakeCaseReportId() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void runEndpointDefaultShouldNotIncludeHistoricalDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist());
    }

    @Test
    void runEndpointIncludeFalseShouldNotIncludeHistoricalDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_diagnostics\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist());
    }

    @Test
    void runEndpointIncludeTrueShouldMergeHistoricalDiagnosticsEvenOn503() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_diagnostics\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.historical_diagnostics.enabled").value(true))
                .andExpect(jsonPath("$.data.historical_diagnostics.schema_version").value("1.1"))
                .andExpect(jsonPath("$.data.historical_diagnostics.computed_by").value("java-summary-store"))
                .andExpect(jsonPath("$.data.historical_diagnostics.basis.current_summary_present").value(false))
                .andExpect(jsonPath("$.data.historical_diagnostics.basis.matching_strategy").exists())
                .andExpect(jsonPath("$.data.historical_diagnostics.quality_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_diagnostics.level").doesNotExist())
                .andExpect(jsonPath("$.data.historical_diagnostics.tier").doesNotExist())
                .andExpect(jsonPath("$.data.historical_diagnostics.score").doesNotExist());
    }

    @Test
    void runEndpointCamelCaseIncludeFlagShouldAlsoEnableDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\",\"includeHistoricalDiagnostics\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics.enabled").value(true));
    }

    @Test
    void runEndpointDefaultShouldNotIncludeHistoricalQuality() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality").doesNotExist())
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist());
    }

    @Test
    void runEndpointIncludeQualityFalseShouldNotIncludeIt() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_quality\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality").doesNotExist());
    }

    @Test
    void runEndpointIncludeQualityOnlyShouldNotEmitDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_quality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.enabled").value(true))
                .andExpect(jsonPath("$.data.historical_quality.schema_version").value("1.1"))
                .andExpect(jsonPath("$.data.historical_quality.computed_by").value("java-quality-scorer"))
                .andExpect(jsonPath("$.data.historical_quality.basis.diagnostics_used").value(true))
                .andExpect(jsonPath("$.data.historical_quality.score_available").value(false))
                .andExpect(jsonPath("$.data.historical_quality.level").value("unavailable"))
                .andExpect(jsonPath("$.data.historical_quality.unavailable_reason").value("MISSING_SUMMARY"))
                .andExpect(jsonPath("$.data.historical_quality.quality_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.quality_score_percent").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.dimensions").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.penalties").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.warnings",
                        org.hamcrest.Matchers.hasItem("QUALITY_SCORE_IS_DIAGNOSTIC_ONLY")))
                .andExpect(jsonPath("$.data.historical_quality.warnings",
                        org.hamcrest.Matchers.hasItem("NOT_A_BUSINESS_PERFORMANCE_SCORE")));
    }

    @Test
    void runEndpointBothFlagsTrueShouldEmitBothWithSameBasis() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\","
                                + "\"include_historical_diagnostics\":true,"
                                + "\"include_historical_quality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics.enabled").value(true))
                .andExpect(jsonPath("$.data.historical_quality.enabled").value(true))
                .andExpect(jsonPath("$.data.historical_diagnostics.basis.corpus_size").exists())
                .andExpect(jsonPath("$.data.historical_quality.basis.corpus_size").exists());
    }

    @Test
    void runEndpointIncludeQualityOn503ShouldEmitUnavailable() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_quality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.historical_quality.score_available").value(false))
                .andExpect(jsonPath("$.data.historical_quality.level").value("unavailable"));
    }

    @Test
    void runEndpointHistoricalQualityShouldNotExposeBusinessScoreKeys() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_quality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality.business_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.performance_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.ranking_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.optimization_score").doesNotExist());
    }

    @Test
    void runEndpointCamelCaseQualityFlagShouldWork() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\",\"includeHistoricalQuality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality.enabled").value(true));
    }
}
