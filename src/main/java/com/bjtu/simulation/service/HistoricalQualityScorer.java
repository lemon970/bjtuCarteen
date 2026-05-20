package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * 历史质量评分服务(阶段 3,RFC-003)。
 *
 * <p>语义:`quality_score` 是当前报告的"数据质量与历史可比性评分",衡量
 * 当前报告用于历史对比和后续分析的可用性。**不是**业务表现评分,
 * **不是**食堂运行好坏评分。anomaly 偏离历史只反映该指标与历史不一致,
 * 不一定意味着诊断本身不可信。
 *
 * <p>输入:phase 2 输出的 historical_diagnostics ObjectNode。
 * 输出:historical_quality ObjectNode。
 *
 * <p>4 维度独立打分(每维从 1.0 起扣分,夹紧到 [0,1]):
 * <ul>
 *   <li>availability —— 当前 summary 自身可用性
 *   <li>comparability —— 历史可比性(matching strategy + matched count)
 *   <li>historical_conformity —— 当前指标相对历史基线的稳定性。
 *       注意:此名表示"相对历史基线的指标稳定性",不是系统稳定性,也不是业务稳定性。
 *   <li>reliability —— 语料整体健康度(corpus_size / usable_summaries / source_status)
 * </ul>
 *
 * <p>综合分 = min(4 维度分),保守取最小,避免"加权后掩盖单一维度问题"。
 *
 * <p>{@link #score(ObjectNode, String)} 永不向上抛异常;内部任何异常以
 * QUALITY_SCORER_INTERNAL_ERROR / score_available=false 表达。
 */
@Service
public class HistoricalQualityScorer {

    private static final Logger log = LoggerFactory.getLogger(HistoricalQualityScorer.class);

    static final String SCHEMA_VERSION = "1.1";
    static final String COMPUTED_BY = "java-quality-scorer";

    static final String DISCLAIMER_DIAGNOSTIC_ONLY = "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY";
    static final String DISCLAIMER_NOT_BUSINESS = "NOT_A_BUSINESS_PERFORMANCE_SCORE";

    private final ObjectMapper mapper;

    @Autowired
    public HistoricalQualityScorer() {
        this(AppBeansConfig.createReportObjectMapper());
    }

    public HistoricalQualityScorer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode score(ObjectNode diagnostics, String reportId) {
        ObjectNode result = newEnvelope();
        ArrayNode warnings = result.putArray("warnings");
        warnings.add(DISCLAIMER_DIAGNOSTIC_ONLY);
        warnings.add(DISCLAIMER_NOT_BUSINESS);

        try {
            return doScore(diagnostics, reportId, result);
        } catch (Throwable t) {
            log.warn("quality scorer failed for {}: {}", reportId, t.toString());
            return unavailable(result, reportId, "QUALITY_SCORER_INTERNAL_ERROR", null);
        }
    }

    private ObjectNode newEnvelope() {
        ObjectNode env = mapper.createObjectNode();
        env.put("enabled", true);
        env.put("schema_version", SCHEMA_VERSION);
        env.put("computed_by", COMPUTED_BY);
        env.put("computed_at_epoch_millis", System.currentTimeMillis());
        return env;
    }

    private ObjectNode doScore(ObjectNode diagnostics, String reportId, ObjectNode result) {
        // ---- 闸门 1:diagnostics 不存在或不是 ObjectNode ----
        if (diagnostics == null) {
            return unavailable(result, reportId, "DIAGNOSTICS_NOT_PROVIDED", null);
        }
        // 注意:HistoricalQualityScorer.score 的签名已收紧到 ObjectNode,
        // 所以 NOT_OBJECT 走 controller 层的类型检查;此处仍保留分支以容错调用方传 null
        // 之外的异常 ObjectNode 子类(测试用)。

        JsonNode basis = diagnostics.path("basis");
        // ---- 闸门 2:核心 check 触发 unavailable ----
        String fatalReason = scanFatalCheck(diagnostics);
        if (fatalReason != null) {
            return unavailable(result, reportId, fatalReason, diagnostics);
        }

        boolean currentPresent = basis.path("current_summary_present").asBoolean(false);
        int corpusSize = basis.path("corpus_size").asInt(0);
        int matchedReports = basis.path("matched_reports").asInt(0);

        if (corpusSize <= 0 && !currentPresent) {
            return unavailable(result, reportId, "EMPTY_CORPUS_AND_NO_CURRENT", diagnostics);
        }
        if (matchedReports <= 0 && !currentPresent) {
            return unavailable(result, reportId, "INSUFFICIENT_LOCAL_AND_GLOBAL", diagnostics);
        }
        if (!currentPresent) {
            // 兜底:phase 2 通常会先发 MISSING_SUMMARY 而被 scanFatalCheck 命中,
            // 此处兜住"current 缺失但语料非空"的边缘场景。
            return unavailable(result, reportId, "MISSING_SUMMARY", diagnostics);
        }

        // RFC-004:存在 basis.baseline.confidence 时启用新公式;否则回退 phase 3 旧逻辑(向后兼容)。
        JsonNode baselineNode = basis.path("baseline");
        boolean adaptive = baselineNode.isObject() && baselineNode.has("confidence");
        String confidence = adaptive ? baselineNode.path("confidence").asText("unknown") : "unknown";

        // ---- 4 维扣分 ----
        ArrayNode penalties = result.putArray("penalties");
        DimensionResult availability = computeAvailability(diagnostics, penalties);
        DimensionResult comparability = adaptive
                ? computeComparabilityAdaptive(diagnostics, penalties, confidence)
                : computeComparability(diagnostics, penalties);
        DimensionResult conformity = adaptive
                ? computeHistoricalConformityAdaptive(diagnostics, penalties, confidence)
                : computeHistoricalConformity(diagnostics, penalties);
        DimensionResult reliability = computeReliability(diagnostics, penalties);

        // ---- 综合分 = min(有效 4 维),not_applicable 维度排除 ----
        List<Double> activeScores = new ArrayList<>();
        activeScores.add(clamp01(availability.score));
        if (!comparability.notApplicable) activeScores.add(clamp01(comparability.score));
        if (!conformity.notApplicable) activeScores.add(clamp01(conformity.score));
        activeScores.add(clamp01(reliability.score));
        double quality = activeScores.stream().min(Double::compareTo).orElse(0.0);
        if (Double.isNaN(quality) || Double.isInfinite(quality)) quality = 0.0;
        double rounded = round2(quality);

        result.put("score_available", true);
        result.put("quality_score", rounded);
        result.put("quality_score_percent", (int) Math.round(rounded * 100.0));
        String level = deriveLevel(rounded, diagnostics);
        if (adaptive) {
            String capped = capByConfidence(level, confidence);
            if (!capped.equals(level)) {
                ArrayNode warningsArr = (ArrayNode) result.path("warnings");
                addWarningOnce(warningsArr, "LEVEL_CAPPED_BY_CONFIDENCE");
                level = capped;
            }
            ArrayNode warningsArr = (ArrayNode) result.path("warnings");
            if ("low".equals(confidence)) addWarningOnce(warningsArr, "BASELINE_CONFIDENCE_LOW");
            else if ("very_low".equals(confidence)) addWarningOnce(warningsArr, "BASELINE_CONFIDENCE_VERY_LOW");
            else if ("none".equals(confidence)) addWarningOnce(warningsArr, "NO_COMPARABLE_HISTORY");
        }
        result.put("level", level);

        ObjectNode dimensions = result.putObject("dimensions");
        writeDimension(dimensions, "availability", availability);
        writeDimension(dimensions, "comparability", comparability);
        writeDimension(dimensions, "historical_conformity", conformity);
        writeDimension(dimensions, "reliability", reliability);

        result.set("basis", buildBasis(diagnostics, reportId));
        // 让 score_available + level 字段排在 basis 前更易读 —— Jackson ObjectNode 保留插入顺序。
        // 但因前文已 putArray("warnings") 在 result 顶部,Jackson 会按插入顺序输出。
        return result;
    }

    private ObjectNode unavailable(ObjectNode result, String reportId, String reason, JsonNode diagnostics) {
        result.put("score_available", false);
        result.put("level", "unavailable");
        result.put("unavailable_reason", reason);
        result.set("basis", buildBasis(diagnostics, reportId));
        return result;
    }

    /** 扫描 diagnostics.checks 中的致命 code,优先级:MISSING_SUMMARY > CURRENT_PARSE_FAILED > DIAGNOSTICS_INTERNAL_ERROR。 */
    private String scanFatalCheck(JsonNode diagnostics) {
        JsonNode checks = diagnostics.path("checks");
        if (!checks.isArray()) return null;
        boolean missingSummary = false;
        boolean parseFailed = false;
        boolean internalError = false;
        for (JsonNode c : checks) {
            String code = c.path("code").asText("");
            switch (code) {
                case "MISSING_SUMMARY": missingSummary = true; break;
                case "CURRENT_PARSE_FAILED": parseFailed = true; break;
                case "DIAGNOSTICS_INTERNAL_ERROR": internalError = true; break;
                default: break;
            }
        }
        if (missingSummary) return "MISSING_SUMMARY";
        if (parseFailed) return "CURRENT_PARSE_FAILED";
        if (internalError) return "DIAGNOSTICS_INTERNAL_ERROR";
        return null;
    }

    private DimensionResult computeAvailability(ObjectNode diagnostics, ArrayNode penalties) {
        DimensionResult dim = new DimensionResult();
        // INVARIANT_FAILURE 重扣
        int invariantFailures = countCheckCode(diagnostics, "INVARIANT_FAILURE");
        if (invariantFailures > 0) {
            applyPenalty(dim, penalties, "availability", "CURRENT_INVARIANT_FAILURE", 0.50,
                    "current_invariant_failure:" + invariantFailures,
                    "historical_diagnostics.checks.INVARIANT_FAILURE");
        }
        // CURRENT_SOURCE_MISSING warning
        if (hasWarning(diagnostics, "CURRENT_SOURCE_MISSING")) {
            applyPenalty(dim, penalties, "availability", "CURRENT_SOURCE_MISSING", 0.05,
                    "current_source_missing",
                    "historical_diagnostics.warnings.CURRENT_SOURCE_MISSING");
        }
        // METRIC_MISSING:* 累计扣分,封顶 0.15
        int metricMissingCount = countWarningPrefix(diagnostics, "METRIC_MISSING:");
        if (metricMissingCount > 0) {
            double amount = Math.min(0.03 * metricMissingCount, 0.15);
            applyPenalty(dim, penalties, "availability", "METRIC_MISSING", amount,
                    "metric_missing:" + metricMissingCount,
                    "historical_diagnostics.warnings.METRIC_MISSING");
        }
        return dim;
    }

    private DimensionResult computeComparability(ObjectNode diagnostics, ArrayNode penalties) {
        DimensionResult dim = new DimensionResult();
        JsonNode basis = diagnostics.path("basis");
        String strategy = basis.path("matching_strategy").asText("none");
        int matched = basis.path("matched_reports").asInt(0);
        if (matched < 0) matched = 0;

        switch (strategy) {
            case "scenario_id_exact":
                // 无扣分
                break;
            case "config_fingerprint":
                applyPenalty(dim, penalties, "comparability", "MATCHING_STRATEGY_FINGERPRINT", 0.05,
                        "strategy_fingerprint",
                        "historical_diagnostics.basis.matching_strategy=config_fingerprint");
                break;
            case "similar_config":
                applyPenalty(dim, penalties, "comparability", "MATCHING_STRATEGY_SIMILAR", 0.20,
                        "strategy_similar_only",
                        "historical_diagnostics.basis.matching_strategy=similar_config");
                break;
            case "none":
            default:
                applyPenalty(dim, penalties, "comparability", "MATCHING_STRATEGY_NONE", 0.60,
                        "strategy_none",
                        "historical_diagnostics.basis.matching_strategy=none");
                break;
        }

        if (matched < 3) {
            applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_VERY_LOW", 0.45,
                    "matched_reports=" + matched,
                    "historical_diagnostics.basis.matched_reports");
        } else if (matched < 5) {
            applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_LOW", 0.20,
                    "matched_reports=" + matched,
                    "historical_diagnostics.basis.matched_reports");
        } else if (matched < 10) {
            applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_RANGE", 0.05,
                    "matched_reports=" + matched,
                    "historical_diagnostics.basis.matched_reports");
        }

        if (hasWarning(diagnostics, "SIMILAR_CONFIG_UNAVAILABLE")) {
            applyPenalty(dim, penalties, "comparability", "SIMILAR_CONFIG_UNAVAILABLE", 0.05,
                    "similar_config_unavailable",
                    "historical_diagnostics.warnings.SIMILAR_CONFIG_UNAVAILABLE");
        }
        return dim;
    }

    /**
     * RFC-004:基于 baseline.confidence 的 comparability 计算。
     * 与旧公式不同点:不再因 strategy=none 一次扣 0.60;按 confidence 分档起扣,确保
     * 即使 confidence=none 也保留 ~0.50 的下限,杜绝"语料缺陷传染到单条报告质量"。
     */
    private DimensionResult computeComparabilityAdaptive(ObjectNode diagnostics, ArrayNode penalties,
                                                         String confidence) {
        DimensionResult dim = new DimensionResult();
        JsonNode basis = diagnostics.path("basis");
        String strategy = basis.path("matching_strategy").asText("none");
        int matched = basis.path("matched_reports").asInt(0);
        if (matched < 0) matched = 0;

        double startDeduct;
        switch (confidence) {
            case "high": startDeduct = 0.0; break;
            case "medium": startDeduct = 0.05; break;
            case "low": startDeduct = 0.15; break;
            case "very_low": startDeduct = 0.30; break;
            case "none": startDeduct = 0.50; break;
            default: startDeduct = 0.0; break;
        }
        if (startDeduct > 0) {
            applyPenalty(dim, penalties, "comparability",
                    "BASELINE_CONFIDENCE_" + confidence.toUpperCase(Locale.ROOT), startDeduct,
                    "baseline_confidence:" + confidence,
                    "historical_diagnostics.basis.baseline.confidence=" + confidence);
        }

        if ("relaxed_similar_config".equals(strategy)
                || "weighted_nearest_neighbors".equals(strategy)) {
            applyPenalty(dim, penalties, "comparability", "RELAXED_MATCH_USED", 0.05,
                    "relaxed_match_used:" + strategy,
                    "historical_diagnostics.basis.matching_strategy=" + strategy);
        }

        // matched_reports 二次扣分:medium / low 才适用;high 已蕴含 N>=5,very_low/none 时 matched=0 无意义
        if ("medium".equals(confidence) || "low".equals(confidence)) {
            if (matched < 3) {
                applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_VERY_LOW", 0.45,
                        "matched_reports=" + matched,
                        "historical_diagnostics.basis.matched_reports");
            } else if (matched < 5) {
                applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_LOW", 0.20,
                        "matched_reports=" + matched,
                        "historical_diagnostics.basis.matched_reports");
            } else if (matched < 10) {
                applyPenalty(dim, penalties, "comparability", "MATCHED_REPORTS_RANGE", 0.05,
                        "matched_reports=" + matched,
                        "historical_diagnostics.basis.matched_reports");
            }
        }

        if (hasWarning(diagnostics, "SIMILAR_CONFIG_UNAVAILABLE")) {
            applyPenalty(dim, penalties, "comparability", "SIMILAR_CONFIG_UNAVAILABLE", 0.05,
                    "similar_config_unavailable",
                    "historical_diagnostics.warnings.SIMILAR_CONFIG_UNAVAILABLE");
        }
        return dim;
    }

    /**
     * historical_conformity:当前指标相对历史基线的稳定性。
     * 反映"和邻居比一不一致",不反映系统稳定性,也不反映业务稳定性。
     */
    private DimensionResult computeHistoricalConformity(ObjectNode diagnostics, ArrayNode penalties) {
        DimensionResult dim = new DimensionResult();
        int matched = diagnostics.path("basis").path("matched_reports").asInt(0);

        // matched < 3 时 phase 2 不做偏离 → 我们也不能据此判断稳定性
        if (matched < 3) {
            applyPenalty(dim, penalties, "historical_conformity", "CONFORMITY_SKIPPED_INSUFFICIENT", 0.30,
                    "stability_skipped",
                    "historical_diagnostics.basis.matched_reports<3");
            return dim;
        }

        JsonNode anomalies = diagnostics.path("anomalies");
        if (anomalies.isArray()) {
            int wCount = 0;
            int iCount = 0;
            for (JsonNode a : anomalies) {
                String severity = a.path("severity").asText("");
                String metric = a.path("metric").asText("?");
                if ("warning".equals(severity)) {
                    wCount++;
                    applyPenalty(dim, penalties, "historical_conformity", "WARNING_ANOMALY", 0.15,
                            "warning_anomaly:" + metric,
                            "historical_diagnostics.anomalies:" + metric);
                } else if ("info".equals(severity)) {
                    iCount++;
                    applyPenalty(dim, penalties, "historical_conformity", "INFO_ANOMALY", 0.05,
                            "info_anomaly:" + metric,
                            "historical_diagnostics.anomalies:" + metric);
                }
            }
            // 不在 reasons 里冗余总数,wCount/iCount 仅用于潜在调试
            if (wCount + iCount == 0) {
                // 无 anomaly,不动
            }
        }

        int madZero = countWarningPrefix(diagnostics, "MAD_ZERO:");
        if (madZero > 0) {
            double amount = Math.min(0.02 * madZero, 0.10);
            applyPenalty(dim, penalties, "historical_conformity", "MAD_ZERO", amount,
                    "mad_zero:" + madZero,
                    "historical_diagnostics.warnings.MAD_ZERO");
        }
        return dim;
    }

    /**
     * RFC-004 §8.2.2:基于 baseline.confidence 的 conformity 计算。
     * very_low / none → not_applicable,从 min 中剔除;不再用 0.30 扣分压低维度。
     * low → INFO 级 anomaly 不扣分(已转 INFO_ANOMALY_HINT 入 warnings);WARNING 仍扣 0.15。
     */
    private DimensionResult computeHistoricalConformityAdaptive(ObjectNode diagnostics, ArrayNode penalties,
                                                                 String confidence) {
        DimensionResult dim = new DimensionResult();
        if ("very_low".equals(confidence) || "none".equals(confidence)) {
            dim.notApplicable = true;
            dim.reasons.add("baseline_confidence=" + confidence);
            return dim;
        }
        int matched = diagnostics.path("basis").path("matched_reports").asInt(0);
        if (matched < 3) {
            applyPenalty(dim, penalties, "historical_conformity", "CONFORMITY_SKIPPED_INSUFFICIENT", 0.30,
                    "stability_skipped",
                    "historical_diagnostics.basis.matched_reports<3");
            return dim;
        }
        boolean lowConf = "low".equals(confidence);
        JsonNode anomalies = diagnostics.path("anomalies");
        if (anomalies.isArray()) {
            for (JsonNode a : anomalies) {
                String severity = a.path("severity").asText("");
                String metric = a.path("metric").asText("?");
                if ("warning".equals(severity)) {
                    applyPenalty(dim, penalties, "historical_conformity", "WARNING_ANOMALY", 0.15,
                            "warning_anomaly:" + metric,
                            "historical_diagnostics.anomalies:" + metric);
                } else if ("info".equals(severity) && !lowConf) {
                    applyPenalty(dim, penalties, "historical_conformity", "INFO_ANOMALY", 0.05,
                            "info_anomaly:" + metric,
                            "historical_diagnostics.anomalies:" + metric);
                }
            }
        }
        int madZero = countWarningPrefix(diagnostics, "MAD_ZERO:");
        if (madZero > 0) {
            double amount = Math.min(0.02 * madZero, 0.10);
            applyPenalty(dim, penalties, "historical_conformity", "MAD_ZERO", amount,
                    "mad_zero:" + madZero,
                    "historical_diagnostics.warnings.MAD_ZERO");
        }
        return dim;
    }

    private String capByConfidence(String level, String confidence) {
        // unreliable 是强降级状态(由 INVARIANT_FAILURE 触发),不被 confidence cap 提升
        if ("unreliable".equals(level)) return level;
        int rank = levelRank(level);
        int cap;
        switch (confidence) {
            case "high": cap = levelRank("reliable"); break;
            case "medium": cap = levelRank("usable"); break;
            case "low":
            case "very_low":
            case "none": cap = levelRank("caution"); break;
            default: cap = levelRank("reliable"); break;
        }
        return rank <= cap ? level : levelOfRank(cap);
    }

    private static int levelRank(String level) {
        switch (level) {
            case "unreliable": return 0;
            case "caution": return 1;
            case "usable": return 2;
            case "reliable": return 3;
            default: return 3;
        }
    }

    private static String levelOfRank(int rank) {
        switch (rank) {
            case 0: return "unreliable";
            case 1: return "caution";
            case 2: return "usable";
            case 3: return "reliable";
            default: return "caution";
        }
    }

    private static void addWarningOnce(ArrayNode warnings, String code) {
        for (JsonNode w : warnings) {
            if (code.equals(w.asText())) return;
        }
        warnings.add(code);
    }

    private DimensionResult computeReliability(ObjectNode diagnostics, ArrayNode penalties) {
        DimensionResult dim = new DimensionResult();
        JsonNode basis = diagnostics.path("basis");
        int corpusSize = basis.path("corpus_size").asInt(0);
        int usableSummaries = basis.path("usable_summaries").asInt(0);

        if (corpusSize <= 0) {
            applyPenalty(dim, penalties, "reliability", "CORPUS_EMPTY", 0.50,
                    "corpus_empty",
                    "historical_diagnostics.basis.corpus_size=0");
        } else if (corpusSize < 5) {
            applyPenalty(dim, penalties, "reliability", "CORPUS_SMALL", 0.20,
                    "corpus_small:" + corpusSize,
                    "historical_diagnostics.basis.corpus_size");
        }

        if (corpusSize > 0) {
            double usableRatio = (double) usableSummaries / corpusSize;
            if (usableRatio < 0.7) {
                applyPenalty(dim, penalties, "reliability", "LOW_USABLE_RATIO", 0.20,
                        "low_usable_ratio:" + round3(usableRatio),
                        "historical_diagnostics.basis.usable_summaries");
            }
        }

        // 邻居池整体 source_status 占比 —— 用 source_status_counts 推算
        JsonNode counts = basis.path("source_status_counts");
        if (counts.isObject()) {
            int present = counts.path("present").asInt(0);
            int stale = counts.path("stale").asInt(0);
            int missing = counts.path("missing").asInt(0);
            int deleted = counts.path("deleted").asInt(0);
            int unverified = counts.path("unverified").asInt(0);
            int total = present + stale + missing + deleted + unverified;
            if (total > 0) {
                double weakRatio = (double) (missing + deleted + unverified) / total;
                if (weakRatio > 0.5) {
                    applyPenalty(dim, penalties, "reliability", "WEAK_SOURCE_STATUS_NEIGHBORS", 0.15,
                            "weak_source_status_neighbors:" + round3(weakRatio),
                            "historical_diagnostics.basis.source_status_counts");
                }
            }
        }

        if (hasWarning(diagnostics, "STALE_NEIGHBORS")) {
            applyPenalty(dim, penalties, "reliability", "STALE_NEIGHBORS", 0.10,
                    "stale_neighbors",
                    "historical_diagnostics.warnings.STALE_NEIGHBORS");
        }
        if (hasWarning(diagnostics, "MISSING_SOURCE_NEIGHBORS")) {
            applyPenalty(dim, penalties, "reliability", "MISSING_SOURCE_NEIGHBORS", 0.05,
                    "missing_source_neighbors",
                    "historical_diagnostics.warnings.MISSING_SOURCE_NEIGHBORS");
        }
        return dim;
    }

    private String deriveLevel(double quality, JsonNode diagnostics) {
        // INVARIANT_FAILURE error 强降级
        if (countCheckCode(diagnostics, "INVARIANT_FAILURE") > 0) return "unreliable";
        if (quality < 0.40) return "unreliable";
        boolean hasWarningAnomaly = false;
        JsonNode anomalies = diagnostics.path("anomalies");
        if (anomalies.isArray()) {
            for (JsonNode a : anomalies) {
                if ("warning".equals(a.path("severity").asText())) { hasWarningAnomaly = true; break; }
            }
        }
        if (quality < 0.70 || hasWarningAnomaly) return "caution";
        if (quality < 0.85) return "usable";
        return "reliable";
    }

    private ObjectNode buildBasis(JsonNode diagnostics, String reportId) {
        ObjectNode basis = mapper.createObjectNode();
        basis.put("diagnostics_used", true);
        if (diagnostics != null && diagnostics.isObject()) {
            basis.put("diagnostics_schema_version",
                    diagnostics.path("schema_version").asText("unknown"));
            JsonNode dBasis = diagnostics.path("basis");
            basis.put("current_report_id",
                    dBasis.path("current_report_id").asText(reportId == null ? "" : reportId));
            basis.put("current_summary_present", dBasis.path("current_summary_present").asBoolean(false));
            basis.put("matched_reports", dBasis.path("matched_reports").asInt(0));
            basis.put("matching_strategy", dBasis.path("matching_strategy").asText("none"));
            basis.put("corpus_size", dBasis.path("corpus_size").asInt(0));
            basis.put("usable_summaries", dBasis.path("usable_summaries").asInt(0));
            // RFC-004 PR-004A.1:把 diagnostics.basis.baseline 中的稳定契约字段
            // (confidence / strategy)透传到 historical_quality.basis,前端据此
            // 渲染 confidence chip / banner,无需依赖 warnings 内部实现细节。
            // 字段不存在时(legacy 1.0 输入或 diagnostics=null)回退为 "unknown"。
            JsonNode dBaseline = dBasis.path("baseline");
            basis.put("baseline_confidence",
                    dBaseline.path("confidence").asText("unknown"));
            basis.put("baseline_strategy",
                    dBaseline.path("strategy").asText("unknown"));

            ObjectNode anomalyCount = basis.putObject("anomaly_count");
            int w = 0, i = 0;
            JsonNode anomalies = diagnostics.path("anomalies");
            if (anomalies.isArray()) {
                for (JsonNode a : anomalies) {
                    String s = a.path("severity").asText("");
                    if ("warning".equals(s)) w++;
                    else if ("info".equals(s)) i++;
                }
            }
            anomalyCount.put("warning", w);
            anomalyCount.put("info", i);
        } else {
            basis.put("diagnostics_schema_version", "unknown");
            basis.put("current_report_id", reportId == null ? "" : reportId);
            basis.put("current_summary_present", false);
            basis.put("matched_reports", 0);
            basis.put("matching_strategy", "none");
            basis.put("corpus_size", 0);
            basis.put("usable_summaries", 0);
            basis.put("baseline_confidence", "unknown");
            basis.put("baseline_strategy", "unknown");
        }
        return basis;
    }

    private void writeDimension(ObjectNode dimensions, String name, DimensionResult dim) {
        ObjectNode node = dimensions.putObject(name);
        if (dim.notApplicable) {
            node.putNull("score");
            node.put("not_applicable", true);
            node.put("excluded_from_min", true);
        } else {
            node.put("score", round2(clamp01(dim.score)));
        }
        ArrayNode reasons = node.putArray("reasons");
        for (String r : dim.reasons) reasons.add(r);
    }

    private void applyPenalty(DimensionResult dim, ArrayNode penalties, String dimName,
                              String code, double amount, String reason, String source) {
        dim.score -= amount;
        dim.reasons.add(reason);
        ObjectNode p = penalties.addObject();
        p.put("code", code);
        p.put("dimension", dimName);
        p.put("amount", round3(amount));
        p.put("source", source);
    }

    private int countCheckCode(JsonNode diagnostics, String code) {
        int n = 0;
        JsonNode checks = diagnostics.path("checks");
        if (!checks.isArray()) return 0;
        for (JsonNode c : checks) {
            if (code.equals(c.path("code").asText())) n++;
        }
        return n;
    }

    private boolean hasWarning(JsonNode diagnostics, String warning) {
        JsonNode warnings = diagnostics.path("warnings");
        if (!warnings.isArray()) return false;
        for (JsonNode w : warnings) {
            if (warning.equals(w.asText())) return true;
        }
        return false;
    }

    private int countWarningPrefix(JsonNode diagnostics, String prefix) {
        int n = 0;
        JsonNode warnings = diagnostics.path("warnings");
        if (!warnings.isArray()) return 0;
        for (JsonNode w : warnings) {
            if (w.asText("").startsWith(prefix)) n++;
        }
        return n;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    @SuppressWarnings("unused")
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static final class DimensionResult {
        double score = 1.0;
        boolean notApplicable = false;
        final List<String> reasons = new ArrayList<>();
    }
}
