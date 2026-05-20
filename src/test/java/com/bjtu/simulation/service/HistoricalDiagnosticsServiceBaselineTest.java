package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RFC-004 / PR-004A:Diagnostics 自适应基线 B1–B14 用例。
 *
 * 关键约束:
 *  - 不读 reports/*.json
 *  - 不调 C++
 *  - 不调 InternalStatisticsAnalyzer
 *  - 仅基于 ReportSummaryStore 的小摘要
 *  - 输出 schema_version 升级到 1.1,新字段 basis.baseline 加性扩展
 */
class HistoricalDiagnosticsServiceBaselineTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    private ReportSummaryStore newStore(Path analysisStore, Path reports) {
        ReportSummaryStore store = new ReportSummaryStore(analysisStore, reports, mapper,
                new ReportSummaryExtractor(mapper));
        store.validateConfiguration();
        return store;
    }

    private HistoricalDiagnosticsService newService(ReportSummaryStore store) {
        return new HistoricalDiagnosticsService(store, mapper);
    }

    private ObjectNode buildSummary(String reportId, String scenarioId, String fingerprint,
                                    double arrivalRate, double duration, int windowCount,
                                    int totalSeats, int takeawayWindowCount,
                                    double packProbability, String weatherType,
                                    double avgWait) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", "1.0");
        root.put("report_id", reportId);
        root.put("indexed_at_epoch_millis", 1747641600000L);
        ObjectNode source = root.putObject("source");
        source.put("original_report_path", "reports/" + reportId + ".json");
        source.put("source_file_name", reportId + ".json");
        source.put("source_size_bytes", 1024L);
        source.put("source_modified_time_epoch_millis", 1747641000000L);
        source.put("source_exists_when_indexed", true);
        source.put("source_status", "present");
        source.put("source_status_checked_at_epoch_millis", 1747641600000L);
        ObjectNode meta = root.putObject("report_meta");
        if (scenarioId == null) meta.putNull("scenario_id");
        else meta.put("scenario_id", scenarioId);
        meta.put("report_schema_version", "1.0");
        meta.put("generated_at_epoch_millis", 1747641000000L);
        ObjectNode config = root.putObject("config");
        config.put("arrival_rate", arrivalRate);
        config.put("duration", duration);
        config.put("window_count", windowCount);
        config.put("total_seats", totalSeats);
        config.put("takeaway_window_count", takeawayWindowCount);
        config.put("pack_probability", packProbability);
        config.put("weather_impact_factor", 1.0);
        config.put("weather_type", weatherType);
        config.put("queue_limit", 15);
        config.put("seed", 1L);
        config.put("config_fingerprint", fingerprint);
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("arrived_count", 1000L);
        metrics.put("served_count", 950L);
        metrics.put("abandoned_count", 50L);
        metrics.put("abandonment_rate", 0.05);
        metrics.put("typical_wait_time_minutes", avgWait);
        metrics.put("avg_wait_time_minutes", avgWait);
        metrics.put("seat_utilization_rate", 0.62);
        metrics.put("takeaway_rate", 0.16);
        metrics.put("max_total_queue_size", 18);
        metrics.put("avg_total_queue_size", 8.4);
        metrics.put("timeline_points", 100);
        ObjectNode precheck = root.putObject("precheck");
        precheck.put("has_required_fields", true);
        precheck.putArray("missing_fields");
        precheck.put("basic_invariants_valid", true);
        precheck.putArray("invariant_violations");
        precheck.put("timeline_monotonic", true);
        precheck.put("parse_status", "ok");
        precheck.putNull("parse_error_code");
        precheck.putArray("warnings");
        return root;
    }

    private ObjectNode standard(String id, String scenarioId, String fp, double avgWait) {
        return buildSummary(id, scenarioId, fp, 300.0, 2.0, 8, 200, 1, 0.13, "sunny", avgWait);
    }

    private void writeSummary(Path summaryDir, ObjectNode summary) throws IOException {
        Files.createDirectories(summaryDir);
        Path file = summaryDir.resolve(summary.get("report_id").asText() + ".summary.json");
        Files.writeString(file, mapper.writeValueAsString(summary), StandardCharsets.UTF_8);
    }

    private boolean hasWarning(JsonNode result, String warning) {
        for (JsonNode w : result.path("warnings")) if (warning.equals(w.asText())) return true;
        return false;
    }

    private boolean hasCheck(JsonNode result, String code) {
        for (JsonNode c : result.path("checks")) if (code.equals(c.path("code").asText())) return true;
        return false;
    }

    private boolean hasLimitation(JsonNode result, String code) {
        JsonNode lims = result.path("basis").path("baseline").path("limitations");
        if (!lims.isArray()) return false;
        for (JsonNode l : lims) if (code.equals(l.asText())) return true;
        return false;
    }

    // ==================== B1 ====================
    @Test
    void b1_emptyCorpusYieldsConfidenceNone(@TempDir Path tmp) {
        Path analysisStore = tmp.resolve("analysis-store");
        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        ObjectNode result = newService(store).diagnose("any-id");

        assertEquals("1.1", result.path("schema_version").asText());
        assertEquals("none", result.path("basis").path("matching_strategy").asText());
        assertEquals("none", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals("none", result.path("basis").path("baseline").path("strategy").asText());
        assertTrue(hasCheck(result, "MISSING_SUMMARY"));
    }

    // ==================== B2 ====================
    @Test
    void b2_oneOrTwoSummariesNoSimilarYieldsNone(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        // current 存在
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        // 邻居仅 1 条且 config 完全不相似(window_count 16,无 fp 相同)
        ObjectNode farther = standard("far1", null, "sha1:other1", 9.9);
        ((ObjectNode) farther.path("config")).put("window_count", 16);
        ((ObjectNode) farther.path("config")).put("total_seats", 500);
        writeSummary(summaryDir, farther);

        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("none", result.path("basis").path("matching_strategy").asText());
        assertEquals("none", result.path("basis").path("baseline").path("confidence").asText());
        assertTrue(hasWarning(result, "NO_COMPARABLE_HISTORY"));
        assertTrue(hasCheck(result, "INSUFFICIENT_BASELINE"));
        assertTrue(hasCheck(result, "NO_COMPARABLE_HISTORY"));
    }

    // ==================== B3 ====================
    @Test
    void b3_scenarioIdExactNFiveYieldsHighConfidence(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", 4.0));
        for (int i = 0; i < 5; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:other" + i, 4.0 + i * 0.1));
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("scenario_id_exact", result.path("basis").path("matching_strategy").asText());
        assertEquals("high", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(5, result.path("basis").path("matched_reports").asInt());
    }

    // ==================== B4 ====================
    @Test
    void b4_scenarioIdExactNThreeYieldsMediumConfidence(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", 4.0));
        for (int i = 0; i < 3; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:other" + i, 4.0 + i * 0.1));
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("scenario_id_exact", result.path("basis").path("matching_strategy").asText());
        assertEquals("medium", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(3, result.path("basis").path("matched_reports").asInt());
    }

    // ==================== B5 ====================
    @Test
    void b5_configFingerprintNSixYieldsHighConfidence(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:fp1", 4.0));
        for (int i = 0; i < 6; i++) {
            writeSummary(summaryDir, standard("fp" + i, null, "sha1:fp1", 4.0 + i * 0.1));
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("config_fingerprint", result.path("basis").path("matching_strategy").asText());
        assertEquals("high", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(6, result.path("basis").path("matched_reports").asInt());
        assertTrue(hasLimitation(result, "NO_EXACT_SCENARIO_MATCH"));
    }

    // ==================== B6 ====================
    @Test
    void b6_strictSimilarConfigNFiveYieldsMedium(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        for (int i = 0; i < 5; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            // 微调 arrival_rate 在 ±10% 内
            ((ObjectNode) nb.path("config")).put("arrival_rate", 300.0 + i * 5.0);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("similar_config", result.path("basis").path("matching_strategy").asText());
        assertEquals("medium", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(5, result.path("basis").path("matched_reports").asInt());
    }

    // ==================== B7 ====================
    @Test
    void b7_strictSimilarConfigNThreeYieldsLow(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        for (int i = 0; i < 3; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            ((ObjectNode) nb.path("config")).put("arrival_rate", 300.0 + i * 5.0);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("similar_config", result.path("basis").path("matching_strategy").asText());
        assertEquals("low", result.path("basis").path("baseline").path("confidence").asText());
        assertTrue(hasWarning(result, "BASELINE_CONFIDENCE_LOW"));
    }

    // ==================== B8 ====================
    @Test
    void b8_relaxedSimilarConfigYieldsLowConfidence(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        // strict 不命中:window_count = 9 vs 8(差 1,仅 relaxed 命中)
        for (int i = 0; i < 4; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            ((ObjectNode) nb.path("config")).put("window_count", 9);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("relaxed_similar_config", result.path("basis").path("matching_strategy").asText());
        assertEquals("low", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(4, result.path("basis").path("matched_reports").asInt());
        assertTrue(hasLimitation(result, "RELAXED_MATCH_USED"));
        assertTrue(hasCheck(result, "RELAXED_BASELINE_USED"));
    }

    // ==================== B9 ====================
    @Test
    void b9_weightedNearestNeighborsYieldsLow(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        // strict / relaxed 都不命中:window_count 16(差 8,relaxed 也拒);
        // 但 distance 仍 ≤ 1.0:挑配置接近的多份。
        for (int i = 0; i < 5; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            // total_seats 240(差 40,relaxed 拒);window_count 11(差 3,relaxed 拒)
            // 但欧氏距离仍小于 1.0
            ((ObjectNode) nb.path("config")).put("window_count", 11);
            ((ObjectNode) nb.path("config")).put("total_seats", 240);
            ((ObjectNode) nb.path("config")).put("arrival_rate", 305.0 + i);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("weighted_nearest_neighbors", result.path("basis").path("matching_strategy").asText());
        assertEquals("low", result.path("basis").path("baseline").path("confidence").asText());
        assertTrue(result.path("basis").path("baseline").path("effective_sample_size").asDouble() >= 3.0);
        assertTrue(result.path("basis").path("baseline").path("distance").has("min"));
        assertTrue(result.path("basis").path("baseline").path("distance").has("median"));
        assertTrue(result.path("basis").path("baseline").path("distance").has("max"));
        assertTrue(result.path("basis").path("baseline").path("weights").isObject());
        assertTrue(hasLimitation(result, "WNN_USED"));
        assertTrue(hasWarning(result, "WNN_USED"));
    }

    // ==================== B10 ====================
    @Test
    void b10_wnnAllRejectedDistanceFallsToGlobal(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        // 距离全部超阈值(arrival_rate 偏移 300% + window_count 极远):
        for (int i = 0; i < 5; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 9.9);
            ((ObjectNode) nb.path("config")).put("arrival_rate", 1500.0);
            ((ObjectNode) nb.path("config")).put("window_count", 30);
            ((ObjectNode) nb.path("config")).put("total_seats", 800);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("global_reference_baseline", result.path("basis").path("matching_strategy").asText());
        assertEquals("very_low", result.path("basis").path("baseline").path("confidence").asText());
        assertTrue(hasCheck(result, "BASELINE_REJECTED_DISTANCE"));
    }

    // ==================== B11 ====================
    @Test
    void b11_globalReferenceUsedNoAnomaly(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        // 多达 10 条邻居,但配置全远离阈值
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 12.0));
        for (int i = 0; i < 10; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            ((ObjectNode) nb.path("config")).put("arrival_rate", 1500.0);
            ((ObjectNode) nb.path("config")).put("window_count", 30);
            ((ObjectNode) nb.path("config")).put("total_seats", 800);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("global_reference_baseline", result.path("basis").path("matching_strategy").asText());
        assertEquals("very_low", result.path("basis").path("baseline").path("confidence").asText());
        assertEquals(0, result.path("anomalies").size(), "global 档不输出 anomaly");
        assertTrue(hasWarning(result, "GLOBAL_REFERENCE_ONLY"));
        assertTrue(hasWarning(result, "NOT_AN_OUTLIER_TEST"));
        // global_reference 子树有内容
        JsonNode global = result.path("basis").path("baseline").path("global_reference");
        assertTrue(global.isObject());
        assertTrue(global.path("metrics").isObject());
    }

    // ==================== B12 ====================
    @Test
    void b12_globalDoesNotComputeRobustZ(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 99.0)); // 极端值
        for (int i = 0; i < 8; i++) {
            ObjectNode nb = standard("nb" + i, null, "sha1:nb" + i, 4.0 + i * 0.1);
            ((ObjectNode) nb.path("config")).put("arrival_rate", 1500.0);
            ((ObjectNode) nb.path("config")).put("window_count", 30);
            ((ObjectNode) nb.path("config")).put("total_seats", 800);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("global_reference_baseline", result.path("basis").path("matching_strategy").asText());
        assertEquals(0, result.path("anomalies").size(),
                "global 档:即便 current 极端偏离全局 median 也不输出 anomaly");
    }

    // ==================== B13 ====================
    @Test
    void b13_relaxedWeatherEmitsWarning(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", 4.0));
        // 邻居 weather_type=rainy(strict 拒;relaxed 接受但 emit warning)
        for (int i = 0; i < 4; i++) {
            ObjectNode nb = buildSummary("nb" + i, null, "sha1:nb" + i,
                    300.0, 2.0, 8, 200, 1, 0.13, "rainy", 4.0 + i * 0.1);
            writeSummary(summaryDir, nb);
        }
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("relaxed_similar_config", result.path("basis").path("matching_strategy").asText());
        assertEquals("low", result.path("basis").path("baseline").path("confidence").asText());
        assertTrue(hasWarning(result, "RELAXED_WEATHER"));
    }

    // ==================== B14 ====================
    @Test
    void b14_schemaVersionIs1Point1(@TempDir Path tmp) throws IOException {
        Path summaryDir = tmp.resolve("analysis-store/report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", 4.0));
        ObjectNode result = newService(newStore(tmp.resolve("analysis-store"), tmp.resolve("reports"))).diagnose("cur");

        assertEquals("1.1", result.path("schema_version").asText());
        assertTrue(result.path("basis").path("baseline").isObject());
        assertNotNull(result.path("basis").path("policy").path("relaxed_window"));
        assertNotNull(result.path("basis").path("policy").path("weighted_nn"));

        // 旧字段全保留
        assertTrue(result.path("basis").has("matching_strategy"));
        assertTrue(result.path("basis").has("matched_reports"));
        assertTrue(result.has("checks"));
        assertTrue(result.has("anomalies"));
        assertTrue(result.has("warnings"));

        // 禁字段不出现
        assertFalse(result.has("quality_score"));
        assertFalse(result.has("level"));
    }

    @SuppressWarnings("unused")
    private void unused(ArrayNode n) { }
}
