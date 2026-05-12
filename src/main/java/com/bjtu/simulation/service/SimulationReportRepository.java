package com.bjtu.simulation.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SimulationReportRepository {
    private static final Path REPORTS_DIR = Path.of("reports");
    private static final String LATEST_REPORT_FILE = "simulation-report-latest.json";
    private static final String HISTORY_FILE_PREFIX = "simulation-history-";
    private static final String JSON_SUFFIX = ".json";

    private final ObjectMapper reportMapper;

    public SimulationReportRepository(ObjectMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public void write(SimulationReport report) {
        try {
            Files.createDirectories(REPORTS_DIR);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = REPORTS_DIR.resolve("simulation-report-" + timestamp + "-" + report.getReportId() + ".json");
            JsonNode fullReport = reportMapper.valueToTree(report);
            writeHistory(report.getReportId(), fullReport.path("summary").path("history"));
            JsonNode compactReport = compactReportNode(fullReport);
            writeJsonWithRetry(reportPath, compactReport);
            Path latestPath = REPORTS_DIR.resolve(LATEST_REPORT_FILE);
            writeJsonWithRetry(latestPath, compactReport);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write simulation report", e);
        }
    }

    public Optional<JsonNode> readLatest() {
        Path latestPath = REPORTS_DIR.resolve(LATEST_REPORT_FILE);
        if (!Files.exists(latestPath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(reportMapper.readTree(latestPath.toFile()));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read latest report", e);
        }
    }

    public JsonNode listReports() {
        ObjectNode data = reportMapper.createObjectNode();
        ArrayNode reports = reportMapper.createArrayNode();

        if (!Files.exists(REPORTS_DIR)) {
            data.put("count", 0);
            data.set("reports", reports);
            return data;
        }

        try (Stream<Path> paths = Files.list(REPORTS_DIR)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isHistoricalReportFile)
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed())
                    .forEach(path -> addReportListItem(reports, path));
            data.put("count", reports.size());
            data.set("reports", reports);
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("failed to list reports", e);
        }
    }

    public Optional<JsonNode> readById(String reportId) {
        Path reportPath = findReportPathById(reportId);
        if (reportPath == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(reportMapper.readTree(reportPath.toFile()));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read report", e);
        }
    }

    public Optional<JsonNode> readHistoryById(String reportId) {
        if (!isSafeReportId(reportId)) {
            return Optional.empty();
        }

        Path historyPath = REPORTS_DIR.resolve(HISTORY_FILE_PREFIX + reportId + JSON_SUFFIX);
        if (Files.exists(historyPath)) {
            try {
                return Optional.of(reportMapper.readTree(historyPath.toFile()));
            } catch (IOException e) {
                throw new IllegalStateException("failed to read report history", e);
            }
        }

        Optional<JsonNode> report = readById(reportId);
        if (report.isPresent()) {
            JsonNode history = report.get().path("summary").path("history");
            if (history.isArray()) {
                return Optional.of(history);
            }
        }
        return Optional.empty();
    }

    public boolean isSafeReportId(String reportId) {
        return reportId != null && reportId.matches("[A-Za-z0-9._-]+");
    }

    private boolean isHistoricalReportFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("simulation-report-")
                && fileName.endsWith(".json")
                && !LATEST_REPORT_FILE.equals(fileName);
    }

    private void writeHistory(String reportId, JsonNode history) throws IOException {
        JsonNode safeHistory = history != null && history.isArray() ? history : reportMapper.createArrayNode();
        Path historyPath = REPORTS_DIR.resolve(HISTORY_FILE_PREFIX + reportId + JSON_SUFFIX);
        writeJsonWithRetry(historyPath, safeHistory);
    }

    // [重构] 报告写入改为临时文件替换并短暂重试，原因是 Windows 上 latest 文件偶发被映射锁占用。
    private void writeJsonWithRetry(Path target, JsonNode data) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Path tempPath = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().getId() + "-" + attempt);
            try {
                reportMapper.writeValue(tempPath.toFile(), data);
                Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tempPath);
                sleepBeforeRetry(attempt);
            }
        }
        throw last == null ? new IOException("failed to write json file") : last;
    }

    private void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(40L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while retrying report write", e);
        }
    }

    private JsonNode compactReportNode(JsonNode reportNode) {
        JsonNode copy = reportNode == null ? MissingNode.getInstance() : reportNode.deepCopy();
        if (copy instanceof ObjectNode reportObject) {
            JsonNode summary = reportObject.path("summary");
            if (summary instanceof ObjectNode summaryObject) {
                summaryObject.remove("history");
                stripTimelineTableSnapshots(summaryObject);
            }
        }
        return copy;
    }

    private void stripTimelineTableSnapshots(ObjectNode summaryObject) {
        JsonNode timeline = summaryObject.path("timeline");
        if (!timeline.isArray()) {
            return;
        }
        for (JsonNode item : timeline) {
            if (item instanceof ObjectNode itemObject) {
                itemObject.remove("table_snapshots");
            }
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void addReportListItem(ArrayNode reports, Path path) {
        try {
            JsonNode report = reportMapper.readTree(path.toFile());
            reports.add(toReportListItem(report, path));
        } catch (IOException e) {
            ObjectNode item = reportMapper.createObjectNode();
            item.put("file_name", path.getFileName().toString());
            item.put("parse_error", true);
            item.put("message", "failed to parse report file");
            reports.add(item);
        }
    }

    private ObjectNode toReportListItem(JsonNode report, Path path) {
        ObjectNode item = reportMapper.createObjectNode();
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
        item.set("config_snapshot", config.isMissingNode() ? reportMapper.getNodeFactory().nullNode() : config.deepCopy());

        ObjectNode summarySnapshot = reportMapper.createObjectNode();
        summarySnapshot.put("arrived_count", intValue(summary, 0, "arrived_count", "arrivedCount"));
        summarySnapshot.put("abandoned_count", intValue(summary, 0, "abandoned_count", "abandonedCount"));
        summarySnapshot.put("served_count", intValue(summary, 0, "served_count", "servedCount"));
        summarySnapshot.put("dine_in_count", intValue(summary, 0, "dine_in_count", "dineInCount"));
        summarySnapshot.put("takeaway_count", intValue(summary, 0, "takeaway_count", "takeawayCount"));
        summarySnapshot.put("avg_wait_time_minutes", doubleValue(summary, 0, "avg_wait_time_minutes", "avgWaitTimeMinutes"));
        summarySnapshot.put("avg_movement_time_minutes", doubleValue(summary, 0, "avg_movement_time_minutes", "avgMovementTimeMinutes"));
        summarySnapshot.put("total_movement_time_minutes", doubleValue(summary, 0, "total_movement_time_minutes", "totalMovementTimeMinutes"));
        summarySnapshot.put("max_queue_size", intValue(summary, 0, "max_queue_size", "maxQueueSize"));
        summarySnapshot.put("max_total_queue_size", intValue(summary, 0, "max_total_queue_size", "maxTotalQueueSize"));
        summarySnapshot.put("peak_time_minutes", longValue(summary, 0L, "peak_time_minutes", "peakTimeMinutes"));
        summarySnapshot.put("peak_window_id", intValue(summary, -1, "peak_window_id", "peakWindowId"));
        summarySnapshot.put("seat_utilization_rate", doubleValue(summary, 0, "seat_utilization_rate", "seatUtilizationRate"));
        summarySnapshot.put("normal_window_count", intValue(summary, 0, "normal_window_count", "normalWindowCount"));
        summarySnapshot.put("takeaway_window_count", intValue(summary, 0, "takeaway_window_count", "takeawayWindowCount"));
        summarySnapshot.put("takeaway_window_served_count", intValue(summary, 0, "takeaway_window_served_count", "takeawayWindowServedCount"));
        summarySnapshot.put("takeaway_rate", doubleValue(summary, 0, "takeaway_rate", "takeawayRate"));
        summarySnapshot.put("dine_in_rate", doubleValue(summary, 0, "dine_in_rate", "dineInRate"));
        summarySnapshot.put("takeaway_window_ratio", doubleValue(summary, 0, "takeaway_window_ratio", "takeawayWindowRatio"));
        summarySnapshot.put("takeaway_window_served_rate", doubleValue(summary, 0, "takeaway_window_served_rate", "takeawayWindowServedRate"));
        JsonNode queueTheory = summary.path("queue_theory_metrics");
        if (queueTheory.isMissingNode() || queueTheory.isNull()) {
            queueTheory = summary.path("queueTheoryMetrics");
        }
        summarySnapshot.set("queue_theory_metrics", queueTheory.isMissingNode() ? reportMapper.getNodeFactory().nullNode() : queueTheory.deepCopy());
        item.set("summary_snapshot", summarySnapshot);

        return item;
    }

    private Path findReportPathById(String reportId) {
        if (!Files.exists(REPORTS_DIR)) {
            return null;
        }

        try (Stream<Path> paths = Files.list(REPORTS_DIR)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                    .filter(this::isHistoricalReportFile)
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed())
                    .toList();

            for (Path path : candidates) {
                if (reportId.equals(extractReportIdFromFileName(path))) {
                    return path;
                }
            }
            for (Path path : candidates) {
                try {
                    JsonNode report = reportMapper.readTree(path.toFile());
                    String contentId = textValue(report, "report_id", "reportId", "");
                    if (reportId.equals(contentId)) {
                        return path;
                    }
                } catch (IOException ignored) {
                    // Unreadable historical reports are surfaced by listReports().
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to find report", e);
        }

        return null;
    }

    private String extractReportIdFromFileName(Path path) {
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

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String textValue(JsonNode node, String snakeName, String camelName, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(snakeName);
        if (value.isMissingNode() || value.isNull()) {
            value = node.path(camelName);
        }
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText(defaultValue);
    }

    private int intValue(JsonNode node, int defaultValue, String snakeName, String camelName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(snakeName);
        if (value.isMissingNode() || value.isNull()) {
            value = node.path(camelName);
        }
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }

    private long longValue(JsonNode node, long defaultValue, String snakeName, String camelName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(snakeName);
        if (value.isMissingNode() || value.isNull()) {
            value = node.path(camelName);
        }
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asLong(defaultValue);
    }

    private double doubleValue(JsonNode node, double defaultValue, String snakeName, String camelName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(snakeName);
        if (value.isMissingNode() || value.isNull()) {
            value = node.path(camelName);
        }
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asDouble(defaultValue);
    }
}
