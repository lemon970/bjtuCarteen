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
}
