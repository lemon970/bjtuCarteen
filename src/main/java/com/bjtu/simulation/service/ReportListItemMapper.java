package com.bjtu.simulation.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ReportListItemMapper {
    private final ObjectMapper mapper;

    ReportListItemMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toReportListItem(JsonNode report, Path path) {
        ObjectNode item = mapper.createObjectNode();
        JsonNode config = report.path("config");
        JsonNode summary = report.path("summary");

        item.put("report_id", textValue(report, "report_id", "reportId", extractReportIdFromFileName(path)));
        item.put("report_version", textValue(report, "report_version", "reportVersion", ""));
        item.put("generated_at", textValue(report, "generated_at", "generatedAt", ""));
        item.put("generated_at_epoch_millis", longValue(report, 0L, "generated_at_epoch_millis", "generatedAtEpochMillis"));
        item.put("effective_seed", longValue(report, 0L, "effective_seed", "effectiveSeed"));
        item.put("file_name", path.getFileName().toString());
        item.put("file_size_bytes", fileSize(path));
        item.put("file_modified_epoch_millis", lastModifiedMillis(path));
        item.put("simulation_name", textValue(config, "simulation_name", "simulationName", "default-simulation"));
        item.set("config_snapshot", config.isMissingNode() ? mapper.getNodeFactory().nullNode() : config.deepCopy());
        item.set("summary_snapshot", summarySnapshot(summary));
        return item;
    }

    static String extractReportIdFromFileName(Path path) {
        String fileName = path.getFileName().toString();
        String prefix = "simulation-report-";
        String suffix = ".json";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) {
            return "";
        }

        String body = fileName.substring(prefix.length(), fileName.length() - suffix.length());
        int firstDash = body.indexOf('-');
        int secondDash = firstDash < 0 ? -1 : body.indexOf('-', firstDash + 1);
        if (secondDash < 0 || secondDash + 1 >= body.length()) {
            return "";
        }
        return body.substring(secondDash + 1);
    }

    private ObjectNode summarySnapshot(JsonNode summary) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("arrived_count", intValue(summary, 0, "arrived_count", "arrivedCount"));
        snapshot.put("abandoned_count", intValue(summary, 0, "abandoned_count", "abandonedCount"));
        snapshot.put("served_count", intValue(summary, 0, "served_count", "servedCount"));
        snapshot.put("dine_in_count", intValue(summary, 0, "dine_in_count", "dineInCount"));
        snapshot.put("takeaway_count", intValue(summary, 0, "takeaway_count", "takeawayCount"));
        snapshot.put("avg_wait_time_minutes", doubleValue(summary, 0, "avg_wait_time_minutes", "avgWaitTimeMinutes"));
        snapshot.put("raw_avg_wait_time_minutes", doubleValue(summary, 0, "raw_avg_wait_time_minutes", "rawAvgWaitTimeMinutes", "avg_wait_time_minutes", "avgWaitTimeMinutes"));
        snapshot.put("steady_avg_wait_time_minutes", doubleValue(summary, 0, "steady_avg_wait_time_minutes", "steadyAvgWaitTimeMinutes"));
        snapshot.put("typical_wait_time_minutes", doubleValue(summary, 0, "typical_wait_time_minutes", "typicalWaitTimeMinutes"));
        snapshot.put("median_wait_time_minutes", doubleValue(summary, 0, "median_wait_time_minutes", "medianWaitTimeMinutes"));
        snapshot.put("p75_wait_time_minutes", doubleValue(summary, 0, "p75_wait_time_minutes", "p75WaitTimeMinutes"));
        snapshot.put("p90_wait_time_minutes", doubleValue(summary, 0, "p90_wait_time_minutes", "p90WaitTimeMinutes"));
        snapshot.put("long_wait_rate", doubleValue(summary, 0, "long_wait_rate", "longWaitRate"));
        snapshot.put("zero_wait_rate", doubleValue(summary, 0, "zero_wait_rate", "zeroWaitRate"));
        snapshot.put("edge_wait_sample_rate", doubleValue(summary, 0, "edge_wait_sample_rate", "edgeWaitSampleRate"));
        snapshot.put("avg_movement_time_minutes", doubleValue(summary, 0, "avg_movement_time_minutes", "avgMovementTimeMinutes"));
        snapshot.put("max_queue_size", intValue(summary, 0, "max_queue_size", "maxQueueSize"));
        snapshot.put("max_total_queue_size", intValue(summary, 0, "max_total_queue_size", "maxTotalQueueSize"));
        snapshot.put("peak_time_minutes", longValue(summary, 0L, "peak_time_minutes", "peakTimeMinutes"));
        snapshot.put("peak_window_id", intValue(summary, -1, "peak_window_id", "peakWindowId"));
        snapshot.put("seat_utilization_rate", doubleValue(summary, 0, "seat_utilization_rate", "seatUtilizationRate"));
        snapshot.put("normal_window_count", intValue(summary, 0, "normal_window_count", "normalWindowCount"));
        snapshot.put("takeaway_window_count", intValue(summary, 0, "takeaway_window_count", "takeawayWindowCount"));
        snapshot.put("takeaway_window_served_count", intValue(summary, 0, "takeaway_window_served_count", "takeawayWindowServedCount"));
        snapshot.put("takeaway_rate", doubleValue(summary, 0, "takeaway_rate", "takeawayRate"));
        snapshot.put("dine_in_rate", doubleValue(summary, 0, "dine_in_rate", "dineInRate"));
        snapshot.put("takeaway_window_ratio", doubleValue(summary, 0, "takeaway_window_ratio", "takeawayWindowRatio"));
        snapshot.put("takeaway_window_served_rate", doubleValue(summary, 0, "takeaway_window_served_rate", "takeawayWindowServedRate"));
        JsonNode queueTheory = summary.path("queue_theory_metrics");
        if (queueTheory.isMissingNode() || queueTheory.isNull()) {
            queueTheory = summary.path("queueTheoryMetrics");
        }
        snapshot.set("queue_theory_metrics", queueTheory.isMissingNode() ? mapper.getNodeFactory().nullNode() : queueTheory.deepCopy());
        return snapshot;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String textValue(JsonNode node, String snakeName, String camelName, String defaultValue) {
        JsonNode value = valueNode(node, snakeName, camelName);
        return value == null ? defaultValue : value.asText(defaultValue);
    }

    private int intValue(JsonNode node, int defaultValue, String snakeName, String camelName) {
        JsonNode value = valueNode(node, snakeName, camelName);
        return value == null ? defaultValue : value.asInt(defaultValue);
    }

    private long longValue(JsonNode node, long defaultValue, String snakeName, String camelName) {
        JsonNode value = valueNode(node, snakeName, camelName);
        return value == null ? defaultValue : value.asLong(defaultValue);
    }

    private double doubleValue(JsonNode node, double defaultValue, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asDouble(defaultValue);
            }
        }
        return defaultValue;
    }

    private JsonNode valueNode(JsonNode node, String snakeName, String camelName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(snakeName);
        if (value.isMissingNode() || value.isNull()) {
            value = node.path(camelName);
        }
        return value.isMissingNode() || value.isNull() ? null : value;
    }
}
