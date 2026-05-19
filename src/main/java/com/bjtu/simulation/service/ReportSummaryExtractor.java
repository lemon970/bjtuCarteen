package com.bjtu.simulation.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.bjtu.simulation.config.AppBeansConfig;

/**
 * 从仿真报告 JSON 中抽取小摘要(≤ 5KB)。
 * 双入口:
 *   1) {@link #extractFromJsonNode} —— 已在内存中的 JsonNode(write hook 路径,零额外 IO)。
 *   2) {@link #extractFromFile}     —— 流式读 20MB+ 文件(rebuild/backfill 路径,堆有界)。
 * 缺失字段写 {@code null} 并在 {@code precheck.warnings} 中显式记录,绝不静默回退。
 */
public class ReportSummaryExtractor {

    public static final String SCHEMA_VERSION = "1.0";

    private static final String[] REQUIRED_TOPLEVEL = {"report_id", "summary"};
    private static final String[] REQUIRED_SUMMARY = {
            "arrived_count", "served_count", "abandoned_count", "timeline"
    };

    private final ObjectMapper mapper;

    public ReportSummaryExtractor() {
        this(AppBeansConfig.createReportObjectMapper());
    }

    public ReportSummaryExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** in-memory 入口:适用于 SimulationReportRepository.write 中已构造的 JsonNode。 */
    public ObjectNode extractFromJsonNode(JsonNode fullReport,
                                          Path sourcePath,
                                          long sourceSizeBytes,
                                          long sourceMtimeEpochMillis,
                                          long indexedAtEpochMillis) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("schema_version", SCHEMA_VERSION);

        String reportId = fullReport.path("report_id").asText("");
        summary.put("report_id", reportId);
        summary.put("indexed_at_epoch_millis", indexedAtEpochMillis);

