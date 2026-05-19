package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 阶段 2 RFC 测试:Historical Diagnostics 单元用例 H1–H18。
 * 全部测试在 @TempDir 下运行,直接写 summary JSON 到 analysis-store/report-summaries/,
 * 不依赖真实 reports/ 或仿真主路径。
 *
 * 严格遵守 RFC 约束:
 *  - 不读 reports/*.json
 *  - 不调 C++
 *  - 不调 InternalStatisticsAnalyzer
 *  - 输出 schema 仅 enabled/schema_version/computed_by/computed_at_epoch_millis/basis/checks/anomalies/warnings
 *  - 不出现 quality_score / level / tier / score
 */
class HistoricalDiagnosticsServiceTest {

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

    /**
     * 直接构造一个完整的 summary JSON 节点。所有 metric/config 字段都齐,
     * 调用方通过参数 override 偏离的少数几项。
     */
    private ObjectNode buildSummary(String reportId,
                                    String scenarioId,
                                    String fingerprint,
                                    String sourceStatus,
                                    boolean parseOk,
                                    boolean invariantsValid,
                                    double arrivalRate,
                                    double duration,
                                    int windowCount,
                                    int totalSeats,
                                    int takeawayWindowCount,
                                    double packProbability,
                                    String weatherType,
                                    int queueLimit,
                                    Double abandonmentRate,
                                    Double avgWait,
                                    Double typicalWait,
                                    Double seatUtil,
                                    Double takeawayRate,
                                    Integer maxQueue,
                                    Double avgQueue) {
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
        source.put("source_status", sourceStatus);
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
        config.put("queue_limit", queueLimit);
        config.put("seed", 1L);
        config.put("config_fingerprint", fingerprint);

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("arrived_count", 1000L);
        metrics.put("served_count", 950L);
        metrics.put("abandoned_count", 50L);
        if (abandonmentRate == null) metrics.putNull("abandonment_rate"); else metrics.put("abandonment_rate", abandonmentRate);
        if (typicalWait == null) metrics.putNull("typical_wait_time_minutes"); else metrics.put("typical_wait_time_minutes", typicalWait);
        if (avgWait == null) metrics.putNull("avg_wait_time_minutes"); else metrics.put("avg_wait_time_minutes", avgWait);
        if (seatUtil == null) metrics.putNull("seat_utilization_rate"); else metrics.put("seat_utilization_rate", seatUtil);
        if (takeawayRate == null) metrics.putNull("takeaway_rate"); else metrics.put("takeaway_rate", takeawayRate);
        if (maxQueue == null) metrics.putNull("max_total_queue_size"); else metrics.put("max_total_queue_size", maxQueue);
        if (avgQueue == null) metrics.putNull("avg_total_queue_size"); else metrics.put("avg_total_queue_size", avgQueue);
        metrics.put("timeline_points", 100);

        ObjectNode precheck = root.putObject("precheck");
        precheck.put("has_required_fields", true);
        precheck.putArray("missing_fields");
        precheck.put("basic_invariants_valid", invariantsValid);
        ArrayNode violations = precheck.putArray("invariant_violations");
        if (!invariantsValid) violations.add("served_count != dine_in_count + takeaway_count");
        precheck.put("timeline_monotonic", true);
        precheck.put("parse_status", parseOk ? "ok" : "failed");
        precheck.putNull("parse_error_code");
        precheck.putArray("warnings");

        return root;
    }

    /** 构造一个典型的"标准"摘要,按位置覆盖即可。 */
    private ObjectNode standard(String reportId, String scenarioId, String fingerprint,
                                String sourceStatus, double avgWait) {
        return buildSummary(reportId, scenarioId, fingerprint, sourceStatus, true, true,
                300.0, 2.0, 8, 200, 1, 0.13, "sunny", 15,
                0.05, avgWait, avgWait, 0.62, 0.16, 18, 8.4);
    }

    private void writeSummary(Path summaryDir, ObjectNode summary) throws IOException {
        Files.createDirectories(summaryDir);
        Path file = summaryDir.resolve(summary.get("report_id").asText() + ".summary.json");
        Files.writeString(file, mapper.writeValueAsString(summary), StandardCharsets.UTF_8);
    }

    private boolean hasCheck(JsonNode result, String code) {
        for (JsonNode c : result.path("checks")) {
            if (code.equals(c.path("code").asText())) return true;
        }
        return false;
    }

    private boolean hasWarning(JsonNode result, String warning) {
        for (JsonNode w : result.path("warnings")) {
            if (warning.equals(w.asText())) return true;
        }
        return false;
    }

