package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.service.ExternalAnalysisService.AnalysisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ExternalAnalysisServiceTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    @Test
    void shouldDelegateToInternalAnalyzerWhenReportExists() {
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "rid")));
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, new InternalStatisticsAnalyzer(mapper));

        AnalysisResult result = service.runForReport("abc-123");

        assertTrue(result.isAvailable(), () -> "expected available, reason=" + result.getReason());
        assertNotNull(result.getPayload());
        assertEquals("java-internal", result.getPayload().path("computed_by").asText());
        assertEquals("1.0", result.getPayload().path("schema_version").asText());
        assertTrue(result.getPayload().has("headline_metrics"));
    }

    @Test
    void shouldReturnUnavailableWhenReportIdInvalid() {
        SimulationReportRepository repo = new StubRepo(false, Optional.empty());
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, new InternalStatisticsAnalyzer(mapper));

        AnalysisResult result = service.runForReport("../traversal");

        assertFalse(result.isAvailable());
        assertEquals("invalid report id", result.getReason());
    }

    @Test
    void shouldReturnUnavailableWhenReportMissing() {
        SimulationReportRepository repo = new StubRepo(true, Optional.empty());
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, new InternalStatisticsAnalyzer(mapper));

        AnalysisResult result = service.runForReport("missing-id");

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("report not found"),
                () -> "expected 'report not found' reason, got: " + result.getReason());
    }

    private static final class StubRepo extends SimulationReportRepository {
        private final boolean validId;
        private final Optional<JsonNode> stored;

        StubRepo(boolean validId, Optional<JsonNode> stored) {
            super();
            this.validId = validId;
            this.stored = stored;
        }

        @Override
        public boolean isSafeReportId(String reportId) {
            return validId;
        }

        @Override
        public Optional<JsonNode> readById(String reportId) {
            return stored;
        }
    }
}