        summary.set("source", buildSource(sourcePath, sourceSizeBytes, sourceMtimeEpochMillis,
                indexedAtEpochMillis, true, "present"));
        summary.set("report_meta", buildReportMeta(fullReport));
        summary.set("config", buildConfig(fullReport.path("config")));
        summary.set("metrics", buildMetrics(fullReport.path("summary")));
        summary.set("precheck", buildPrecheckFromJsonNode(fullReport));
        return summary;
    }

    /**
     * 流式入口:仅抽取 report_id / config / summary 中的摘要必需字段;
     * 跳过 history / table_snapshots / timeline 元素内容(只统计 timeline 长度)。
     * 堆增量 ~ 5MB 量级,与原文件大小无关。
     */
    public ObjectNode extractFromFile(Path sourcePath, long indexedAtEpochMillis) throws IOException {
        long size = -1;
        long mtime = -1;
        try {
            size = Files.size(sourcePath);
            mtime = Files.getLastModifiedTime(sourcePath).toMillis();
        } catch (IOException ignored) {
            // 极端情况:文件刚被删,继续按可拿到的字段构造 partial 摘要
        }

        ObjectNode summary = mapper.createObjectNode();
        summary.put("schema_version", SCHEMA_VERSION);
        summary.put("indexed_at_epoch_millis", indexedAtEpochMillis);

        ObjectNode source = buildSource(sourcePath, size, mtime, indexedAtEpochMillis,
                true, "present");
        summary.set("source", source);

        String reportId = "";
        ObjectNode reportMeta = mapper.createObjectNode();
        reportMeta.putNull("scenario_id");
        reportMeta.put("report_schema_version", "unknown");
        reportMeta.put("generated_at_epoch_millis", 0L);

        JsonNode configNode = null;
        JsonNode summaryScalars = null;
        int timelineCount = 0;
        boolean timelineMonotonic = true;

        JsonFactory factory = mapper.getFactory();
        try (JsonParser parser = factory.createParser(sourcePath.toFile())) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) {
                return failedExtract(summary, sourcePath, size, mtime, indexedAtEpochMillis,
                        "not_json_object", "top-level token is not an object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String fieldName = parser.currentName();
                parser.nextToken();
                switch (fieldName) {
                    case "report_id":
                        reportId = parser.getValueAsString("");
                        break;
                    case "report_version":
                        reportMeta.put("report_schema_version", parser.getValueAsString("unknown"));
                        break;
                    case "generated_at":
                        reportMeta.put("generated_at", parser.getValueAsString(""));
                        break;
                    case "generated_at_epoch_millis":
                        reportMeta.put("generated_at_epoch_millis", parser.getValueAsLong(0L));
                        break;
                    case "scenario_id":
                        reportMeta.put("scenario_id", parser.getValueAsString(""));
                        break;
                    case "config":
                        configNode = mapper.readTree(parser);
                        break;
                    case "summary":
                        SummaryWalk walk = walkSummary(parser);
                        summaryScalars = walk.scalars;
                        timelineCount = walk.timelineCount;
                        timelineMonotonic = walk.timelineMonotonic;
                        break;
                    default:
                        parser.skipChildren();
                        break;
                }
            }
        } catch (IOException ioe) {
            return failedExtract(summary, sourcePath, size, mtime, indexedAtEpochMillis,
                    "parse_io_error", ioe.getMessage());
        } catch (RuntimeException re) {
            return failedExtract(summary, sourcePath, size, mtime, indexedAtEpochMillis,
                    "parse_runtime_error", re.getMessage());
        }

        summary.put("report_id", reportId);
        summary.set("report_meta", reportMeta);
        summary.set("config", buildConfig(configNode == null ? mapper.nullNode() : configNode));
        summary.set("metrics", buildMetricsFromScalars(summaryScalars, timelineCount));
        summary.set("precheck", buildPrecheckFromScalars(reportId, summaryScalars, timelineCount,
                timelineMonotonic));
        return summary;
    }

    private ObjectNode failedExtract(ObjectNode summary, Path sourcePath, long size, long mtime,
                                     long indexedAt, String code, String message) {
        summary.set("source", buildSource(sourcePath, size, mtime, indexedAt, true, "unverified"));
        summary.put("report_id", "");

        ObjectNode reportMeta = mapper.createObjectNode();
        reportMeta.putNull("scenario_id");
        reportMeta.put("report_schema_version", "unknown");
        reportMeta.put("generated_at_epoch_millis", 0L);
        summary.set("report_meta", reportMeta);

        summary.set("config", buildConfig(mapper.nullNode()));
        summary.set("metrics", buildMetricsFromScalars(null, 0));

        ObjectNode precheck = mapper.createObjectNode();
        precheck.put("has_required_fields", false);
        ArrayNode missing = precheck.putArray("missing_fields");
        for (String key : REQUIRED_TOPLEVEL) missing.add(key);
        precheck.put("basic_invariants_valid", false);
        precheck.putArray("invariant_violations");
        precheck.put("timeline_monotonic", false);
        precheck.put("parse_status", "failed");
        precheck.put("parse_error_code", code);
        ArrayNode warnings = precheck.putArray("warnings");
        if (message != null && !message.isEmpty()) warnings.add(message);
        summary.set("precheck", precheck);
        return summary;
    }

    private ObjectNode buildSource(Path sourcePath,
                                   long size,
                                   long mtime,
                                   long checkedAt,
                                   boolean existsWhenIndexed,
                                   String status) {
        ObjectNode source = mapper.createObjectNode();
        source.put("original_report_path", sourcePath == null ? "" : sourcePath.toString());
        source.put("source_file_name", sourcePath == null ? "" : sourcePath.getFileName().toString());
        source.put("source_size_bytes", size);
        source.put("source_modified_time_epoch_millis", mtime);
        source.put("source_exists_when_indexed", existsWhenIndexed);
        source.put("source_status", status);
        source.put("source_status_checked_at_epoch_millis", checkedAt);
        return source;
    }

    private ObjectNode buildReportMeta(JsonNode fullReport) {
        ObjectNode meta = mapper.createObjectNode();
        JsonNode scenarioId = fullReport.path("scenario_id");
        if (scenarioId.isMissingNode() || scenarioId.isNull()) {
            meta.putNull("scenario_id");
        } else {
            meta.put("scenario_id", scenarioId.asText(""));
        }
        meta.put("report_schema_version", fullReport.path("report_version").asText("unknown"));
        meta.put("generated_at_epoch_millis", fullReport.path("generated_at_epoch_millis").asLong(0L));
        String generatedAt = fullReport.path("generated_at").asText("");
        if (!generatedAt.isEmpty()) meta.put("generated_at", generatedAt);
        return meta;
    }

    private ObjectNode buildConfig(JsonNode configNode) {
        ObjectNode config = mapper.createObjectNode();
        JsonNode base = configNode.path("base_config");
        JsonNode weather = configNode.path("weather_config");

        putOrNull(config, "arrival_rate", configNode.path("arrival_rate"));
        putOrNull(config, "duration", configNode.path("duration"));
        putOrNullInt(config, "window_count", base.path("window_count"));
        putOrNullInt(config, "total_seats", base.path("total_seats"));
        putOrNullInt(config, "takeaway_window_count", base.path("takeaway_window_count"));
        putOrNull(config, "pack_probability", configNode.path("pack_probability"));
        putOrNull(config, "weather_impact_factor", weather.path("weather_impact_factor"));
        putOrNullText(config, "weather_type", weather.path("current_weather"));
        putOrNullInt(config, "queue_limit", configNode.path("queue_limit"));
        putOrNullLong(config, "seed", configNode.path("seed"));

        config.put("config_fingerprint", computeFingerprint(config));
        return config;
    }

    private ObjectNode buildMetrics(JsonNode summaryNode) {
        ObjectNode metrics = mapper.createObjectNode();
        JsonNode arrived = summaryNode.path("arrived_count");
        JsonNode served = summaryNode.path("served_count");
        JsonNode abandoned = summaryNode.path("abandoned_count");

        putOrNullInt(metrics, "arrived_count", arrived);
        putOrNullInt(metrics, "served_count", served);
        putOrNullInt(metrics, "abandoned_count", abandoned);
        if (arrived.isNumber() && abandoned.isNumber() && arrived.asInt(0) > 0) {
            metrics.put("abandonment_rate", round3(abandoned.asDouble(0.0) / arrived.asDouble(1.0)));
        } else {
            metrics.putNull("abandonment_rate");
        }

        JsonNode waitMetrics = summaryNode.path("wait_time_metrics");
        putOrNull(metrics, "typical_wait_time_minutes",
                firstNumber(waitMetrics.path("typical_wait_time_minutes"),
                        summaryNode.path("typical_wait_time_minutes"),
                        summaryNode.path("avg_wait_time_minutes")));
        putOrNull(metrics, "avg_wait_time_minutes", summaryNode.path("avg_wait_time_minutes"));
        putOrNull(metrics, "seat_utilization_rate", summaryNode.path("seat_utilization_rate"));
        putOrNull(metrics, "takeaway_rate", summaryNode.path("takeaway_rate"));
        putOrNullInt(metrics, "max_total_queue_size", summaryNode.path("max_total_queue_size"));
        putOrNull(metrics, "avg_total_queue_size", summaryNode.path("avg_total_queue_size"));

        JsonNode timeline = summaryNode.path("timeline");
        metrics.put("timeline_points", timeline.isArray() ? timeline.size() : 0);
        return metrics;
    }

    private ObjectNode buildMetricsFromScalars(JsonNode scalars, int timelineCount) {
        ObjectNode metrics = mapper.createObjectNode();
        if (scalars == null) {
            metrics.putNull("arrived_count");
            metrics.putNull("served_count");
            metrics.putNull("abandoned_count");
            metrics.putNull("abandonment_rate");
            metrics.putNull("typical_wait_time_minutes");
            metrics.putNull("avg_wait_time_minutes");
            metrics.putNull("seat_utilization_rate");
            metrics.putNull("takeaway_rate");
            metrics.putNull("max_total_queue_size");
            metrics.putNull("avg_total_queue_size");
            metrics.put("timeline_points", timelineCount);
            return metrics;
        }
        ObjectNode m = buildMetrics(scalars);
        // streaming 路径 timeline 数组未保留在 scalars 中,显式覆盖 timeline_points
        m.put("timeline_points", timelineCount);
        return m;
    }

    private ObjectNode buildPrecheckFromJsonNode(JsonNode fullReport) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_TOPLEVEL) {
            JsonNode v = fullReport.path(key);
            if (v.isMissingNode() || v.isNull() || (v.isTextual() && v.asText().isEmpty())) {
                missing.add(key);
            }
        }
        JsonNode summaryNode = fullReport.path("summary");
        for (String key : REQUIRED_SUMMARY) {
            JsonNode v = summaryNode.path(key);
            if (v.isMissingNode() || v.isNull()) missing.add("summary." + key);
        }

        List<String> warnings = new ArrayList<>();
        if (fullReport.path("scenario_id").isMissingNode()) warnings.add("scenario_id_missing");
        if (fullReport.path("report_version").asText("").isEmpty()) {
            warnings.add("report_schema_version_unknown");
        }
        if (summaryNode.path("takeaway_rate_breakdown").isMissingNode()) {
            warnings.add("legacy_no_breakdown");
        }
        if (summaryNode.path("wait_time_metrics").path("wait_time_distribution").isMissingNode()) {
            warnings.add("wait_time_distribution_missing");
        }

        List<String> violations = computeInvariantViolations(summaryNode);
        boolean monotonic = isTimelineMonotonic(summaryNode.path("timeline"));

        ObjectNode precheck = mapper.createObjectNode();
        precheck.put("has_required_fields", missing.isEmpty());
        ArrayNode missingArray = precheck.putArray("missing_fields");
        for (String m : missing) missingArray.add(m);
        precheck.put("basic_invariants_valid", violations.isEmpty());
        ArrayNode violationsArray = precheck.putArray("invariant_violations");
        for (String v : violations) violationsArray.add(v);
        precheck.put("timeline_monotonic", monotonic);
        precheck.put("parse_status", "ok");
        precheck.putNull("parse_error_code");
        ArrayNode warningsArray = precheck.putArray("warnings");
        for (String w : warnings) warningsArray.add(w);
        return precheck;
    }

    private ObjectNode buildPrecheckFromScalars(String reportId, JsonNode scalars,
                                                int timelineCount, boolean timelineMonotonic) {
        List<String> missing = new ArrayList<>();
        if (reportId == null || reportId.isEmpty()) missing.add("report_id");
        if (scalars == null) {
            missing.add("summary");
            for (String key : REQUIRED_SUMMARY) missing.add("summary." + key);
        } else {
            for (String key : REQUIRED_SUMMARY) {
                JsonNode v = scalars.path(key);
                if (v.isMissingNode() || v.isNull()) {
                    if (!"timeline".equals(key) || timelineCount == 0) {
                        missing.add("summary." + key);
                    }
                }
            }
        }

        List<String> violations = scalars == null ? List.of() : computeInvariantViolations(scalars);
        List<String> warnings = new ArrayList<>();
        if (timelineCount == 0) warnings.add("timeline_empty");

        ObjectNode precheck = mapper.createObjectNode();
        precheck.put("has_required_fields", missing.isEmpty());
        ArrayNode missingArray = precheck.putArray("missing_fields");
        for (String m : missing) missingArray.add(m);
        precheck.put("basic_invariants_valid", violations.isEmpty());
        ArrayNode violationsArray = precheck.putArray("invariant_violations");
        for (String v : violations) violationsArray.add(v);
        precheck.put("timeline_monotonic", timelineMonotonic);
        precheck.put("parse_status", "ok");
        precheck.putNull("parse_error_code");
        ArrayNode warningsArray = precheck.putArray("warnings");
        for (String w : warnings) warningsArray.add(w);
        return precheck;
    }

    private List<String> computeInvariantViolations(JsonNode summaryNode) {
        List<String> violations = new ArrayList<>();
        int served = summaryNode.path("served_count").asInt(-1);
        int dineIn = summaryNode.path("dine_in_count").asInt(-1);
        int takeaway = summaryNode.path("takeaway_count").asInt(-1);
        if (served >= 0 && dineIn >= 0 && takeaway >= 0 && served != dineIn + takeaway) {
            violations.add("served_count != dine_in_count + takeaway_count");
        }

        double seatUtil = summaryNode.path("seat_utilization_rate").asDouble(Double.NaN);
        if (!Double.isNaN(seatUtil) && (seatUtil < 0.0 || seatUtil > 1.0001)) {
            violations.add("seat_utilization_rate out of [0, 1]");
        }
        double takeawayRate = summaryNode.path("takeaway_rate").asDouble(Double.NaN);
        if (!Double.isNaN(takeawayRate) && (takeawayRate < 0.0 || takeawayRate > 1.0001)) {
            violations.add("takeaway_rate out of [0, 1]");
        }

        int maxQueue = summaryNode.path("max_total_queue_size").asInt(-1);
        double avgQueue = summaryNode.path("avg_total_queue_size").asDouble(Double.NaN);
        if (maxQueue >= 0 && !Double.isNaN(avgQueue) && maxQueue + 0.001 < avgQueue) {
            violations.add("max_total_queue_size < avg_total_queue_size");
        }

        int maxOcc = summaryNode.path("max_occupied_seats").asInt(-1);
        double avgOcc = summaryNode.path("avg_occupied_seats").asDouble(Double.NaN);
        int totalSeats = summaryNode.path("total_seats").asInt(-1);
        if (maxOcc >= 0 && !Double.isNaN(avgOcc) && maxOcc + 0.001 < avgOcc) {
            violations.add("max_occupied_seats < avg_occupied_seats");
        }
        if (totalSeats >= 0 && maxOcc > totalSeats) {
            violations.add("max_occupied_seats > total_seats");
        }
        return violations;
    }

    private boolean isTimelineMonotonic(JsonNode timeline) {
        if (!timeline.isArray()) return true;
        long prev = Long.MIN_VALUE;
        for (JsonNode point : timeline) {
            JsonNode v = point.path("cumulative_arrived_count");
            if (!v.isNumber()) continue;
            long cur = v.asLong();
            if (cur < prev) return false;
            prev = cur;
        }
        return true;
    }

    private SummaryWalk walkSummary(JsonParser parser) throws IOException {
        SummaryWalk walk = new SummaryWalk();
        ObjectNode scalars = mapper.createObjectNode();

        if (parser.currentToken() != JsonToken.START_OBJECT) {
            walk.scalars = scalars;
            return walk;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken t = parser.nextToken();
            switch (name) {
                case "history":
                case "table_snapshots":
                case "seat_cells":
                case "arrival_samples":
                case "takeaway_decision_records":
                    parser.skipChildren();
                    break;
                case "timeline":
                    if (t == JsonToken.START_ARRAY) {
                        long prev = Long.MIN_VALUE;
                        boolean monotonic = true;
                        int count = 0;
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            // 当前 token = 元素首 token。仅扫顶层 cumulative_arrived_count,其他跳过。
                            if (parser.currentToken() == JsonToken.START_OBJECT) {
                                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                                    String inner = parser.currentName();
                                    parser.nextToken();
                                    if ("cumulative_arrived_count".equals(inner) && monotonic) {
                                        long cur = parser.getValueAsLong(prev);
                                        if (cur < prev) monotonic = false;
                                        prev = cur;
                                    } else {
                                        parser.skipChildren();
                                    }
                                }
                            } else {
                                parser.skipChildren();
                            }
                            count++;
                        }
                        walk.timelineCount = count;
                        walk.timelineMonotonic = monotonic;
                    } else {
                        parser.skipChildren();
                    }
                    break;
                case "wait_time_metrics":
                    if (t == JsonToken.START_OBJECT) {
                        scalars.set("wait_time_metrics", readBoundedTree(parser));
                    } else {
                        parser.skipChildren();
                    }
                    break;
                default:
                    if (t == JsonToken.START_ARRAY || t == JsonToken.START_OBJECT) {
                        parser.skipChildren();
                    } else {
                        switch (t) {
                            case VALUE_NUMBER_INT:
                                scalars.put(name, parser.getLongValue());
                                break;
                            case VALUE_NUMBER_FLOAT:
                                scalars.put(name, parser.getDoubleValue());
                                break;
                            case VALUE_TRUE:
                            case VALUE_FALSE:
                                scalars.put(name, parser.getBooleanValue());
                                break;
                            case VALUE_STRING:
                                scalars.put(name, parser.getValueAsString(""));
                                break;
                            case VALUE_NULL:
                                scalars.putNull(name);
                                break;
                            default:
                                break;
                        }
                    }
                    break;
            }
        }
        walk.scalars = scalars;
        return walk;
    }

    private JsonNode readBoundedTree(JsonParser parser) throws IOException {
        // 读 wait_time_metrics 子树。Distribution 数组通常 < 30 桶,不会膨胀。
        return mapper.readTree(parser);
    }

    private void putOrNull(ObjectNode obj, String name, JsonNode v) {
        if (v != null && v.isNumber()) obj.put(name, round3(v.asDouble()));
        else obj.putNull(name);
    }

    private void putOrNullInt(ObjectNode obj, String name, JsonNode v) {
        if (v != null && v.isNumber()) obj.put(name, v.asLong());
        else obj.putNull(name);
    }

    private void putOrNullLong(ObjectNode obj, String name, JsonNode v) {
        if (v != null && v.isNumber()) obj.put(name, v.asLong());
        else obj.putNull(name);
    }

    private void putOrNullText(ObjectNode obj, String name, JsonNode v) {
        if (v != null && v.isTextual()) obj.put(name, v.asText());
        else if (v != null && !v.isMissingNode() && !v.isNull()) obj.put(name, v.asText(""));
        else obj.putNull(name);
    }

    private JsonNode firstNumber(JsonNode... candidates) {
        for (JsonNode c : candidates) if (c != null && c.isNumber()) return c;
        return null;
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * 固定字段顺序 SHA-1 短指纹,前 12 hex。
     * 字段集严格固定,seed 不参与(同配置不同 seed 应得同一指纹)。
     */
    public static String computeFingerprint(ObjectNode config) {
        StringBuilder sb = new StringBuilder();
        sb.append(numOrNa(config.get("arrival_rate"))).append('|');
        sb.append(numOrNa(config.get("duration"))).append('|');
        sb.append(numOrNa(config.get("window_count"))).append('|');
        sb.append(numOrNa(config.get("total_seats"))).append('|');
        sb.append(numOrNa(config.get("takeaway_window_count"))).append('|');
        sb.append(numOrNa(config.get("pack_probability"))).append('|');
        sb.append(textOrNa(config.get("weather_type"))).append('|');
        sb.append(numOrNa(config.get("queue_limit")));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return "sha1:" + hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "sha1:unavailable";
        }
    }

    private static String numOrNa(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) return "NA";
        if (n.isIntegralNumber()) return Long.toString(n.asLong());
        return String.format(Locale.ROOT, "%.6f", n.asDouble());
    }

    private static String textOrNa(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) return "NA";
        return n.asText("NA");
    }

    private static final class SummaryWalk {
        ObjectNode scalars;
        int timelineCount;
        boolean timelineMonotonic = true;
    }
}