    private JsonNode anomalyOf(JsonNode result, String metric) {
        for (JsonNode a : result.path("anomalies")) {
            if (metric.equals(a.path("metric").asText())) return a;
        }
        return null;
    }

    private void assertSchemaShape(JsonNode result) {
        // 顶层字段集严格,无 quality_score/level/tier/score
        assertTrue(result.has("enabled"));
        assertTrue(result.has("schema_version"));
        assertTrue(result.has("computed_by"));
        assertTrue(result.has("computed_at_epoch_millis"));
        assertTrue(result.has("basis"));
        assertTrue(result.has("checks"));
        assertTrue(result.has("anomalies"));
        assertTrue(result.has("warnings"));
        assertFalse(result.has("quality_score"), "quality_score must NEVER appear");
        assertFalse(result.has("level"), "level must NEVER appear");
        assertFalse(result.has("tier"), "tier must NEVER appear");
        assertFalse(result.has("score"), "score must NEVER appear");
        assertEquals("1.0", result.path("schema_version").asText());
        assertEquals("java-summary-store", result.path("computed_by").asText());
        assertTrue(result.path("enabled").asBoolean());
    }

    // ==================== H1 ====================
    @Test
    void h1_emptyCorpusEmitsZeroAndInsufficientBaseline(@TempDir Path tmp) {
        Path analysisStore = tmp.resolve("analysis-store");
        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        HistoricalDiagnosticsService svc = newService(store);

        ObjectNode result = assertDoesNotThrow(() -> svc.diagnose("any-id"));

        assertSchemaShape(result);
        assertEquals(0, result.path("basis").path("corpus_size").asInt());
        assertEquals(0, result.path("basis").path("matched_reports").asInt());
        assertEquals("none", result.path("basis").path("matching_strategy").asText());
        assertTrue(hasCheck(result, "MISSING_SUMMARY"));
        assertTrue(hasCheck(result, "INSUFFICIENT_BASELINE"));
        assertEquals(0, result.path("anomalies").size());
    }

    // ==================== H2 ====================
    @Test
    void h2_currentMissingButCorpusExists(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        for (int i = 0; i < 5; i++) {
            writeSummary(summaryDir, standard("base" + i, "scn", "sha1:abc", "present", 4.0 + i * 0.1));
        }
        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        HistoricalDiagnosticsService svc = newService(store);

        ObjectNode result = svc.diagnose("not-here");

        assertSchemaShape(result);
        assertEquals(5, result.path("basis").path("corpus_size").asInt());
        assertEquals(0, result.path("basis").path("matched_reports").asInt());
        assertEquals("none", result.path("basis").path("matching_strategy").asText());
        assertFalse(result.path("basis").path("current_summary_present").asBoolean());
        assertTrue(hasCheck(result, "MISSING_SUMMARY"));
        // present count comes from source_status_counts
        assertEquals(5, result.path("basis").path("source_status_counts").path("present").asInt());
    }

