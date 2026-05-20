package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class InternalStatisticsAnalyzerTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
    private final InternalStatisticsAnalyzer analyzer = new InternalStatisticsAnalyzer(mapper);

    @Test
    void shouldEmitSchemaAndComputedByForSingleReport() {
        ObjectNode report = sampleReport("rid-1", 0.45, 12.0, new int[]{120, 80, 50}, new double[]{2, 5, 6, 4, 3});
        ObjectNode result = analyzer.analyze(report);

        assertEquals("1.0", result.path("schema_version").asText());
        assertEquals("java-internal", result.path("computed_by").asText());
        assertEquals("rid-1", result.path("source_report_id").asText());
        assertTrue(result.has("confidence_intervals"));
        assertTrue(result.has("bottleneck"));
        assertTrue(result.has("headline_metrics"));
        assertEquals(0.45, result.path("headline_metrics").path("seat_utilization_rate").asDouble(), 0.001);
    }

    @Test
    void giniShouldRiseWhenWindowsAreSkewed() {
        ObjectNode balanced = sampleReport("rid-2", 0.5, 6.0, new int[]{100, 100, 100}, new double[]{2, 2, 2});
        ObjectNode skewed = sampleReport("rid-3", 0.5, 6.0, new int[]{300, 0, 0}, new double[]{2, 2, 2});

        double balancedGini = analyzer.analyze(balanced).path("bottleneck").path("gini_coefficient").asDouble();
        double skewedGini = analyzer.analyze(skewed).path("bottleneck").path("gini_coefficient").asDouble();

        assertEquals(0.0, balancedGini, 0.01);
        assertTrue(skewedGini > 0.5, () -> "expected skewed gini > 0.5 but got " + skewedGini);
    }

    @Test
    void bootstrapIntervalShouldContainSampleMean() {
        double[] queueSeries = new double[60];
        for (int i = 0; i < queueSeries.length; i++) {
            queueSeries[i] = 5.0 + Math.sin(i * 0.3) * 2.0;
        }
        ObjectNode report = sampleReport("rid-4", 0.7, 8.0, new int[]{150, 130, 140}, queueSeries);
        ObjectNode ci = (ObjectNode) analyzer.analyze(report).path("confidence_intervals").path("wait_time_minutes");

        double mean = ci.path("mean").asDouble();
        double lower = ci.path("lower").asDouble();
        double upper = ci.path("upper").asDouble();
        assertTrue(lower <= mean, () -> "lower " + lower + " should be <= mean " + mean);
        assertTrue(upper >= mean, () -> "upper " + upper + " should be >= mean " + mean);
        assertTrue(ci.path("sample_count").asInt() == queueSeries.length);
    }

    @Test
    void batchAnalyzeShouldProduceAnovaAndMonteCarlo() {
        ObjectNode r1 = sampleReport("rid-a", 0.4, 5.0, new int[]{100, 80}, new double[]{1, 1, 1, 1});
        ObjectNode r2 = sampleReport("rid-b", 0.65, 9.0, new int[]{120, 90}, new double[]{8, 8, 7, 9});
        ObjectNode r3 = sampleReport("rid-c", 0.55, 7.0, new int[]{110, 85}, new double[]{4, 5, 4, 5});

        ObjectNode result = analyzer.batchAnalyze(List.of(r1, r2, r3));
        assertEquals(3, result.path("report_count").asInt());

        JsonNode mc = result.path("monte_carlo");
        assertTrue(mc.path("seat_utilization_rate").has("mean"));
        assertTrue(mc.path("seat_utilization_rate").has("stddev"));

        JsonNode anova = result.path("anova");
        assertTrue(anova.path("enabled").asBoolean());
        assertTrue(anova.path("f_statistic").asDouble() > 0.0);
        assertEquals(3, anova.path("group_count").asInt());
        assertNotEquals(anova.path("strongest_group").asText(), anova.path("weakest_group").asText());
    }

    private ObjectNode sampleReport(String reportId,
                                     double seatUtilization,
                                     double avgWait,
                                     int[] windowServedCounts,
                                     double[] queueSeries) {
        ObjectNode report = mapper.createObjectNode();
        report.put("report_id", reportId);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("seat_utilization_rate", seatUtilization);
        summary.put("typical_wait_time_minutes", avgWait);
        summary.put("avg_wait_time_minutes", avgWait);
        summary.put("served_count", 320);
        summary.put("abandoned_count", 4);
        summary.put("takeaway_rate", 0.18);
        summary.put("simulation_end_time_minutes", queueSeries.length);

        ArrayNode wsc = summary.putArray("window_served_counts");
        for (int v : windowServedCounts) {
            wsc.add(v);
        }
        ArrayNode timeline = summary.putArray("timeline");
        for (int i = 0; i < queueSeries.length; i++) {
            ObjectNode point = timeline.addObject();
            point.put("minute", i);
            point.put("total_queue_size", queueSeries[i]);
            point.put("seat_utilization_rate", seatUtilization);
        }
        report.set("summary", summary);
        return report;
    }
}
