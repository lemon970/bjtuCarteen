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

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SimulationReportRepository {
    private static final Path REPORTS_DIR = Path.of("reports");
    private static final String LATEST_REPORT_FILE = "simulation-report-latest.json";
    private static final String HISTORY_FILE_PREFIX = "simulation-history-";
    private static final String JSON_SUFFIX = ".json";

    private final ObjectMapper reportMapper;
    private final ReportListItemMapper listItemMapper;

    @Autowired
    public SimulationReportRepository() {
        this(AppBeansConfig.createReportObjectMapper());
    }

    public SimulationReportRepository(ObjectMapper reportMapper) {
        this.reportMapper = reportMapper;
        this.listItemMapper = new ReportListItemMapper(reportMapper);
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
            reports.add(listItemMapper.toReportListItem(report, path));
        } catch (IOException e) {
            ObjectNode item = reportMapper.createObjectNode();
            item.put("file_name", path.getFileName().toString());
            item.put("parse_error", true);
            item.put("message", "failed to parse report file");
            reports.add(item);
        }
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
                if (reportId.equals(ReportListItemMapper.extractReportIdFromFileName(path))) {
                    return path;
                }
            }
            for (Path path : candidates) {
                try {
                    JsonNode report = reportMapper.readTree(path.toFile());
                    String contentId = readReportId(report);
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

    private String readReportId(JsonNode report) {
        if (report == null || report.isMissingNode() || report.isNull()) {
            return "";
        }
        JsonNode value = report.path("report_id");
        if (value.isMissingNode() || value.isNull()) {
            value = report.path("reportId");
        }
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