    // ==================== H3 ====================
    @Test
    void h3_scenarioIdExactMatchSelected(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.0));
        for (int i = 0; i < 6; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:xyz" + i, "present", 4.0 + i * 0.1));
        }
        // 干扰:不同 scenario_id
        for (int i = 0; i < 3; i++) {
            writeSummary(summaryDir, standard("other" + i, "dinner", "sha1:def" + i, "present", 8.0));
        }
        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        HistoricalDiagnosticsService svc = newService(store);

        ObjectNode result = svc.diagnose("cur");

        assertSchemaShape(result);
        assertEquals("scenario_id_exact", result.path("basis").path("matching_strategy").asText());
        assertEquals(6, result.path("basis").path("matched_reports").asInt());
        assertTrue(result.path("basis").path("current_summary_present").asBoolean());
        assertTrue(result.path("basis").path("self_excluded").asBoolean());
    }

    // ==================== H4 ====================
    @Test
    void h4_fingerprintMatchWhenScenarioMissing(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        // 当前 summary 缺 scenario_id
        writeSummary(summaryDir, standard("cur", null, "sha1:fp1", "present", 4.0));
        // fingerprint 一致的邻居 6 个,scenario_id 也都缺
        for (int i = 0; i < 6; i++) {
            writeSummary(summaryDir, standard("fp" + i, null, "sha1:fp1", "present", 4.0 + i * 0.1));
        }
        // fingerprint 不同的干扰
        writeSummary(summaryDir, standard("dis", null, "sha1:other", "present", 9.9));

        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        ObjectNode result = newService(store).diagnose("cur");

        assertEquals("config_fingerprint", result.path("basis").path("matching_strategy").asText());
        assertEquals(6, result.path("basis").path("matched_reports").asInt());
    }

    // ==================== H5 ====================
    @Test
    void h5_similarConfigFallback(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        // 当前 summary 缺 scenario_id 和 fingerprint
        writeSummary(summaryDir, standard("cur", null, "sha1:unavailable", "present", 4.0));
        // 邻居 5 个,fingerprint 各不同(避免 Tier B 命中),但 config 接近
        for (int i = 0; i < 5; i++) {
            ObjectNode nb = standard("near" + i, null, "sha1:unique" + i, "present", 4.0 + i * 0.1);
            // 微调 arrival_rate 在 ±10% 内
            ((ObjectNode) nb.path("config")).put("arrival_rate", 300.0 + i * 5.0);
            writeSummary(summaryDir, nb);
        }
        // 干扰:不同 window_count
        ObjectNode bad = standard("badcfg", null, "sha1:badcfg", "present", 9.9);
        ((ObjectNode) bad.path("config")).put("window_count", 16);
        writeSummary(summaryDir, bad);

        ReportSummaryStore store = newStore(analysisStore, tmp.resolve("reports"));
        ObjectNode result = newService(store).diagnose("cur");

        assertEquals("similar_config", result.path("basis").path("matching_strategy").asText());
        assertEquals(5, result.path("basis").path("matched_reports").asInt());
    }

    // ==================== H6 ====================
    @Test
    void h6_nothingMatchesYieldsNoneStrategy(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:fpcur", "present", 4.0));
        // 邻居:scenario_id / fingerprint / config 全都不匹配
        for (int i = 0; i < 4; i++) {
            ObjectNode nb = standard("dif" + i, "dinner", "sha1:other" + i, "present", 9.9);
            ((ObjectNode) nb.path("config")).put("window_count", 16);
            ((ObjectNode) nb.path("config")).put("total_seats", 500);
            writeSummary(summaryDir, nb);
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals("none", result.path("basis").path("matching_strategy").asText());
        assertEquals(0, result.path("basis").path("matched_reports").asInt());
        assertTrue(hasCheck(result, "INSUFFICIENT_BASELINE"));
    }

    // ==================== H7 ====================
    @Test
    void h7_matchedTwoSkipsAnomalyEmitInsufficientCheck(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 12.0));
        writeSummary(summaryDir, standard("nb1", "lunch", "sha1:abc", "present", 4.0));
        writeSummary(summaryDir, standard("nb2", "lunch", "sha1:abc", "present", 4.2));

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(2, result.path("basis").path("matched_reports").asInt());
        assertEquals(0, result.path("anomalies").size(),
                "matched=2 < 3,不计算偏离");
        assertTrue(hasCheck(result, "INSUFFICIENT_BASELINE"));
    }

    // ==================== H8 ====================
    @Test
    void h8_matchedFourMedianOnlyNoAnomaly(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 100.0));
        for (int i = 0; i < 4; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(4, result.path("basis").path("matched_reports").asInt());
        assertEquals(0, result.path("anomalies").size(),
                "3<=n<=4 时仅计算 median,不产生 anomaly 项");
        assertTrue(hasCheck(result, "INSUFFICIENT_BASELINE"),
                "matched<5 仍输出 INSUFFICIENT_BASELINE warning");
    }

    // ==================== H9 ====================
    @Test
    void h9_madZeroEmitsWarningNoAnomaly(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        // 当前 takeaway_rate = 0.99,邻居全 0.16(MAD = 0)
        ObjectNode cur = standard("cur", "lunch", "sha1:abc", "present", 4.2);
        ((ObjectNode) cur.path("metrics")).put("takeaway_rate", 0.99);
        writeSummary(summaryDir, cur);
        for (int i = 0; i < 8; i++) {
            // avgWait 各异避免触发 avg_wait_time_minutes anomaly,关注 takeaway_rate
            ObjectNode nb = standard("nb" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1);
            ((ObjectNode) nb.path("metrics")).put("takeaway_rate", 0.16);
            writeSummary(summaryDir, nb);
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(8, result.path("basis").path("matched_reports").asInt());
        assertTrue(hasWarning(result, "MAD_ZERO:takeaway_rate"));
        assertEquals(null, anomalyOf(result, "takeaway_rate"),
                "MAD=0 时不应产生 takeaway_rate anomaly");
    }

    // ==================== H10 ====================
    @Test
    void h10_largeDeviationEmitsAnomaly(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        // 当前 avg_wait = 12.0 vs 邻居 ~4.x → robust_z 巨大
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 12.0));
        double[] baseline = {4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7};
        for (int i = 0; i < baseline.length; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:abc", "present", baseline[i]));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        JsonNode anomaly = anomalyOf(result, "avg_wait_time_minutes");
        assertNotNull(anomaly, "应产生 avg_wait_time_minutes anomaly");
        assertEquals("warning", anomaly.path("severity").asText());
        assertTrue(anomaly.path("robust_z").asDouble() >= 3.0,
                "|robust_z| 应 >= 3");
        assertEquals(8, anomaly.path("n").asInt());
        assertTrue(anomaly.has("historical_median"));
        assertTrue(anomaly.has("mad"));
        assertEquals(12.0, anomaly.path("current").asDouble(), 1e-6);
    }

    // ==================== H11 ====================
    @Test
    void h11_mixedSourceStatusEmitsMissingSourceWarning(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.2));
        // 8 个邻居:3 present + 5 missing → missing 占比 5/8 = 62.5% > 50%
        writeSummary(summaryDir, standard("p1", "lunch", "sha1:abc", "present", 4.0));
        writeSummary(summaryDir, standard("p2", "lunch", "sha1:abc", "present", 4.1));
        writeSummary(summaryDir, standard("p3", "lunch", "sha1:abc", "present", 4.2));
        for (int i = 0; i < 5; i++) {
            writeSummary(summaryDir, standard("m" + i, "lunch", "sha1:abc", "missing", 4.3 + i * 0.05));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(8, result.path("basis").path("matched_reports").asInt(),
                "missing 状态摘要也参与基线");
        assertTrue(hasWarning(result, "MISSING_SOURCE_NEIGHBORS"));
        assertEquals(4, result.path("basis").path("source_status_counts").path("present").asInt());
        assertEquals(5, result.path("basis").path("source_status_counts").path("missing").asInt());
    }

    // ==================== H12 ====================
    @Test
    void h12_parseFailedSummariesExcludedFromCandidates(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.0));
        for (int i = 0; i < 5; i++) {
            writeSummary(summaryDir, standard("ok" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }
        // 2 份 parse_failed
        ObjectNode bad1 = buildSummary("bad1", "lunch", "sha1:abc", "unverified",
                false, false, 300, 2, 8, 200, 1, 0.13, "sunny", 15,
                null, null, null, null, null, null, null);
        writeSummary(summaryDir, bad1);
        ObjectNode bad2 = buildSummary("bad2", "lunch", "sha1:abc", "unverified",
                false, false, 300, 2, 8, 200, 1, 0.13, "sunny", 15,
                null, null, null, null, null, null, null);
        writeSummary(summaryDir, bad2);

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(8, result.path("basis").path("corpus_size").asInt(),
                "corpus_size 包含全部摘要");
        assertEquals(6, result.path("basis").path("usable_summaries").asInt(),
                "usable_summaries 排除 parse_failed");
        assertEquals(5, result.path("basis").path("matched_reports").asInt());
        assertTrue(result.path("basis").path("excluded_counts").path("parse_failed").asInt() >= 2);
    }

    // ==================== H13 ====================
    @Test
    void h13_invariantFailedSummariesExcludedFromCandidates(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.0));
        for (int i = 0; i < 4; i++) {
            writeSummary(summaryDir, standard("ok" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }
        // 1 份 invariant_failed
        ObjectNode iv = buildSummary("iv1", "lunch", "sha1:abc", "present",
                true, false, 300, 2, 8, 200, 1, 0.13, "sunny", 15,
                0.05, 4.5, 4.5, 0.62, 0.16, 18, 8.4);
        writeSummary(summaryDir, iv);

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(4, result.path("basis").path("matched_reports").asInt(),
                "invariant_failed 不进 baseline");
        assertEquals(1, result.path("basis").path("excluded_counts").path("invariant_failed").asInt());
    }

    // ==================== H14 ====================
    @Test
    void h14_currentInvariantFailedStillRunsDeviation(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        // 当前 invariants 失败但 parse_status=ok
        ObjectNode cur = buildSummary("cur", "lunch", "sha1:abc", "present",
                true, false, 300, 2, 8, 200, 1, 0.13, "sunny", 15,
                0.05, 12.0, 12.0, 0.62, 0.16, 18, 8.4);
        writeSummary(summaryDir, cur);
        double[] baseline = {4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6};
        for (int i = 0; i < baseline.length; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:abc", "present", baseline[i]));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertTrue(hasCheck(result, "INVARIANT_FAILURE"),
                "当前 invariant_failed 应输出 INVARIANT_FAILURE check");
        // 仍做偏离判断
        JsonNode anomaly = anomalyOf(result, "avg_wait_time_minutes");
        assertNotNull(anomaly, "当前 invariant_failed 不阻断偏离判断");
    }

    // ==================== H15 ====================
    @Test
    void h15_currentMetricNullEmitsMetricMissingWarning(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        ObjectNode cur = standard("cur", "lunch", "sha1:abc", "present", 4.2);
        ((ObjectNode) cur.path("metrics")).putNull("avg_wait_time_minutes");
        writeSummary(summaryDir, cur);
        for (int i = 0; i < 6; i++) {
            writeSummary(summaryDir, standard("nb" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertTrue(hasWarning(result, "METRIC_MISSING:avg_wait_time_minutes"));
        assertEquals(null, anomalyOf(result, "avg_wait_time_minutes"),
                "缺失 metric 不进 anomaly");
    }

    // ==================== H16 ====================
    @Test
    void h16_selfReportIdExcludedFromBaseline(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("myid", "lunch", "sha1:abc", "present", 4.2));
        for (int i = 0; i < 5; i++) {
            writeSummary(summaryDir, standard("other" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("myid");

        assertEquals(5, result.path("basis").path("matched_reports").asInt(),
                "自身不计入 baseline");
        assertEquals(1, result.path("basis").path("excluded_counts").path("self").asInt());
        assertTrue(result.path("basis").path("self_excluded").asBoolean());
    }

    // ==================== H17 ====================
    @Test
    void h17_storeThrowsEmitsInternalErrorCheckNotPropagating(@TempDir Path tmp) {
        Path analysisStore = tmp.resolve("analysis-store");
        // 用一个会抛异常的 store 子类
        ReportSummaryStore evilStore = new ReportSummaryStore(analysisStore, tmp.resolve("reports"),
                mapper, new ReportSummaryExtractor(mapper)) {
            @Override
            public List<JsonNode> list() {
                throw new RuntimeException("simulated store failure");
            }
        };
        evilStore.validateConfiguration();

        HistoricalDiagnosticsService svc = newService(evilStore);
        ObjectNode result = assertDoesNotThrow(() -> svc.diagnose("any"),
                "服务必须永不向上抛异常");

        assertSchemaShape(result);
        assertTrue(hasCheck(result, "DIAGNOSTICS_INTERNAL_ERROR"),
                "服务内部异常应输出 DIAGNOSTICS_INTERNAL_ERROR check");
    }

    // ==================== H18 ====================
    @Test
    void h18_allStaleNeighborsEmitsStaleNeighborsWarning(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.2));
        // 全部邻居 stale
        for (int i = 0; i < 6; i++) {
            writeSummary(summaryDir, standard("st" + i, "lunch", "sha1:abc", "stale", 4.0 + i * 0.1));
        }

        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");

        assertEquals(6, result.path("basis").path("matched_reports").asInt(),
                "stale 摘要参与基线");
        assertTrue(hasWarning(result, "STALE_NEIGHBORS"));
    }

    // ==================== 辅助:验证 schema 不出禁字段 ====================
    @Test
    void schemaNeverExposesQualityScoreOrLevel(@TempDir Path tmp) throws IOException {
        Path analysisStore = tmp.resolve("analysis-store");
        Path summaryDir = analysisStore.resolve("report-summaries");
        writeSummary(summaryDir, standard("cur", "lunch", "sha1:abc", "present", 4.0));
        for (int i = 0; i < 8; i++) {
            writeSummary(summaryDir, standard("n" + i, "lunch", "sha1:abc", "present", 4.0 + i * 0.1));
        }
        ObjectNode result = newService(newStore(analysisStore, tmp.resolve("reports"))).diagnose("cur");
        // 全字段递归扫描:任何键名包含 score / level / tier 都不应出现
        List<String> forbidden = List.of("quality_score", "level", "tier", "score");
        scanForbidden(result, forbidden, "");
    }

    private void scanForbidden(JsonNode node, List<String> forbidden, String pathTrail) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                String childPath = pathTrail + "/" + name;
                for (String f : forbidden) {
                    assertFalse(name.equals(f),
                            "禁出现字段 '" + f + "' 在 " + childPath);
                }
                scanForbidden(node.get(name), forbidden, childPath);
            });
        } else if (node.isArray()) {
            int idx = 0;
            for (JsonNode child : node) {
                scanForbidden(child, forbidden, pathTrail + "[" + (idx++) + "]");
            }
        }
    }

    @SuppressWarnings("unused")
    private static List<Double> seq(double... xs) {
        List<Double> out = new ArrayList<>();
        for (double x : xs) out.add(x);
        return out;
    }
}
