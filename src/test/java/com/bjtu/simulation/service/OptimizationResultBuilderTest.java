package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OptimizationResultBuilderTest {

    private static final ObjectMapper MAPPER = AppBeansConfig.createReportObjectMapper();
    private static SimulationReport sharedReport;

    @BeforeAll
    static void setUpSharedReport() {
        SimConfig config = new SimConfig();
        config.setDuration(0.05);
        config.setArrivalRate(20);
        config.setQueueLimit(10);
        config.setSeed(7777L);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTotalSeats(20);
        config.getBaseConfig().setTotalStudents(0);
        sharedReport = new SimulationRunService().run(config, "test-report-id");
    }

    @Test
    void parseObjectiveDefaultsWhenBlankOrNull() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective fromNull = builder.parseObjective(null);
        assertEquals("minimize", fromNull.direction());
        assertEquals("typical_wait_time_minutes", fromNull.metric());

        OptimizationResultBuilder.Objective fromBlank = builder.parseObjective("   ");
        assertEquals("minimize", fromBlank.direction());
        assertEquals("typical_wait_time_minutes", fromBlank.metric());
    }

    @Test
    void parseObjectiveAcceptsMaximizeAndMinimize() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);

        OptimizationResultBuilder.Objective max = builder.parseObjective("maximize seat_utilization_rate");
        assertEquals("maximize", max.direction());
        assertEquals("seat_utilization_rate", max.metric());

        OptimizationResultBuilder.Objective min = builder.parseObjective("minimize avg_wait_time_minutes");
        assertEquals("minimize", min.direction());
        assertEquals("avg_wait_time_minutes", min.metric());
    }

    @Test
    void parseObjectiveFallsBackToMinimizeForUnknownDirection() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective parsed = builder.parseObjective("garbage typical_wait_time_minutes");
        assertEquals("minimize", parsed.direction());
        assertEquals("typical_wait_time_minutes", parsed.metric());
    }

    @Test
    void buildItemNodeContainsExpectedFields() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective objective = builder.parseObjective("minimize avg_wait_time_minutes");

        ObjectNode node = builder.buildItemNode(3, sharedReport, objective);
        assertEquals(3, node.path("index").asInt());
        assertEquals("test-report-id", node.path("report_id").asText());
        assertTrue(node.has("config"));
        assertTrue(node.has("summary"));
        assertTrue(node.has("objective_value"));
        assertFalse(node.has("error_message"), "sync buildItemNode must not include error_message");
    }

    @Test
    void buildItemNodeStripsHistoryTimelineAndTableSnapshots() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective objective = builder.parseObjective("minimize typical_wait_time_minutes");

        ObjectNode node = builder.buildItemNode(1, sharedReport, objective);
        assertFalse(node.path("summary").has("history"));
        assertFalse(node.path("summary").has("timeline"));
        assertFalse(node.path("summary").has("table_snapshots"));
    }

    @Test
    void buildSuccessItemNodeWithErrorFieldIncludesNullErrorMessage() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective objective = builder.parseObjective("minimize avg_wait_time_minutes");

        ObjectNode node = builder.buildSuccessItemNodeWithErrorField(2, sharedReport, objective);
        assertEquals(2, node.path("index").asInt());
        assertEquals("test-report-id", node.path("report_id").asText());
        assertTrue(node.has("error_message"));
        assertTrue(node.path("error_message").isNull());
    }

    @Test
    void buildFailedItemNodeUsesExceptionClassAndMessage() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);

        SimConfig config = new SimConfig();
        config.setDuration(0.05);
        ObjectNode node = builder.buildFailedItemNode(7, config, new IllegalStateException("kaboom"));

        assertEquals(7, node.path("index").asInt());
        assertTrue(node.path("report_id").isNull());
        assertTrue(node.path("summary").isNull());
        assertTrue(node.path("objective_value").isNull());
        assertEquals("IllegalStateException: kaboom", node.path("error_message").asText());
        assertNotNull(node.get("config"), "failed item must still echo back the config");
    }

    @Test
    void buildFailedItemNodeHandlesNullErrorAndConfig() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);

        ObjectNode node = builder.buildFailedItemNode(1, null, null);
        assertEquals(1, node.path("index").asInt());
        assertEquals("unknown error", node.path("error_message").asText());
        assertTrue(node.has("config"), "config must always be present (default empty)");
    }

    @Test
    void buildFailedItemNodeOmitsMessageWhenExceptionMessageBlank() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);

        ObjectNode node = builder.buildFailedItemNode(2, new SimConfig(), new RuntimeException());
        assertEquals("RuntimeException", node.path("error_message").asText());
    }

    @Test
    void metricValueResolvedThroughObjectiveValueIsFinite() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective objective = builder.parseObjective("minimize seat_utilization_rate");

        ObjectNode node = builder.buildItemNode(1, sharedReport, objective);
        double value = node.path("objective_value").asDouble();
        assertTrue(Double.isFinite(value), "objective_value should be finite for known metric");
    }

    @Test
    void unknownMetricFallsBackToAvgWaitTimeMinutes() {
        OptimizationResultBuilder builder = new OptimizationResultBuilder(MAPPER);
        OptimizationResultBuilder.Objective unknown = builder.parseObjective("minimize totally_unknown_metric");
        OptimizationResultBuilder.Objective fallback = builder.parseObjective("minimize avg_wait_time_minutes");

        double unknownValue = builder.buildItemNode(1, sharedReport, unknown).path("objective_value").asDouble();
        double avgWait = builder.buildItemNode(1, sharedReport, fallback).path("objective_value").asDouble();
        assertEquals(avgWait, unknownValue, 1e-9, "unknown metric must fall back to avg_wait_time_minutes");
    }
}
