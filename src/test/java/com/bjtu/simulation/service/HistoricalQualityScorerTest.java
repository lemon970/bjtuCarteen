package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * 阶段 3 RFC-003 测试:Historical Quality Score 单元用例 Q1–Q25。
 *
 * Scorer 输入是 phase 2 输出的 historical_diagnostics ObjectNode,phase 3 不再访问
 * ReportSummaryStore / 报告文件 / C++ binary。所有用例直接构造 diagnostics 桩。
 *
 * 关键语义守约束:
 *  - quality_score 是 数据质量与历史可比性评分,不是业务质量分。
 *  - 禁出现业务含义评分字段 / 等级标签(business_score / performance_score / ranking_score
 *    / optimization_score / excellent / good / bad / perfect / terrible)。
 *  - level 必须始终输出。
 *  - score_available=false 时不出 quality_score / quality_score_percent / dimensions / penalties。
 *  - warnings 始终至少含 QUALITY_SCORE_IS_DIAGNOSTIC_ONLY + NOT_A_BUSINESS_PERFORMANCE_SCORE。
 */
class HistoricalQualityScorerTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
    private final HistoricalQualityScorer scorer = new HistoricalQualityScorer(mapper);

    /** 构造一个"健康基线"的 diagnostics:scenario_id_exact、matched=10、无 anomaly、无 warning。 */
    private ObjectNode healthyDiagnostics(String reportId) {
        ObjectNode d = mapper.createObjectNode();
        d.put("enabled", true);
        d.put("schema_version", "1.0");
        d.put("computed_by", "java-summary-store");
        d.put("computed_at_epoch_millis", 1747641600000L);

        ObjectNode basis = d.putObject("basis");
        basis.put("summary_store_path", "analysis-store/report-summaries");
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

        ObjectNode excluded = basis.putObject("excluded_counts");
        excluded.put("parse_failed", 4);
        excluded.put("invariant_failed", 3);
        excluded.put("self", 1);

        d.putArray("checks");
        d.putArray("anomalies");
        d.putArray("warnings");
        return d;
    }

    private void addCheck(ObjectNode diagnostics, String code, String severity) {
        ArrayNode checks = (ArrayNode) diagnostics.path("checks");
        ObjectNode c = checks.addObject();
        c.put("code", code);
        c.put("severity", severity);
        c.put("message", code + " test message");
    }

    private void addAnomaly(ObjectNode diagnostics, String metric, String severity, double robustZ) {
        ArrayNode anomalies = (ArrayNode) diagnostics.path("anomalies");
        ObjectNode a = anomalies.addObject();
        a.put("metric", metric);
        a.put("current", 12.4);
        a.put("historical_median", 4.2);
        a.put("mad", 1.2);
        a.put("robust_z", robustZ);
        a.put("severity", severity);
        a.put("n", 8);
    }

    private void addWarning(ObjectNode diagnostics, String warning) {
        ((ArrayNode) diagnostics.path("warnings")).add(warning);
    }

    private boolean hasWarning(JsonNode result, String warning) {
        for (JsonNode w : result.path("warnings")) if (warning.equals(w.asText())) return true;
        return false;
    }

    private double dimensionScore(JsonNode result, String dim) {
        return result.path("dimensions").path(dim).path("score").asDouble();
    }

    private boolean dimensionHasReason(JsonNode result, String dim, String reasonPrefix) {
        for (JsonNode r : result.path("dimensions").path(dim).path("reasons")) {
            if (r.asText().startsWith(reasonPrefix)) return true;
        }
        return false;
    }

    private void assertEnvelopeCommon(JsonNode result) {
        assertEquals(true, result.path("enabled").asBoolean());
        assertEquals("1.1", result.path("schema_version").asText());
        assertEquals("java-quality-scorer", result.path("computed_by").asText());
        assertTrue(result.has("computed_at_epoch_millis"));
        assertTrue(result.has("score_available"));
        assertTrue(result.has("level"));
        assertTrue(result.has("basis"));
        assertTrue(result.path("basis").path("diagnostics_used").asBoolean());
        // 免责声明守约束
        assertTrue(hasWarning(result, "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY"));
        assertTrue(hasWarning(result, "NOT_A_BUSINESS_PERFORMANCE_SCORE"));
    }

    private void assertScoreUnavailable(JsonNode result, String expectedReason, String expectedLevel) {
        assertEnvelopeCommon(result);
        assertFalse(result.path("score_available").asBoolean());
        assertEquals(expectedLevel, result.path("level").asText());
        assertEquals(expectedReason, result.path("unavailable_reason").asText());
        // 不应输出 quality_score / quality_score_percent / dimensions / penalties
        assertFalse(result.has("quality_score"), "quality_score must not appear when unavailable");
        assertFalse(result.has("quality_score_percent"), "quality_score_percent must not appear when unavailable");
        assertFalse(result.has("dimensions"), "dimensions must not appear when unavailable");
        assertFalse(result.has("penalties"), "penalties must not appear when unavailable");
    }

    private void assertScoreAvailable(JsonNode result) {
        assertEnvelopeCommon(result);
        assertTrue(result.path("score_available").asBoolean());
        assertTrue(result.has("quality_score"));
        assertTrue(result.has("quality_score_percent"));
        assertTrue(result.has("dimensions"));
        assertTrue(result.has("penalties"));
        // 4 维度齐全
        assertTrue(result.path("dimensions").has("availability"));
        assertTrue(result.path("dimensions").has("comparability"));
        assertTrue(result.path("dimensions").has("historical_conformity"));
        assertTrue(result.path("dimensions").has("reliability"));
        // 数值范围
        double score = result.path("quality_score").asDouble();
        int percent = result.path("quality_score_percent").asInt();
        assertTrue(score >= 0.0 && score <= 1.0,
                "quality_score must be in [0,1], got " + score);
        assertTrue(percent >= 0 && percent <= 100,
                "quality_score_percent must be in [0,100], got " + percent);
        assertFalse(Double.isNaN(score) || Double.isInfinite(score));
    }

    // ==================== Q1 ====================
    @Test
    void q1_nullDiagnosticsScoreUnavailable() {
        ObjectNode result = assertDoesNotThrow(() -> scorer.score(null, "rid"));
        assertScoreUnavailable(result, "DIAGNOSTICS_NOT_PROVIDED", "unavailable");
    }

    // ==================== Q2(并入 §8.1 决策) ====================
    /**
     * scorer.score 的签名收紧到 ObjectNode,非 ObjectNode 在编译期即被拦下,
     * 调用方必须在 controller 层先做类型检查(对应 phase 2 maybeMergeDiagnostics 中
     * `payload instanceof ObjectNode` 的等价处理)。因此 phase 3 不存在 runtime 的
     * NOT_OBJECT 分支;此处用一条等价用例守"null 触发 NOT_PROVIDED"行为。
     */
    @Test
    void q2_signatureRestrictsToObjectNode_nullPathStillSafe() {
        // 重复 Q1 语义,只为锁定"调用方违规传入将不会从 scorer 抛出"
        ObjectNode result = assertDoesNotThrow(() -> scorer.score(null, ""));
        assertScoreUnavailable(result, "DIAGNOSTICS_NOT_PROVIDED", "unavailable");
    }

    // ==================== Q3 ====================
    @Test
    void q3_missingSummaryGate() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.get("basis")).put("current_summary_present", false);
        addCheck(d, "MISSING_SUMMARY", "error");

        ObjectNode result = scorer.score(d, "rid");
        assertScoreUnavailable(result, "MISSING_SUMMARY", "unavailable");
    }

    // ==================== Q4 ====================
    @Test
    void q4_currentParseFailedGate() {
        ObjectNode d = healthyDiagnostics("rid");
        addCheck(d, "CURRENT_PARSE_FAILED", "error");
        ObjectNode result = scorer.score(d, "rid");
        assertScoreUnavailable(result, "CURRENT_PARSE_FAILED", "unavailable");
    }

    // ==================== Q5 ====================
    @Test
    void q5_diagnosticsInternalErrorGate() {
        ObjectNode d = healthyDiagnostics("rid");
        addCheck(d, "DIAGNOSTICS_INTERNAL_ERROR", "error");
        ObjectNode result = scorer.score(d, "rid");
        assertScoreUnavailable(result, "DIAGNOSTICS_INTERNAL_ERROR", "unavailable");
    }

    // ==================== Q6 ====================
    @Test
    void q6_emptyCorpusAndNoCurrentGate() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("corpus_size", 0);
        ((ObjectNode) d.path("basis")).put("current_summary_present", false);
        ObjectNode result = scorer.score(d, "rid");
        assertScoreUnavailable(result, "EMPTY_CORPUS_AND_NO_CURRENT", "unavailable");
    }

    // ==================== Q7 ====================
    @Test
    void q7_healthyBaselineYieldsReliable() {
        ObjectNode d = healthyDiagnostics("rid");
        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        assertEquals("reliable", result.path("level").asText(),
                "matched>=10, scenario_id_exact, no anomaly, healthy → reliable");
        assertTrue(result.path("quality_score").asDouble() >= 0.85);
    }

    // ==================== Q8 ====================
    @Test
    void q8_warningAnomalyCapsAtCaution() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matched_reports", 8);
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        assertEquals("caution", result.path("level").asText(),
                "warning anomaly 上限封 caution,即便 score 较高");
    }

    // ==================== Q9 ====================
    @Test
    void q9_matched2ConformitySkipped() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matched_reports", 2);

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        assertTrue(dimensionScore(result, "comparability") < 0.6,
                "matched=2: comparability 应 < 0.6");
        assertTrue(dimensionHasReason(result, "historical_conformity", "stability_skipped")
                || dimensionHasReason(result, "historical_conformity", "conformity_skipped"),
                "应有 stability_skipped 或 conformity_skipped reason");
    }

    // ==================== Q10 ====================
    @Test
    void q10_invariantFailureForcesUnreliable() {
        ObjectNode d = healthyDiagnostics("rid");
        addCheck(d, "INVARIANT_FAILURE", "error");
        // 即便 score 算出来高,level 也必须降到 unreliable
        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        assertEquals("unreliable", result.path("level").asText(),
                "INVARIANT_FAILURE error 强降 level=unreliable");
    }

    // ==================== Q11 ====================
    @Test
    void q11_metricMissingDeducts() {
        ObjectNode d = healthyDiagnostics("rid");
        addWarning(d, "METRIC_MISSING:avg_wait_time_minutes");
        addWarning(d, "METRIC_MISSING:typical_wait_time_minutes");
        addWarning(d, "METRIC_MISSING:seat_utilization_rate");
        addWarning(d, "METRIC_MISSING:takeaway_rate");

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        // 4 条 → -0.03 × 4 = -0.12
        double avail = dimensionScore(result, "availability");
        assertTrue(avail >= 0.87 && avail <= 0.89,
                "4 条 METRIC_MISSING 应扣 0.12,得 ~0.88,实际=" + avail);
    }

    // ==================== Q12 ====================
    @Test
    void q12_metricMissingCappedAt015() {
        ObjectNode d = healthyDiagnostics("rid");
        for (int i = 0; i < 6; i++) addWarning(d, "METRIC_MISSING:m" + i);

        ObjectNode result = scorer.score(d, "rid");
        double avail = dimensionScore(result, "availability");
        assertTrue(avail >= 0.84 && avail <= 0.86,
                "6 条 METRIC_MISSING 应封顶扣 0.15,得 0.85,实际=" + avail);
    }

    // ==================== Q13 ====================
    @Test
    void q13_strategyNoneHeavyComparabilityDeduct() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matching_strategy", "none");
        ((ObjectNode) d.path("basis")).put("matched_reports", 0);

        ObjectNode result = scorer.score(d, "rid");
        // matched=0 不会触发 score_available=false 因为 corpus_size=120 且 current 完整
        assertScoreAvailable(result);
        double comp = dimensionScore(result, "comparability");
        assertTrue(comp <= 0.0001,
                "strategy=none + matched=0 → comparability 重扣到 0,实际=" + comp);
    }

    // ==================== Q14 ====================
    @Test
    void q14_strategySimilarConfigDeducts() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matching_strategy", "similar_config");

        ObjectNode result = scorer.score(d, "rid");
        double comp = dimensionScore(result, "comparability");
        // -0.20(similar)而 matched=10 不再扣;预期 0.80
        assertTrue(comp >= 0.79 && comp <= 0.81,
                "similar_config 应扣 0.20,得 0.80,实际=" + comp);
    }

    // ==================== Q15 ====================
    @Test
    void q15_weakSourceStatusNeighborsDeduct() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matched_reports", 12);
        // 邻居中 missing+deleted+unverified = 10 / 12 > 0.5
        ObjectNode statusCounts = (ObjectNode) d.path("basis").path("source_status_counts");
        statusCounts.put("present", 2);
        statusCounts.put("stale", 0);
        statusCounts.put("missing", 8);
        statusCounts.put("deleted", 1);
        statusCounts.put("unverified", 1);
        addWarning(d, "MISSING_SOURCE_NEIGHBORS");

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        assertTrue(dimensionHasReason(result, "reliability", "weak_source_status_neighbors"),
                "应有 weak_source_status_neighbors reason");
    }

    // ==================== Q16 ====================
    @Test
    void q16_staleNeighborsDeducts() {
        ObjectNode d = healthyDiagnostics("rid");
        addWarning(d, "STALE_NEIGHBORS");
        ObjectNode result = scorer.score(d, "rid");
        assertTrue(dimensionHasReason(result, "reliability", "stale_neighbors"));
    }

    // ==================== Q17 ====================
    @Test
    void q17_missingSourceNeighborsLightDeducts() {
        ObjectNode d = healthyDiagnostics("rid");
        addWarning(d, "MISSING_SOURCE_NEIGHBORS");
        ObjectNode result = scorer.score(d, "rid");
        assertTrue(dimensionHasReason(result, "reliability", "missing_source_neighbors"));
    }

    // ==================== Q18 ====================
    @Test
    void q18_madZeroCappedAt010() {
        ObjectNode d = healthyDiagnostics("rid");
        for (int i = 0; i < 10; i++) addWarning(d, "MAD_ZERO:m" + i);
        ObjectNode result = scorer.score(d, "rid");
        double conf = dimensionScore(result, "historical_conformity");
        // MAD_ZERO 仅扣 ≤0.10 (-0.02 × n 封顶)
        assertTrue(conf >= 0.89 && conf <= 0.91,
                "MAD_ZERO 应封顶扣 0.10,得 0.90,实际=" + conf);
    }

    // ==================== Q19 ====================
    @Test
    void q19_mixedAnomaliesDeduct() {
        ObjectNode d = healthyDiagnostics("rid");
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);
        addAnomaly(d, "typical_wait_time_minutes", "warning", 3.5);
        addAnomaly(d, "abandonment_rate", "info", 2.5);
        addAnomaly(d, "seat_utilization_rate", "info", 2.2);
        addAnomaly(d, "takeaway_rate", "info", 2.1);
        addAnomaly(d, "max_total_queue_size", "info", 2.6);

        ObjectNode result = scorer.score(d, "rid");
        // 2 warning(-0.30) + 4 info(-0.20)= -0.50;dimension 夹紧到 0.50
        double conf = dimensionScore(result, "historical_conformity");
        assertTrue(conf >= 0.49 && conf <= 0.51,
                "2W + 4I anomalies 应得 conformity ~0.50,实际=" + conf);
    }

    // ==================== Q20 ====================
    @Test
    void q20_forbiddenBusinessFieldsNeverAppear() {
        ObjectNode d = healthyDiagnostics("rid");
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);
        addCheck(d, "INVARIANT_FAILURE", "error");
        addWarning(d, "MISSING_SOURCE_NEIGHBORS");

        ObjectNode result = scorer.score(d, "rid");
        // 仅禁业务含义评分字段和等级标签,不禁 quality_score / level / score(在 dimensions.* 内合法)
        List<String> forbiddenKeys = List.of(
                "business_score", "performance_score", "ranking_score", "optimization_score");
        scanForbiddenKeys(result, forbiddenKeys, "");
        // value 层面禁夸张等级标签
        List<String> forbiddenValues = List.of(
                "excellent", "good", "bad", "perfect", "terrible");
        scanForbiddenValues(result, forbiddenValues, "");
    }

    // ==================== Q21 ====================
    @Test
    void q21_scoreInRangeAlways() {
        ObjectNode d = healthyDiagnostics("rid");
        // 触发尽可能多的扣分
        ((ObjectNode) d.path("basis")).put("matching_strategy", "none");
        ((ObjectNode) d.path("basis")).put("matched_reports", 0);
        addCheck(d, "INVARIANT_FAILURE", "error");
        for (int i = 0; i < 6; i++) addWarning(d, "METRIC_MISSING:m" + i);
        addWarning(d, "STALE_NEIGHBORS");
        addWarning(d, "MISSING_SOURCE_NEIGHBORS");

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        double s = result.path("quality_score").asDouble();
        int p = result.path("quality_score_percent").asInt();
        assertTrue(s >= 0.0 && s <= 1.0);
        assertTrue(p >= 0 && p <= 100);
    }

    // ==================== Q22 ====================
    @Test
    void q22_noNanOrInfinityUnderEdgeInputs() {
        // 故意做非常规输入:matched_reports = -1,corpus_size = 0
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matched_reports", -1);
        ((ObjectNode) d.path("basis")).put("corpus_size", 0);
        ((ObjectNode) d.path("basis")).put("usable_summaries", 0);
        ObjectNode result = scorer.score(d, "rid");
        // 不论 score_available 如何,绝不输出 NaN/Infinity
        if (result.path("score_available").asBoolean()) {
            double s = result.path("quality_score").asDouble();
            assertFalse(Double.isNaN(s));
            assertFalse(Double.isInfinite(s));
        }
        assertEnvelopeCommon(result);
    }

    // ==================== Q23 ====================
    @Test
    void q23_internalThrowableYieldsScoreUnavailable() {
        // 注入一个抛 RuntimeException 的 ObjectNode 子类
        ObjectNode evil = new ObjectNode(mapper.getNodeFactory()) {
            @Override
            public JsonNode path(String fieldName) {
                throw new RuntimeException("simulated parse failure");
            }
        };
        ObjectNode result = assertDoesNotThrow(() -> scorer.score(evil, "rid"));
        assertScoreUnavailable(result, "QUALITY_SCORER_INTERNAL_ERROR", "unavailable");
    }

    // ==================== Q24 ====================
    @Test
    void q24_dimensionsAlwaysFourWithReasons() {
        ObjectNode d = healthyDiagnostics("rid");
        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        for (String dim : List.of("availability", "comparability", "historical_conformity", "reliability")) {
            JsonNode dn = result.path("dimensions").path(dim);
            assertTrue(dn.has("score"));
            assertTrue(dn.has("reasons"));
            assertTrue(dn.path("reasons").isArray());
        }
    }

    // ==================== Q25 ====================
    @Test
    void q25_disclaimerWarningsAlwaysPresentEvenUnavailable() {
        // available 路径
        ObjectNode r1 = scorer.score(healthyDiagnostics("rid"), "rid");
        assertTrue(hasWarning(r1, "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY"));
        assertTrue(hasWarning(r1, "NOT_A_BUSINESS_PERFORMANCE_SCORE"));
        // unavailable 路径
        ObjectNode r2 = scorer.score(null, "rid");
        assertTrue(hasWarning(r2, "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY"));
        assertTrue(hasWarning(r2, "NOT_A_BUSINESS_PERFORMANCE_SCORE"));
    }

    // ==================== 额外:basis.diagnostics_schema_version 透传 ====================
    @Test
    void basisShouldEchoDiagnosticsSchemaVersion() {
        ObjectNode d = healthyDiagnostics("rid");
        d.put("schema_version", "1.0");
        ObjectNode result = scorer.score(d, "rid");
        assertEquals("1.0", result.path("basis").path("diagnostics_schema_version").asText());
    }

    // ==================== PR-004A.1:baseline_confidence / baseline_strategy 透传 ====================

    /** 在 diagnostics 上挂一个 schema 1.1 的 baseline 子树。 */
    private void attachBaseline(ObjectNode diagnostics, String confidence, String strategy) {
        diagnostics.put("schema_version", "1.1");
        ObjectNode baseline = ((ObjectNode) diagnostics.path("basis")).putObject("baseline");
        baseline.put("strategy", strategy);
        baseline.put("confidence", confidence);
        baseline.put("matched_reports", diagnostics.path("basis").path("matched_reports").asInt(0));
        baseline.put("effective_sample_size", (double) diagnostics.path("basis").path("matched_reports").asInt(0));
        baseline.putNull("distance");
        baseline.putNull("weights");
        baseline.putNull("global_reference");
        baseline.putArray("limitations");
    }

    /** schema 1.1 + baseline.confidence=low 应透传到 historical_quality.basis.baseline_confidence。 */
    @Test
    void basisShouldEchoBaselineConfidenceLow() {
        ObjectNode d = healthyDiagnostics("rid");
        attachBaseline(d, "low", "weighted_nearest_neighbors");
        ObjectNode result = scorer.score(d, "rid");
        assertEquals("low", result.path("basis").path("baseline_confidence").asText());
    }

    /** schema 1.1 + baseline.strategy=weighted_nearest_neighbors 透传到 basis.baseline_strategy。 */
    @Test
    void basisShouldEchoBaselineStrategyWnn() {
        ObjectNode d = healthyDiagnostics("rid");
        attachBaseline(d, "low", "weighted_nearest_neighbors");
        ObjectNode result = scorer.score(d, "rid");
        assertEquals("weighted_nearest_neighbors",
                result.path("basis").path("baseline_strategy").asText());
    }

    /** confidence=high 透传。 */
    @Test
    void basisShouldEchoBaselineConfidenceHigh() {
        ObjectNode d = healthyDiagnostics("rid");
        attachBaseline(d, "high", "scenario_id_exact");
        ObjectNode result = scorer.score(d, "rid");
        assertEquals("high", result.path("basis").path("baseline_confidence").asText());
        assertEquals("scenario_id_exact", result.path("basis").path("baseline_strategy").asText());
    }

    /** confidence=none 透传(score_available=true 路径,因为 corpus_size=120 + currentPresent)。 */
    @Test
    void basisShouldEchoBaselineConfidenceNone() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matched_reports", 0);
        ((ObjectNode) d.path("basis")).put("matching_strategy", "none");
        attachBaseline(d, "none", "none");
        ObjectNode result = scorer.score(d, "rid");
        assertEquals("none", result.path("basis").path("baseline_confidence").asText());
        assertEquals("none", result.path("basis").path("baseline_strategy").asText());
    }

    /** legacy 1.0(无 baseline 子树):baseline_confidence/strategy=unknown,字段始终存在。 */
    @Test
    void basisShouldFallbackToUnknownWhenLegacy10() {
        ObjectNode d = healthyDiagnostics("rid"); // schema_version 默认 1.0,无 baseline
        ObjectNode result = scorer.score(d, "rid");
        // 字段始终存在,使前端 switch 单路径处理
        assertTrue(result.path("basis").has("baseline_confidence"),
                "baseline_confidence 字段必须始终存在");
        assertTrue(result.path("basis").has("baseline_strategy"),
                "baseline_strategy 字段必须始终存在");
        assertEquals("unknown", result.path("basis").path("baseline_confidence").asText());
        assertEquals("unknown", result.path("basis").path("baseline_strategy").asText());
    }

    /** unavailable 路径(diagnostics=null)也应输出兜底 unknown,符合"字段始终存在"约束。 */
    @Test
    void basisShouldFallbackToUnknownWhenDiagnosticsNull() {
        ObjectNode result = scorer.score(null, "rid");
        assertFalse(result.path("score_available").asBoolean());
        assertEquals("unknown", result.path("basis").path("baseline_confidence").asText());
        assertEquals("unknown", result.path("basis").path("baseline_strategy").asText());
    }

    /** 透传字段不影响 scoring 公式与 level cap(回归保护:legacy 旧用例 q13 行为不变)。 */
    @Test
    void baselinePassthroughDoesNotChangeScoring() {
        ObjectNode d = healthyDiagnostics("rid");
        ((ObjectNode) d.path("basis")).put("matching_strategy", "none");
        ((ObjectNode) d.path("basis")).put("matched_reports", 0);
        // 不挂 baseline 子树 → schema 仍 1.0 → 走 legacy 公式
        ObjectNode result = scorer.score(d, "rid");
        // legacy 公式下 strategy=none + matched=0 仍应让 comparability 归零
        double comp = result.path("dimensions").path("comparability").path("score").asDouble();
        assertTrue(comp <= 0.0001,
                "legacy 1.0 + strategy=none → comparability 仍归 0(未受透传字段影响),实际=" + comp);
    }

    // ==================== 额外:penalties 与 dimension 关联 ====================
    @Test
    void penaltiesShouldReferenceDimensionAndAmount() {
        ObjectNode d = healthyDiagnostics("rid");
        addAnomaly(d, "avg_wait_time_minutes", "warning", 4.0);

        ObjectNode result = scorer.score(d, "rid");
        assertScoreAvailable(result);
        ArrayNode penalties = (ArrayNode) result.path("penalties");
        assertNotNull(penalties);
        assertTrue(penalties.size() > 0, "应至少产生 1 条 penalty");
        for (JsonNode p : penalties) {
            assertTrue(p.has("code"));
            assertTrue(p.has("dimension"));
            assertTrue(p.has("amount"));
            double amount = p.path("amount").asDouble();
            assertTrue(amount > 0.0 && amount <= 1.0,
                    "penalty.amount 应 ∈ (0,1],实际=" + amount);
        }
    }

    // ==================== 工具:递归 key 扫描 ====================

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

    @SuppressWarnings("unused")
    private void unused() { assertNull(null); } // silence unused import for assertNull
}
