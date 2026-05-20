package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 历史诊断服务(阶段 2,RFC-002)。
 *
 * 仅基于 {@link ReportSummaryStore} 中的小摘要做事实层诊断,输出 historical_diagnostics 子树:
 * basis(语料/匹配规模)、checks(结构化检查)、anomalies(指标偏离)、warnings(非阻断提示)。
 *
 * 严格不做的事:
 *  - 不读 reports/*.json
 *  - 不调 C++ binary
 *  - 不调 InternalStatisticsAnalyzer
 *  - 不输出 quality_score / level / tier / score
 *
 * {@link #diagnose(String)} 永不向上抛异常;内部任何异常以 DIAGNOSTICS_INTERNAL_ERROR check 表达。
 */
@Service
public class HistoricalDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDiagnosticsService.class);

    static final String SCHEMA_VERSION = "1.1";
    static final String COMPUTED_BY = "java-summary-store";

    private static final int MIN_FULL_ANOMALY_N = 5;
    private static final int MIN_MEDIAN_ONLY_N = 3;
    private static final double ROBUST_Z_WARNING = 3.0;
    private static final double ROBUST_Z_INFO = 2.0;
    private static final double SIMILAR_RATE_PCT = 0.10;
    private static final double SIMILAR_DURATION_PCT = 0.10;
    private static final double SIMILAR_PACK_PROB_ABS = 0.05;

    // RFC-004:relaxed similar window
    private static final double RELAXED_RATE_PCT = 0.25;
    private static final double RELAXED_DURATION_PCT = 0.25;
    private static final double RELAXED_PACK_PROB_ABS = 0.15;
    private static final int RELAXED_WINDOW_DELTA = 1;
    private static final int RELAXED_TAKEAWAY_WINDOW_DELTA = 1;
    private static final double RELAXED_TOTAL_SEATS_PCT = 0.20;

    // RFC-004:weighted nearest neighbors
    private static final int WNN_TOP_K = 5;
    private static final double WNN_DISTANCE_MAX = 1.0;
    private static final double WNN_ESS_MIN = 3.0;

    private static final List<String> METRICS = List.of(
            "abandonment_rate",
            "avg_wait_time_minutes",
            "typical_wait_time_minutes",
            "seat_utilization_rate",
            "takeaway_rate",
            "max_total_queue_size",
            "avg_total_queue_size"
    );

    private final ReportSummaryStore store;
    private final ObjectMapper mapper;

    @Autowired
    public HistoricalDiagnosticsService(ReportSummaryStore store) {
        this(store, AppBeansConfig.createReportObjectMapper());
    }

    public HistoricalDiagnosticsService(ReportSummaryStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** 主入口。永不抛。 */
    public ObjectNode diagnose(String reportId) {
        ObjectNode result = newEnvelope();
        ObjectNode basis = result.putObject("basis");
        ArrayNode checks = result.putArray("checks");
        ArrayNode anomalies = result.putArray("anomalies");
        ArrayNode warnings = result.putArray("warnings");

        try {
            doDiagnose(reportId, basis, checks, anomalies, warnings);
        } catch (Throwable t) {
            log.warn("historical diagnostics failed for {}: {}", reportId, t.toString());
            // 即使发生异常,也保证 basis/checks 字段存在,主分析路径不受影响
            ensureBasisDefaults(basis, reportId);
            addCheck(checks, "DIAGNOSTICS_INTERNAL_ERROR", "error",
                    t.getClass().getSimpleName() + ":" + (t.getMessage() == null ? "" : t.getMessage()),
                    null);
        }
        return result;
    }

    private ObjectNode newEnvelope() {
        ObjectNode env = mapper.createObjectNode();
        env.put("enabled", true);
        env.put("schema_version", SCHEMA_VERSION);
        env.put("computed_by", COMPUTED_BY);
        env.put("computed_at_epoch_millis", System.currentTimeMillis());
        return env;
    }

    private void ensureBasisDefaults(ObjectNode basis, String reportId) {
        if (!basis.has("summary_store_path")) {
            basis.put("summary_store_path", safePath());
        }
        if (!basis.has("current_report_id")) {
            basis.put("current_report_id", reportId == null ? "" : reportId);
        }
        if (!basis.has("current_summary_present")) basis.put("current_summary_present", false);
        if (!basis.has("corpus_size")) basis.put("corpus_size", 0);
        if (!basis.has("usable_summaries")) basis.put("usable_summaries", 0);
        if (!basis.has("matched_reports")) basis.put("matched_reports", 0);
        if (!basis.has("matching_strategy")) basis.put("matching_strategy", "none");
        if (!basis.has("self_excluded")) basis.put("self_excluded", false);
        if (!basis.has("source_status_counts")) basis.putObject("source_status_counts");
        if (!basis.has("excluded_counts")) basis.putObject("excluded_counts");
        if (!basis.has("policy")) basis.set("policy", policyNode());
        if (!basis.has("baseline")) basis.set("baseline", emptyBaselineNode("none", "none", 0));
    }

    private void doDiagnose(String reportId, ObjectNode basis, ArrayNode checks,
                            ArrayNode anomalies, ArrayNode warnings) {
        // ---- basis 基础字段 ----
        basis.put("summary_store_path", safePath());
        basis.put("current_report_id", reportId == null ? "" : reportId);

        List<JsonNode> all = store.list(); // 阶段 1 已 sorted by filename + corrupt skip
        basis.put("corpus_size", all.size());

        // source_status 分布(基于全部摘要)
        ObjectNode statusCounts = basis.putObject("source_status_counts");
        statusCounts.put("present", 0);
        statusCounts.put("stale", 0);
        statusCounts.put("missing", 0);
        statusCounts.put("deleted", 0);
        statusCounts.put("unverified", 0);
        for (JsonNode s : all) {
            String st = s.path("source").path("source_status").asText("unverified");
            if (!statusCounts.has(st)) statusCounts.put(st, 0);
            statusCounts.put(st, statusCounts.get(st).asInt() + 1);
        }

        // ---- 排除统计 ----
        int parseFailed = 0;
        int invariantFailed = 0;
        boolean selfInCorpus = false;
        for (JsonNode s : all) {
            String parseStatus = s.path("precheck").path("parse_status").asText("ok");
            boolean invValid = s.path("precheck").path("basic_invariants_valid").asBoolean(true);
            if ("failed".equals(parseStatus)) parseFailed++;
            else if (!invValid) invariantFailed++;
            if (reportId != null && reportId.equals(s.path("report_id").asText(""))) {
                selfInCorpus = true;
            }
        }
        ObjectNode excluded = basis.putObject("excluded_counts");
        excluded.put("parse_failed", parseFailed);
        excluded.put("invariant_failed", invariantFailed);
        excluded.put("self", selfInCorpus ? 1 : 0);

        int usable = all.size() - parseFailed - invariantFailed;
        basis.put("usable_summaries", Math.max(0, usable));
        basis.put("self_excluded", selfInCorpus);
        basis.set("policy", policyNode());

        // ---- 当前摘要 ----
        JsonNode current = (reportId == null || reportId.isEmpty())
                ? null
                : store.read(reportId).orElse(null);
        boolean currentPresent = current != null;
        basis.put("current_summary_present", currentPresent);

        if (!currentPresent) {
            basis.put("matched_reports", 0);
            basis.put("matching_strategy", "none");
            basis.set("baseline", emptyBaselineNode("none", "none", 0));
            addCheck(checks, "MISSING_SUMMARY", "error",
                    "current report has no summary in analysis-store/report-summaries", null);
            addCheck(checks, "INSUFFICIENT_BASELINE", "warning",
                    "matched_reports=0 < " + MIN_FULL_ANOMALY_N + "; deviation analysis skipped",
                    contextWith("matched_reports", 0, "required_for_full", MIN_FULL_ANOMALY_N));
            return;
        }

        // 当前 summary 状态检查(在做匹配前先 emit)
        String currentSourceStatus = current.path("source").path("source_status").asText("unverified");
        if ("missing".equals(currentSourceStatus) || "deleted".equals(currentSourceStatus)) {
            warnings.add("CURRENT_SOURCE_MISSING");
        }
        String currentParseStatus = current.path("precheck").path("parse_status").asText("ok");
        if ("failed".equals(currentParseStatus)) {
            addCheck(checks, "CURRENT_PARSE_FAILED", "error",
                    "current summary parse_status=failed", null);
        }
        boolean currentInvariants = current.path("precheck").path("basic_invariants_valid").asBoolean(true);
        if (!currentInvariants) {
            ObjectNode ctx = mapper.createObjectNode();
            ArrayNode vios = ctx.putArray("violations");
            for (JsonNode v : current.path("precheck").path("invariant_violations")) {
                vios.add(v.asText());
            }
            addCheck(checks, "INVARIANT_FAILURE", "error",
                    "current summary failed basic invariants", ctx);
        }

        // ---- 候选池 ----
        List<JsonNode> pool = new ArrayList<>();
        for (JsonNode s : all) {
            if ("failed".equals(s.path("precheck").path("parse_status").asText("ok"))) continue;
            if (!s.path("precheck").path("basic_invariants_valid").asBoolean(true)) continue;
            if (reportId.equals(s.path("report_id").asText(""))) continue;
            pool.add(s);
        }

        // ---- 三层匹配(RFC-004:扩展为七层阶梯)----
        String currentScenarioId = current.path("report_meta").path("scenario_id").asText("");
        boolean hasScenario = !currentScenarioId.isEmpty();
        String currentFp = current.path("config").path("config_fingerprint").asText("");
        boolean hasFp = !currentFp.isEmpty() && !"sha1:unavailable".equals(currentFp);

        List<JsonNode> tierA = new ArrayList<>();
        if (hasScenario) {
            for (JsonNode c : pool) {
                if (currentScenarioId.equals(c.path("report_meta").path("scenario_id").asText(""))) {
                    tierA.add(c);
                }
            }
        }
        List<JsonNode> tierB = new ArrayList<>();
        if (hasFp) {
            for (JsonNode c : pool) {
                if (currentFp.equals(c.path("config").path("config_fingerprint").asText(""))) {
                    tierB.add(c);
                }
            }
        }
        List<JsonNode> tierC = new ArrayList<>();
        boolean similarPossible = canComputeSimilar(current);
        if (similarPossible) {
            for (JsonNode c : pool) {
                if (similarConfig(current, c)) tierC.add(c);
            }
        } else {
            warnings.add("SIMILAR_CONFIG_UNAVAILABLE");
        }

        // Tier D: relaxed similar config(RFC-004 §4.4)
        List<JsonNode> tierD = new ArrayList<>();
        boolean tierDHasWeatherMismatch = false;
        if (similarPossible) {
            for (JsonNode c : pool) {
                if (relaxedSimilarConfig(current, c)) {
                    tierD.add(c);
                    if (!current.path("config").path("weather_type").asText("")
                            .equals(c.path("config").path("weather_type").asText(""))) {
                        tierDHasWeatherMismatch = true;
                    }
                }
            }
        }

        String strategy;
        String confidence;
        List<JsonNode> matched;
        ObjectNode baselineNode = mapper.createObjectNode();
        baselineNode.putArray("limitations");
        boolean wnnRejectedByDistance = false;

        if (tierA.size() >= MIN_MEDIAN_ONLY_N) {
            strategy = "scenario_id_exact"; matched = tierA;
            confidence = (tierA.size() >= MIN_FULL_ANOMALY_N) ? "high" : "medium";
        } else if (tierB.size() >= MIN_MEDIAN_ONLY_N) {
            strategy = "config_fingerprint"; matched = tierB;
            confidence = (tierB.size() >= MIN_FULL_ANOMALY_N) ? "high" : "medium";
            addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
        } else if (tierC.size() >= MIN_MEDIAN_ONLY_N) {
            strategy = "similar_config"; matched = tierC;  // 旧值保留
            confidence = (tierC.size() >= MIN_FULL_ANOMALY_N) ? "medium" : "low";
            addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
        } else if (tierD.size() >= MIN_MEDIAN_ONLY_N) {
            strategy = "relaxed_similar_config"; matched = tierD;
            confidence = "low";
            addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
            addLimitation(baselineNode, "RELAXED_MATCH_USED");
            if (tierDHasWeatherMismatch) warnings.add("RELAXED_WEATHER");
        } else {
            // Tier E: WNN
            WnnResult wnn = similarPossible ? computeWnn(current, pool) : null;
            if (wnn != null && wnn.neighbors.size() >= MIN_MEDIAN_ONLY_N && wnn.ess >= WNN_ESS_MIN) {
                strategy = "weighted_nearest_neighbors";
                matched = wnn.neighbors;
                confidence = "low";
                addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
                addLimitation(baselineNode, "RELAXED_MATCH_USED");
                addLimitation(baselineNode, "WNN_USED");
                writeWnnFields(baselineNode, wnn);
                warnings.add("WNN_USED");
            } else {
                if (wnn != null && wnn.allRejectedByDistance) wnnRejectedByDistance = true;
                // Legacy fallback:A/B/C 各自 1-2 命中时回退为该档(保留 phase 2 既有 H7 行为)。
                if (!tierA.isEmpty()) {
                    strategy = "scenario_id_exact"; matched = tierA; confidence = "low";
                    addLimitation(baselineNode, "INSUFFICIENT_TIER_SAMPLES");
                } else if (!tierB.isEmpty()) {
                    strategy = "config_fingerprint"; matched = tierB; confidence = "low";
                    addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
                    addLimitation(baselineNode, "INSUFFICIENT_TIER_SAMPLES");
                } else if (!tierC.isEmpty()) {
                    strategy = "similar_config"; matched = tierC; confidence = "low";
                    addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
                    addLimitation(baselineNode, "INSUFFICIENT_TIER_SAMPLES");
                } else if (pool.size() >= MIN_MEDIAN_ONLY_N) {
                    // Tier F: global reference baseline
                    strategy = "global_reference_baseline";
                    matched = Collections.emptyList();
                    confidence = "very_low";
                    addLimitation(baselineNode, "NO_EXACT_SCENARIO_MATCH");
                    addLimitation(baselineNode, "GLOBAL_REFERENCE_ONLY");
                    writeGlobalReference(baselineNode, pool);
                    warnings.add("GLOBAL_REFERENCE_ONLY");
                    warnings.add("NOT_AN_OUTLIER_TEST");
                } else {
                    strategy = "none";
                    matched = Collections.emptyList();
                    confidence = "none";
                    addLimitation(baselineNode, "NO_COMPARABLE_HISTORY");
                    warnings.add("NO_COMPARABLE_HISTORY");
                    addCheck(checks, "NO_COMPARABLE_HISTORY", "warning",
                            "no usable historical summaries to compare against (excluding self)",
                            contextWith("usable_summaries_excluding_self", pool.size(),
                                    "required_for_global", MIN_MEDIAN_ONLY_N));
                }
            }
        }

        basis.put("matching_strategy", strategy);
        basis.put("matched_reports", matched.size());
        baselineNode.put("strategy", strategy);
        baselineNode.put("confidence", confidence);
        baselineNode.put("matched_reports", matched.size());
        // ESS:对非 WNN 档,ESS = matched_reports;对 WNN 档已在 writeWnnFields 中写入
        if (!"weighted_nearest_neighbors".equals(strategy)) {
            baselineNode.put("effective_sample_size", round2((double) matched.size()));
            if (!baselineNode.has("distance")) baselineNode.putNull("distance");
            if (!baselineNode.has("weights")) baselineNode.putNull("weights");
        }
        if (!baselineNode.has("global_reference")) baselineNode.putNull("global_reference");
        basis.set("baseline", baselineNode);

        // confidence 相关 warnings
        if ("low".equals(confidence)) warnings.add("BASELINE_CONFIDENCE_LOW");
        else if ("very_low".equals(confidence)) warnings.add("BASELINE_CONFIDENCE_VERY_LOW");

        // 阶梯过渡 checks
        switch (strategy) {
            case "relaxed_similar_config":
            case "weighted_nearest_neighbors":
            case "global_reference_baseline":
                addCheck(checks, "RELAXED_BASELINE_USED", "info",
                        "selected relaxed baseline strategy: " + strategy, null);
                break;
            default:
                break;
        }
        if (wnnRejectedByDistance) {
            addCheck(checks, "BASELINE_REJECTED_DISTANCE", "info",
                    "WNN candidates rejected: median distance exceeded threshold "
                            + WNN_DISTANCE_MAX, null);
        }

        // ---- source_status 邻居告警 ----
        if (!matched.isEmpty()) {
            int allStale = 0;
            int missingLike = 0;
            for (JsonNode n : matched) {
                String st = n.path("source").path("source_status").asText("unverified");
                if ("stale".equals(st)) allStale++;
                if ("missing".equals(st) || "deleted".equals(st) || "unverified".equals(st)) {
                    missingLike++;
                }
            }
            if (allStale == matched.size()) warnings.add("STALE_NEIGHBORS");
            if (matched.size() > 0 && missingLike * 2 > matched.size()) {
                warnings.add("MISSING_SOURCE_NEIGHBORS");
            }
        }

        // ---- INSUFFICIENT_BASELINE 检查 ----
        if (matched.size() < MIN_FULL_ANOMALY_N) {
            addCheck(checks, "INSUFFICIENT_BASELINE", "warning",
                    "matched_reports=" + matched.size() + " < " + MIN_FULL_ANOMALY_N
                            + "; deviation analysis " + (matched.size() < MIN_MEDIAN_ONLY_N ? "skipped" : "median-only"),
                    contextWith("matched_reports", matched.size(), "required_for_full", MIN_FULL_ANOMALY_N));
        }

        // ---- 指标偏离:global / none 不参与 ----
        if ("global_reference_baseline".equals(strategy) || "none".equals(strategy)) {
            // 收集 metric_missing 提示但不计算 anomaly
            for (String metric : METRICS) {
                JsonNode curMetric = current.path("metrics").path(metric);
                if (curMetric.isMissingNode() || curMetric.isNull() || !curMetric.isNumber()) {
                    if (currentPresent) warnings.add("METRIC_MISSING:" + metric);
                } else {
                    double cur = curMetric.asDouble();
                    if (Double.isNaN(cur) || Double.isInfinite(cur)) {
                        warnings.add("METRIC_NON_FINITE:" + metric);
                    }
                }
            }
            return;
        }

        // ---- WNN 模式 anomaly:info 级降级为 INFO_ANOMALY_HINT 不入 anomalies ----
        boolean wnnMode = "weighted_nearest_neighbors".equals(strategy);

        for (String metric : METRICS) {
            JsonNode curMetric = current.path("metrics").path(metric);
            if (curMetric.isMissingNode() || curMetric.isNull() || !curMetric.isNumber()) {
                if (currentPresent) warnings.add("METRIC_MISSING:" + metric);
                continue;
            }
            double cur = curMetric.asDouble();
            if (Double.isNaN(cur) || Double.isInfinite(cur)) {
                warnings.add("METRIC_NON_FINITE:" + metric);
                continue;
            }

            double[] xs = collectMetric(matched, metric);
            int n = xs.length;
            if (n < MIN_MEDIAN_ONLY_N) continue;

            double median = median(xs);
            if (n < MIN_FULL_ANOMALY_N) {
                continue;
            }

            double mad = medianAbsoluteDeviation(xs, median);
            if (mad == 0.0) {
                warnings.add("MAD_ZERO:" + metric);
                continue;
            }
            double robustZ = 0.6745 * (cur - median) / mad;
            double absZ = Math.abs(robustZ);
            String severity;
            if (absZ >= ROBUST_Z_WARNING) severity = "warning";
            else if (absZ >= ROBUST_Z_INFO) severity = "info";
            else continue;

            if (wnnMode && "info".equals(severity)) {
                warnings.add("INFO_ANOMALY_HINT:" + metric);
                continue;
            }

            ObjectNode anomaly = anomalies.addObject();
            anomaly.put("metric", metric);
            anomaly.put("current", round3(cur));
            anomaly.put("historical_median", round3(median));
            anomaly.put("mad", round3(mad));
            anomaly.put("robust_z", round2(robustZ));
            anomaly.put("severity", severity);
            anomaly.put("n", n);
        }
    }

    // ---- 工具 ----

    private String safePath() {
        try {
            return store.getSummaryDir().toString();
        } catch (Throwable t) {
            return "analysis-store/report-summaries";
        }
    }

    private ObjectNode policyNode() {
        ObjectNode policy = mapper.createObjectNode();
        policy.put("strict", false);
        policy.put("min_full_anomaly_n", MIN_FULL_ANOMALY_N);
        policy.put("min_median_only_n", MIN_MEDIAN_ONLY_N);
        policy.put("robust_z_warning_threshold", ROBUST_Z_WARNING);
        policy.put("robust_z_info_threshold", ROBUST_Z_INFO);
        ObjectNode window = policy.putObject("similar_config_window");
        window.put("arrival_rate_pct", SIMILAR_RATE_PCT);
        window.put("duration_pct", SIMILAR_DURATION_PCT);
        window.put("pack_probability_abs", SIMILAR_PACK_PROB_ABS);
        // RFC-004 新增 policy 段
        ObjectNode relaxed = policy.putObject("relaxed_window");
        relaxed.put("arrival_rate_pct", RELAXED_RATE_PCT);
        relaxed.put("duration_pct", RELAXED_DURATION_PCT);
        relaxed.put("pack_probability_abs", RELAXED_PACK_PROB_ABS);
        relaxed.put("window_count_delta", RELAXED_WINDOW_DELTA);
        relaxed.put("takeaway_window_count_delta", RELAXED_TAKEAWAY_WINDOW_DELTA);
        relaxed.put("total_seats_pct", RELAXED_TOTAL_SEATS_PCT);
        ObjectNode wnn = policy.putObject("weighted_nn");
        wnn.put("top_k", WNN_TOP_K);
        wnn.put("distance_max", WNN_DISTANCE_MAX);
        wnn.put("ess_min", WNN_ESS_MIN);
        return policy;
    }

    private ObjectNode emptyBaselineNode(String strategy, String confidence, int matched) {
        ObjectNode b = mapper.createObjectNode();
        b.put("strategy", strategy);
        b.put("confidence", confidence);
        b.put("matched_reports", matched);
        b.put("effective_sample_size", round2((double) matched));
        b.putNull("distance");
        b.putNull("weights");
        b.putNull("global_reference");
        b.putArray("limitations");
        return b;
    }

    private void addLimitation(ObjectNode baseline, String code) {
        JsonNode lim = baseline.path("limitations");
        ArrayNode arr = (lim instanceof ArrayNode) ? (ArrayNode) lim : baseline.putArray("limitations");
        for (JsonNode existing : arr) {
            if (code.equals(existing.asText())) return;
        }
        arr.add(code);
    }

    private void addCheck(ArrayNode checks, String code, String severity, String message, JsonNode ctx) {
        ObjectNode c = checks.addObject();
        c.put("code", code);
        c.put("severity", severity);
        c.put("message", message);
        if (ctx != null && !ctx.isMissingNode() && !ctx.isNull()) c.set("context", ctx);
    }

    private ObjectNode contextWith(String k1, Object v1, String k2, Object v2) {
        ObjectNode ctx = mapper.createObjectNode();
        putAny(ctx, k1, v1);
        putAny(ctx, k2, v2);
        return ctx;
    }

    private void putAny(ObjectNode o, String k, Object v) {
        if (v == null) o.putNull(k);
        else if (v instanceof Integer) o.put(k, (Integer) v);
        else if (v instanceof Long) o.put(k, (Long) v);
        else if (v instanceof Double) o.put(k, (Double) v);
        else o.put(k, v.toString());
    }

    private boolean canComputeSimilar(JsonNode current) {
        JsonNode cfg = current.path("config");
        return cfg.path("window_count").isNumber()
                && cfg.path("total_seats").isNumber()
                && cfg.path("takeaway_window_count").isNumber()
                && cfg.path("weather_type").isTextual()
                && cfg.path("arrival_rate").isNumber()
                && cfg.path("duration").isNumber()
                && cfg.path("pack_probability").isNumber();
    }

    private boolean similarConfig(JsonNode current, JsonNode candidate) {
        JsonNode a = current.path("config");
        JsonNode b = candidate.path("config");
        if (!b.path("window_count").isNumber()
                || !b.path("total_seats").isNumber()
                || !b.path("takeaway_window_count").isNumber()
                || !b.path("weather_type").isTextual()
                || !b.path("arrival_rate").isNumber()
                || !b.path("duration").isNumber()
                || !b.path("pack_probability").isNumber()) {
            return false;
        }
        if (a.get("window_count").asInt() != b.get("window_count").asInt()) return false;
        if (a.get("total_seats").asInt() != b.get("total_seats").asInt()) return false;
        if (a.get("takeaway_window_count").asInt() != b.get("takeaway_window_count").asInt()) return false;
        if (!a.get("weather_type").asText().equals(b.get("weather_type").asText())) return false;
        if (!withinPct(a.get("arrival_rate").asDouble(), b.get("arrival_rate").asDouble(), SIMILAR_RATE_PCT)) return false;
        if (!withinPct(a.get("duration").asDouble(), b.get("duration").asDouble(), SIMILAR_DURATION_PCT)) return false;
        double dPack = Math.abs(a.get("pack_probability").asDouble() - b.get("pack_probability").asDouble());
        return dPack <= SIMILAR_PACK_PROB_ABS;
    }

    /** RFC-004 §4.4 relaxed similar config:整数字段允许 ±1 / total_seats ±20%,浮点 ±25%/±0.15。 */
    private boolean relaxedSimilarConfig(JsonNode current, JsonNode candidate) {
        JsonNode a = current.path("config");
        JsonNode b = candidate.path("config");
        if (!b.path("window_count").isNumber()
                || !b.path("total_seats").isNumber()
                || !b.path("takeaway_window_count").isNumber()
                || !b.path("weather_type").isTextual()
                || !b.path("arrival_rate").isNumber()
                || !b.path("duration").isNumber()
                || !b.path("pack_probability").isNumber()) {
            return false;
        }
        if (Math.abs(a.get("window_count").asInt() - b.get("window_count").asInt())
                > RELAXED_WINDOW_DELTA) return false;
        if (Math.abs(a.get("takeaway_window_count").asInt() - b.get("takeaway_window_count").asInt())
                > RELAXED_TAKEAWAY_WINDOW_DELTA) return false;
        if (!withinPct(a.get("total_seats").asDouble(), b.get("total_seats").asDouble(),
                RELAXED_TOTAL_SEATS_PCT)) return false;
        if (!withinPct(a.get("arrival_rate").asDouble(), b.get("arrival_rate").asDouble(),
                RELAXED_RATE_PCT)) return false;
        if (!withinPct(a.get("duration").asDouble(), b.get("duration").asDouble(),
                RELAXED_DURATION_PCT)) return false;
        double dPack = Math.abs(a.get("pack_probability").asDouble() - b.get("pack_probability").asDouble());
        if (dPack > RELAXED_PACK_PROB_ABS) return false;
        // weather_type 不强制等;不等时由调用方记录 RELAXED_WEATHER warning
        return true;
    }

    /** RFC-004 §5 weighted nearest neighbors。 */
    private WnnResult computeWnn(JsonNode current, List<JsonNode> pool) {
        if (pool.isEmpty()) return null;
        JsonNode aCfg = current.path("config");
        // 7 字段 hardcoded 权重(RFC-004 §5.1)
        double wRate = 2.0, wDur = 1.5, wSeats = 1.0, wWin = 1.0,
                wTw = 0.5, wPack = 1.0, wWeather = 1.0;
        double aRate = aCfg.path("arrival_rate").asDouble();
        double aDur = aCfg.path("duration").asDouble();
        double aSeats = aCfg.path("total_seats").asDouble();
        int aWin = aCfg.path("window_count").asInt();
        int aTw = aCfg.path("takeaway_window_count").asInt();
        double aPack = aCfg.path("pack_probability").asDouble();
        String aWeather = aCfg.path("weather_type").asText("");

        List<double[]> candidatesWithDistance = new ArrayList<>();
        int totalEvaluated = 0;
        int rejectedByDistance = 0;
        for (JsonNode c : pool) {
            JsonNode cCfg = c.path("config");
            if (!cCfg.path("window_count").isNumber()
                    || !cCfg.path("total_seats").isNumber()
                    || !cCfg.path("takeaway_window_count").isNumber()
                    || !cCfg.path("weather_type").isTextual()
                    || !cCfg.path("arrival_rate").isNumber()
                    || !cCfg.path("duration").isNumber()
                    || !cCfg.path("pack_probability").isNumber()) continue;
            totalEvaluated++;
            double cRate = cCfg.get("arrival_rate").asDouble();
            double cDur = cCfg.get("duration").asDouble();
            double cSeats = cCfg.get("total_seats").asDouble();
            int cWin = cCfg.get("window_count").asInt();
            int cTw = cCfg.get("takeaway_window_count").asInt();
            double cPack = cCfg.get("pack_probability").asDouble();
            String cWeather = cCfg.get("weather_type").asText("");

            double[] terms = new double[7];
            terms[0] = wRate * sq(normRel(cRate, aRate));
            terms[1] = wDur * sq(normRel(cDur, aDur));
            terms[2] = wSeats * sq(normRel(cSeats, aSeats));
            terms[3] = wWin * sq((cWin - aWin) / 5.0);
            terms[4] = wTw * sq((cTw - aTw) / 3.0);
            terms[5] = wPack * sq(cPack - aPack);
            terms[6] = wWeather * sq(aWeather.equals(cWeather) ? 0.0 : 0.5);
            double sum = 0.0;
            for (double t : terms) sum += t;
            double distance = Math.sqrt(sum);
            if (Double.isNaN(distance) || Double.isInfinite(distance)) continue;
            if (distance > WNN_DISTANCE_MAX) {
                rejectedByDistance++;
                continue;
            }
            candidatesWithDistance.add(new double[]{ distance, indexOfNode(pool, c) });
        }
        candidatesWithDistance.sort((x, y) -> Double.compare(x[0], y[0]));

        WnnResult result = new WnnResult();
        result.totalEvaluated = totalEvaluated;
        result.allRejectedByDistance = (totalEvaluated > 0 && rejectedByDistance == totalEvaluated);

        int take = Math.min(WNN_TOP_K, candidatesWithDistance.size());
        if (take == 0) return result;
        double sumW = 0.0, sumW2 = 0.0;
        double minD = Double.POSITIVE_INFINITY, maxD = Double.NEGATIVE_INFINITY;
        double[] dArr = new double[take];
        double[] wArr = new double[take];
        for (int i = 0; i < take; i++) {
            double[] entry = candidatesWithDistance.get(i);
            double d = entry[0];
            int poolIdx = (int) entry[1];
            JsonNode neighbor = pool.get(poolIdx);
            double w = 1.0 / (1.0 + d);
            result.neighbors.add(neighbor);
            sumW += w;
            sumW2 += w * w;
            dArr[i] = d;
            wArr[i] = w;
            if (d < minD) minD = d;
            if (d > maxD) maxD = d;
        }
        double medianD = median(dArr);
        result.distanceMin = minD;
        result.distanceMedian = medianD;
        result.distanceMax = maxD;
        result.ess = (sumW2 == 0.0) ? 0.0 : (sumW * sumW) / sumW2;
        result.wRate = wRate; result.wDur = wDur; result.wSeats = wSeats;
        result.wWin = wWin; result.wTw = wTw; result.wPack = wPack; result.wWeather = wWeather;
        return result;
    }

    private static double sq(double v) { return v * v; }

    private static double normRel(double cur, double base) {
        double denom = Math.max(Math.abs(base), 1.0);
        return (cur - base) / denom;
    }

    private static int indexOfNode(List<JsonNode> pool, JsonNode target) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i) == target) return i;
        }
        return -1;
    }

    private void writeWnnFields(ObjectNode baseline, WnnResult wnn) {
        baseline.put("effective_sample_size", round2(wnn.ess));
        ObjectNode dist = baseline.putObject("distance");
        dist.put("min", round3(wnn.distanceMin));
        dist.put("median", round3(wnn.distanceMedian));
        dist.put("max", round3(wnn.distanceMax));
        dist.put("threshold", WNN_DISTANCE_MAX);
        ObjectNode weights = baseline.putObject("weights");
        weights.put("arrival_rate", wnn.wRate);
        weights.put("duration", wnn.wDur);
        weights.put("total_seats", wnn.wSeats);
        weights.put("window_count", wnn.wWin);
        weights.put("takeaway_window_count", wnn.wTw);
        weights.put("pack_probability", wnn.wPack);
        weights.put("weather_type", wnn.wWeather);
    }

    private void writeGlobalReference(ObjectNode baseline, List<JsonNode> pool) {
        ObjectNode global = baseline.putObject("global_reference");
        ObjectNode metricsNode = global.putObject("metrics");
        for (String metric : METRICS) {
            double[] xs = collectMetric(pool, metric);
            if (xs.length < MIN_MEDIAN_ONLY_N) continue;
            ObjectNode m = metricsNode.putObject(metric);
            m.put("median", round3(median(xs)));
            m.put("n", xs.length);
        }
    }

    private static final class WnnResult {
        final List<JsonNode> neighbors = new ArrayList<>();
        double ess = 0.0;
        double distanceMin, distanceMedian, distanceMax;
        double wRate, wDur, wSeats, wWin, wTw, wPack, wWeather;
        int totalEvaluated;
        boolean allRejectedByDistance;
    }

    private boolean withinPct(double base, double other, double pct) {
        if (base == 0.0) return other == 0.0;
        return Math.abs(other - base) / Math.abs(base) <= pct;
    }

    private double[] collectMetric(List<JsonNode> matched, String metric) {
        double[] tmp = new double[matched.size()];
        int n = 0;
        for (JsonNode m : matched) {
            JsonNode v = m.path("metrics").path(metric);
            if (!v.isNumber()) continue;
            double d = v.asDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) continue;
            tmp[n++] = d;
        }
        return Arrays.copyOf(tmp, n);
    }

    private static double median(double[] xs) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n == 0) return 0.0;
        if ((n & 1) == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static double medianAbsoluteDeviation(double[] xs, double median) {
        double[] devs = new double[xs.length];
        for (int i = 0; i < xs.length; i++) devs[i] = Math.abs(xs[i] - median);
        return median(devs);
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }
}
