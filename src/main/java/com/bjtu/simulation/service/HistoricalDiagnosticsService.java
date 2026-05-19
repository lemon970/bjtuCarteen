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

    static final String SCHEMA_VERSION = "1.0";
    static final String COMPUTED_BY = "java-summary-store";

    private static final int MIN_FULL_ANOMALY_N = 5;
    private static final int MIN_MEDIAN_ONLY_N = 3;
    private static final double ROBUST_Z_WARNING = 3.0;
    private static final double ROBUST_Z_INFO = 2.0;
    private static final double SIMILAR_RATE_PCT = 0.10;
    private static final double SIMILAR_DURATION_PCT = 0.10;
    private static final double SIMILAR_PACK_PROB_ABS = 0.05;

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

        // ---- 三层匹配 ----
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

        String strategy;
        List<JsonNode> matched;
        if (tierA.size() >= MIN_MEDIAN_ONLY_N) { strategy = "scenario_id_exact"; matched = tierA; }
        else if (tierB.size() >= MIN_MEDIAN_ONLY_N) { strategy = "config_fingerprint"; matched = tierB; }
        else if (tierC.size() >= MIN_MEDIAN_ONLY_N) { strategy = "similar_config"; matched = tierC; }
        else if (!tierA.isEmpty()) { strategy = "scenario_id_exact"; matched = tierA; }
        else if (!tierB.isEmpty()) { strategy = "config_fingerprint"; matched = tierB; }
        else if (!tierC.isEmpty()) { strategy = "similar_config"; matched = tierC; }
        else { strategy = "none"; matched = Collections.emptyList(); }

        basis.put("matching_strategy", strategy);
        basis.put("matched_reports", matched.size());

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

        // ---- 指标偏离 ----
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
                // 3<=n<=4:仅记录 median,不判 outlier,不输出 anomaly 项
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
            else continue; // 不产生 anomaly 项

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
        return policy;
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
