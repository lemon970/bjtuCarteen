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
        // missing-id 不存在,服务返回 503;此场景与 C++ binary 缺失无关,
        // binary 缺失时 ExternalAnalysisService 走 Java fallback,见 ExternalAnalysisServiceTest。
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    void runEndpointShouldAcceptSnakeCaseReportId() throws Exception {
        // 第七轮:RunRequest.reportId 加 @JsonAlias("report_id"),前端按 API.md 文档发送
        // snake_case 同样进入相同处理路径(此处用 missing-id 触发 503,验证字段被解析)
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\"}"))
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

    // ==================== 阶段 2 RFC-002:Historical Diagnostics 集成 ====================

    /** C1:默认请求不带 include 标志,响应不应包含 historical_diagnostics 字段。 */
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

    /** C2:include_historical_diagnostics=false 时,响应不应包含 historical_diagnostics 字段。 */
    @Test
    void runEndpointIncludeFalseShouldNotIncludeHistoricalDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_diagnostics\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist());
    }

    /**
     * C3 + C5:include_historical_diagnostics=true 时,即便走 503 路径也应把 historical_diagnostics 子树合入响应,
     * 主分析结构保持(available=false 仍在),诊断字段集严格(无 quality_score/level/tier/score)。
     */
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

    /** C4:同 snake_case 别名,以 includeHistoricalDiagnostics(camelCase)发起也应启用。 */
    @Test
    void runEndpointCamelCaseIncludeFlagShouldAlsoEnableDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\",\"includeHistoricalDiagnostics\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_diagnostics.enabled").value(true));
    }

    /** C6:cross-scenario 接口即便客户端误传 include 标志,响应也不应包含 historical_diagnostics。 */
    @Test
    void crossScenarioEndpointShouldNotIncludeHistoricalDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/cross-scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioIds\":[\"only-one\"],\"include_historical_diagnostics\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.historical_diagnostics").doesNotExist())
                .andExpect(jsonPath("$.data.historical_diagnostics").doesNotExist());
    }

    // ==================== 阶段 3 RFC-003:Historical Quality 集成 ====================

    /** C7:无任何 include flag 时,响应不含 historical_quality 也不含 historical_diagnostics。 */
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

    /** C8:include_historical_quality=false 时,响应不含 historical_quality。 */
    @Test
    void runEndpointIncludeQualityFalseShouldNotIncludeIt() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report_id\":\"missing-id\",\"include_historical_quality\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality").doesNotExist());
    }

    /**
     * C9:仅 include_historical_quality=true(diagnostics 关闭)时,
     * 顶层只输出 historical_quality,不输出 historical_diagnostics;basis.diagnostics_used=true。
     */
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
                // missing-id → MISSING_SUMMARY → score_available=false
                .andExpect(jsonPath("$.data.historical_quality.score_available").value(false))
                .andExpect(jsonPath("$.data.historical_quality.level").value("unavailable"))
                .andExpect(jsonPath("$.data.historical_quality.unavailable_reason").value("MISSING_SUMMARY"))
                .andExpect(jsonPath("$.data.historical_quality.quality_score").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.quality_score_percent").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.dimensions").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality.penalties").doesNotExist())
                // 免责声明守约束
                .andExpect(jsonPath("$.data.historical_quality.warnings",
                        org.hamcrest.Matchers.hasItem("QUALITY_SCORE_IS_DIAGNOSTIC_ONLY")))
                .andExpect(jsonPath("$.data.historical_quality.warnings",
                        org.hamcrest.Matchers.hasItem("NOT_A_BUSINESS_PERFORMANCE_SCORE")));
    }

    /**
     * C10:同时启用 quality + diagnostics 时,两者并存,且 basis 字段同源(matched_reports 等数字逐字一致)。
     */
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
                // 同 reportId,basis 中 corpus_size 应同源
                .andExpect(jsonPath("$.data.historical_diagnostics.basis.corpus_size").exists())
                .andExpect(jsonPath("$.data.historical_quality.basis.corpus_size").exists());
    }

    /** C11:include_historical_quality=true 走 503 路径,quality.score_available=false + level=unavailable。 */
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

    /**
     * C12:递归扫描 historical_quality 子树,**只**禁出现业务含义评分键(business_score 等);
     * 允许 quality_score / level / dimensions.*.score(这些是 phase 3 的合法字段)。
     */
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

    /** C13:camelCase 别名 includeHistoricalQuality 同样能启用。 */
    @Test
    void runEndpointCamelCaseQualityFlagShouldWork() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"missing-id\",\"includeHistoricalQuality\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.historical_quality.enabled").value(true));
    }

    /** C14:cross-scenario 误传 include_historical_quality=true 不应输出 historical_quality。 */
    @Test
    void crossScenarioEndpointShouldNotIncludeHistoricalQuality() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(post("/api/analysis/cross-scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioIds\":[\"only-one\"],\"include_historical_quality\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.historical_quality").doesNotExist())
                .andExpect(jsonPath("$.data.historical_quality").doesNotExist());
    }
}
