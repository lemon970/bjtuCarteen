package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * RFC-004 / PR-004A:Scorer 自适应基线 Q1–Q12 用例。
 *
 * 验证 schema_version=1.1 输入下:
 *  - basis.baseline.confidence=high/medium/low/very_low/none 五档分别影响
 *    comparability 起扣、conformity 是否参与 min、level 上限。
 *  - schema_version=1.0(无 baseline 子树)输入回退旧公式,phase 3 用例零回归。
 *  - 必现免责声明、禁止业务字段约束保持。
 */
class HistoricalQualityScorerBaselineTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
    private final HistoricalQualityScorer scorer = new HistoricalQualityScorer(mapper);

    /** 构造 schema_version=1.1 的 diagnostics,默认 confidence=high。 */
    private ObjectNode adaptiveDiagnostics(String reportId, String confidence,
                                           String strategy, int matched) {
        ObjectNode d = mapper.createObjectNode();
        d.put("enabled", true);
        d.put("schema_version", "1.1");
        d.put("computed_by", "java-summary-store");
        d.put("computed_at_epoch_millis", 1747641600000L);

        ObjectNode basis = d.putObject("basis");
        basis.put("summary_store_path", "analysis-store/report-summaries");
        basis.put("current_report_id", reportId);
        basis.put("current_summary_present", true);
        basis.put("corpus_size", 120);
        basis.put("usable_summaries", 113);
        basis.put("matched_reports", matched);
        basis.put("matching_strategy", strategy);
        basis.put("self_excluded", true);

        ObjectNode statusCounts = basis.putObject("source_status_counts");
        statusCounts.put("present", 100);
        statusCounts.put("stale", 5);
        statusCounts.put("missing", 5);
        statusCounts.put("deleted", 2);
        statusCounts.put("unverified", 1);

        ObjectNode excluded = basis.putObject("excluded_counts");
        excluded.put("parse_failed", 4);
        excluded.put("invariant_failed", 3);
        excluded.put("self", 1);

        ObjectNode baseline = basis.putObject("baseline");
        baseline.put("strategy", strategy);
        baseline.put("confidence", confidence);
        baseline.put("matched_reports", matched);
        baseline.put("effective_sample_size", (double) matched);
        baseline.putNull("distance");
        baseline.putNull("weights");
        baseline.putNull("global_reference");
        baseline.putArray("limitations");

        d.putArray("checks");
        d.putArray("anomalies");
        d.putArray("warnings");
        return d;
    }

    /** schema_version=1.0 的 phase 3 旧 diagnostics。 */
    private ObjectNode legacyDiagnostics(String reportId) {
        ObjectNode d = mapper.createObjectNode();
        d.put("enabled", true);
        d.put("schema_version", "1.0");
        d.put("computed_by", "java-summary-store");
        d.put("computed_at_epoch_millis", 1747641600000L);

        ObjectNode basis = d.putObject("basis");
        basis.put("current_report_id", reportId);
        basis.put("current_summary_present", true);
        basis.put("corpus_size", 120);
        basis.put("usable_summaries", 113);
        basis.put("matched_reports", 10);
        basis.put("matching_strategy", "scenario_id_exact");
        basis.put("self_excluded", true);
        ObjectNode statusCounts = basis.putObject("source_status_counts");
        statusCounts.put("present", 100);
        statusCounts.put("stale", 5);
        statusCounts.put("missing", 5);
        statusCounts.put("deleted", 2);
        statusCounts.put("unverified", 1);
        d.putArray("checks");
        d.putArray("anomalies");
        d.putArray("warnings");
        return d;
    }

    private void addAnomaly(ObjectNode d, String metric, String severity, double z) {
        ArrayNode anomalies = (ArrayNode) d.path("anomalies");
        ObjectNode a = anomalies.addObject();
        a.put("metric", metric);
        a.put("current", 12.4);
        a.put("historical_median", 4.2);
        a.put("mad", 1.2);
        a.put("robust_z", z);
        a.put("severity", severity);
        a.put("n", 8);
    }

    private void addCheck(ObjectNode d, String code, String severity) {
        ArrayNode checks = (ArrayNode) d.path("checks");
        ObjectNode c = checks.addObject();
        c.put("code", code);
        c.put("severity", severity);
        c.put("message", code);
    }

    private boolean hasWarning(JsonNode result, String code) {
        for (JsonNode w : result.path("warnings")) if (code.equals(w.asText())) return true;
        return false;
    }

    private double dimScore(JsonNode result, String dim) {
        return result.path("dimensions").path(dim).path("score").asDouble();
    }

    private boolean dimNotApplicable(JsonNode result, String dim) {
        return result.path("dimensions").path(dim).path("not_applicable").asBoolean(false);
    }

    // ==================== Q1 ====================
    @Test
    void q1_confidenceNoneMakesConformityNotApplicable() {
        ObjectNode d = adaptiveDiagnostics("rid", "none", "none", 0);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        assertTrue(dimNotApplicable(result, "historical_conformity"));
        assertTrue(result.path("dimensions").path("historical_conformity").path("excluded_from_min").asBoolean());
        // comparability 不归零,起扣 0.50
        double comp = dimScore(result, "comparability");
        assertTrue(comp >= 0.40 && comp <= 0.55,
                "confidence=none 起扣 0.50,comparability ~0.50,实际=" + comp);
        assertEquals("caution", result.path("level").asText());
        assertTrue(hasWarning(result, "NO_COMPARABLE_HISTORY"));
    }

    // ==================== Q2 ====================
    @Test
    void q2_confidenceNoneOtherDimensionsHighStillCaution() {
        ObjectNode d = adaptiveDiagnostics("rid", "none", "none", 0);
        ObjectNode result = scorer.score(d, "rid");
        // availability + reliability 满分,comparability ~0.50,conformity 不参与 min → quality ~0.50
        double q = result.path("quality_score").asDouble();
        assertTrue(q >= 0.40 && q <= 0.55,
                "confidence=none + 其它满分:quality_score ~0.50,实际=" + q);
        assertEquals("caution", result.path("level").asText());
    }

    // ==================== Q3 ====================
    @Test
    void q3_confidenceVeryLowConformityNotApplicable() {
        ObjectNode d = adaptiveDiagnostics("rid", "very_low", "global_reference_baseline", 0);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(dimNotApplicable(result, "historical_conformity"));
        double comp = dimScore(result, "comparability");
        assertTrue(comp >= 0.65 && comp <= 0.75,
                "confidence=very_low 起扣 0.30,comparability ~0.70,实际=" + comp);
        assertEquals("caution", result.path("level").asText());
        assertTrue(hasWarning(result, "BASELINE_CONFIDENCE_VERY_LOW"));
    }

    // ==================== Q4 ====================
    @Test
    void q4_confidenceLowWarningAnomalyCapsAtCaution() {
        ObjectNode d = adaptiveDiagnostics("rid", "low", "weighted_nearest_neighbors", 5);
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        // confidence=low 上限 caution
        assertEquals("caution", result.path("level").asText());
        assertTrue(hasWarning(result, "BASELINE_CONFIDENCE_LOW"));
    }

    // ==================== Q5 ====================
    @Test
    void q5_confidenceMediumCapsAtUsable() {
        ObjectNode d = adaptiveDiagnostics("rid", "medium", "scenario_id_exact", 4);
        // 4 维都满分(strategy=scenario_id_exact 起扣 0.05;matched=4 扣 0.20)
        // 需要让 comparability 不要把分拉低太多 → 改 matched=10 让 comparability=0.95
        ((ObjectNode) d.path("basis")).put("matched_reports", 10);
        ((ObjectNode) d.path("basis").path("baseline")).put("matched_reports", 10);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        double q = result.path("quality_score").asDouble();
        assertTrue(q >= 0.85,
                "medium + 健康 → quality_score 应较高,实际=" + q);
        assertEquals("usable", result.path("level").asText(),
                "medium 上限封 usable,即便 quality≥0.85 也不能 reliable");
        assertTrue(hasWarning(result, "LEVEL_CAPPED_BY_CONFIDENCE"));
    }

    // ==================== Q6 ====================
    @Test
    void q6_confidenceHighReachReliable() {
        ObjectNode d = adaptiveDiagnostics("rid", "high", "scenario_id_exact", 10);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        double q = result.path("quality_score").asDouble();
        assertTrue(q >= 0.85,
                "high + 健康 → quality_score >= 0.85,实际=" + q);
        assertEquals("reliable", result.path("level").asText());
    }

    // ==================== Q7 ====================
    @Test
    void q7_invariantFailureForcesUnreliableEvenWithHighConfidence() {
        ObjectNode d = adaptiveDiagnostics("rid", "high", "scenario_id_exact", 10);
        addCheck(d, "INVARIANT_FAILURE", "error");
        ObjectNode result = scorer.score(d, "rid");

        assertEquals("unreliable", result.path("level").asText(),
                "INVARIANT_FAILURE 强降级优先于 confidence 上限");
    }

    // ==================== Q8 ====================
    @Test
    void q8_legacySchema10WalksOldFormula() {
        // 旧 phase 3 行为:matching_strategy=none + matched=0 → comparability ~ 0
        ObjectNode d = legacyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matching_strategy", "none");
        ((ObjectNode) d.path("basis")).put("matched_reports", 0);
        // 没有 baseline 子树 → scorer 走 legacy 分支
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        double comp = dimScore(result, "comparability");
        assertTrue(comp <= 0.0001,
                "legacy 1.0 + strategy=none → comparability 仍归 0,实际=" + comp);
    }

    // ==================== Q9 ====================
    @Test
    void q9_confidenceLowInfoAnomalyDoesNotDeductConformity() {
        ObjectNode d = adaptiveDiagnostics("rid", "low", "weighted_nearest_neighbors", 5);
        // 仅 info 级 anomaly
        addAnomaly(d, "abandonment_rate", "info", 2.5);
        ObjectNode result = scorer.score(d, "rid");

        assertTrue(result.path("score_available").asBoolean());
        // INFO 级在 confidence=low 不扣 conformity:维持满分
        double conf = dimScore(result, "historical_conformity");
        assertTrue(conf >= 0.99,
                "confidence=low + INFO anomaly:conformity 不扣分,实际=" + conf);
    }

    // ==================== Q10 ====================
    @Test
    void q10_comparabilityNotZeroWhenConfidenceNone() {
        ObjectNode d = adaptiveDiagnostics("rid", "none", "none", 0);
        ObjectNode result = scorer.score(d, "rid");

        double comp = dimScore(result, "comparability");
        assertTrue(comp >= 0.40,
                "confidence=none 时 comparability 至少 0.40,实际=" + comp);
    }

    // ==================== Q11 ====================
    @Test
    void q11_disclaimerWarningsAlwaysPresent() {
        // 三档全检
        for (String confidence : List.of("high", "low", "none")) {
            ObjectNode d = adaptiveDiagnostics("rid", confidence,
                    "high".equals(confidence) ? "scenario_id_exact" : "none",
                    "high".equals(confidence) ? 10 : 0);
            ObjectNode result = scorer.score(d, "rid");
            assertTrue(hasWarning(result, "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY"),
                    confidence + " path missing diagnostic-only disclaimer");
            assertTrue(hasWarning(result, "NOT_A_BUSINESS_PERFORMANCE_SCORE"),
                    confidence + " path missing business disclaimer");
        }
    }

    // ==================== Q12 ====================
    @Test
    void q12_forbiddenBusinessFieldsNeverAppear() {
        ObjectNode d = adaptiveDiagnostics("rid", "low", "weighted_nearest_neighbors", 5);
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);
        ObjectNode result = scorer.score(d, "rid");

        List<String> forbiddenKeys = List.of(
                "business_score", "performance_score", "ranking_score", "optimization_score");
        scanForbiddenKeys(result, forbiddenKeys, "");
        List<String> forbiddenValues = List.of(
                "excellent", "good", "bad", "perfect", "terrible");
        scanForbiddenValues(result, forbiddenValues, "");
    }

    private void scanForbiddenKeys(JsonNode node, List<String> forbidden, String trail) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                String childPath = trail + "/" + name;
                for (String f : forbidden) {
                    assertFalse(name.equals(f),
                            "禁出现 key '" + f + "' 在 " + childPath);
                }
                scanForbiddenKeys(node.get(name), forbidden, childPath);
            });
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode c : node) scanForbiddenKeys(c, forbidden, trail + "[" + (i++) + "]");
        }
    }

    private void scanForbiddenValues(JsonNode node, List<String> forbiddenValues, String trail) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name ->
                    scanForbiddenValues(node.get(name), forbiddenValues, trail + "/" + name));
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode c : node) scanForbiddenValues(c, forbiddenValues, trail + "[" + (i++) + "]");
        } else if (node.isTextual()) {
            String v = node.asText();
            for (String f : forbiddenValues) {
                assertFalse(v.equalsIgnoreCase(f),
                        "禁出现 value '" + f + "' 在 " + trail);
            }
        }
    }
}
