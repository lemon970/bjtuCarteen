package com.bjtu.simulation.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 摘要数据层。摘要文件落到 {@code analysis-store/report-summaries/{reportId}.summary.json},
 * 与 {@code reports/} 物理隔离 —— 清理 reports/ 不会影响摘要。
 *
 * 写策略:tmp + atomic move,失败重试 5 次。
 * 读策略:逐文件 try/catch,损坏跳过。
 * 重建策略:rebuildFromReports 仅加性,不删 missing/deleted 摘要。
 */
@Service
public class ReportSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(ReportSummaryStore.class);

    private static final String SUMMARY_DIR_NAME = "report-summaries";
    private static final String QUARANTINE_DIR_NAME = "quarantine";
    private static final String SUMMARY_SUFFIX = ".summary.json";
    private static final String REPORT_PREFIX = "simulation-report-";
    private static final String REPORT_SUFFIX = ".json";
    private static final String LATEST_REPORT_FILE = "simulation-report-latest.json";
    private static final String HISTORY_FILE_PREFIX = "simulation-history-";

    private final Path analysisStoreRoot;
    private final Path reportsRoot;
    private final ObjectMapper mapper;
    private final ReportSummaryExtractor extractor;

    @Autowired
    public ReportSummaryStore(@Value("${analysis.store.path:./analysis-store}") String analysisStorePath) {
        this(Paths.get(analysisStorePath), Paths.get("reports"),
                AppBeansConfig.createReportObjectMapper(),
                new ReportSummaryExtractor());
    }

    public ReportSummaryStore(Path analysisStoreRoot, Path reportsRoot,
                              ObjectMapper mapper, ReportSummaryExtractor extractor) {
        this.analysisStoreRoot = analysisStoreRoot;
        this.reportsRoot = reportsRoot;
        this.mapper = mapper;
        this.extractor = extractor;
    }

    /**
     * Fail-fast:analysis-store 路径不能位于 reports/ 内部
     * (防止误配置导致清理脚本一并删除摘要)。
     */
    @PostConstruct
    public void validateConfiguration() {
        Path absStore;
        Path absReports;
        try {
            absStore = analysisStoreRoot.toAbsolutePath().normalize();
            absReports = reportsRoot.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to resolve analysis store / reports paths", e);
        }
        if (absStore.startsWith(absReports)) {
            throw new IllegalStateException(
                    "analysis.store.path must NOT be inside reports/ (analysisStore=" + absStore
                            + ", reports=" + absReports
                            + "). Cleaning reports/ would otherwise delete long-term summaries.");
        }
    }

    public Path getAnalysisStoreRoot() {
        return analysisStoreRoot;
    }

    public Path getSummaryDir() {
        return analysisStoreRoot.resolve(SUMMARY_DIR_NAME);
    }

    public Path getQuarantineDir() {
        return analysisStoreRoot.resolve(QUARANTINE_DIR_NAME);
    }

    /** 主入口:在 SimulationReportRepository.write 末尾以 try/catch 调,失败仅 log,不抛。 */
    public void upsert(String reportId, JsonNode fullReport, Path sourcePath) throws IOException {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId is required");
        }
        if (!isSafeReportId(reportId)) {
            throw new IllegalArgumentException("unsafe reportId: " + reportId);
        }
        long size = -1;
        long mtime = -1;
        if (sourcePath != null) {
            try {
                size = Files.size(sourcePath);
                mtime = Files.getLastModifiedTime(sourcePath).toMillis();
            } catch (IOException ignored) {
                // 写入瞬间源还没落,留空,verify 后续会修
            }
        }
        ObjectNode summary = extractor.extractFromJsonNode(
                fullReport, sourcePath, size, mtime, System.currentTimeMillis());
        writeSummary(reportId, summary);
    }

    /** Backfill 入口:流式读 20MB+ 源文件,堆有界。返回 true 表示完整解析,false 表示已落 failure 占位。 */
    public boolean upsertFromFile(Path sourcePath) throws IOException {
        if (sourcePath == null) throw new IllegalArgumentException("sourcePath is required");
        ObjectNode summary = extractor.extractFromFile(sourcePath, System.currentTimeMillis());
        String reportId = summary.path("report_id").asText("");
        if (reportId.isEmpty() || !isSafeReportId(reportId)) {
            // 无法识别 report_id 的源文件:用文件名兜底,防止互相覆盖
            String fallback = deriveIdFromFileName(sourcePath.getFileName().toString());
            if (fallback != null && isSafeReportId(fallback)) {
                summary.put("report_id", fallback);
                reportId = fallback;
            } else {
                throw new IOException("cannot derive safe reportId from file: " + sourcePath);
            }
        }
        writeSummary(reportId, summary);
        return !"failed".equals(summary.path("precheck").path("parse_status").asText());
    }

    public Optional<JsonNode> read(String reportId) {
        if (!isSafeReportId(reportId)) return Optional.empty();
        Path file = summaryFileFor(reportId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(file.toFile()));
        } catch (IOException e) {
            log.warn("corrupt summary file ignored: {}", file, e);
            return Optional.empty();
        }
    }

    public List<JsonNode> list() {
        Path dir = getSummaryDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<JsonNode> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUMMARY_SUFFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            out.add(mapper.readTree(p.toFile()));
                        } catch (IOException e) {
                            log.warn("skipping corrupt summary {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("failed to list summary dir {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * 加性重建:扫 reports/,为每个找到的源报告 upsert 摘要。
     * 已存在但源缺失的摘要(source_status=missing/deleted)绝不删除、不覆盖。
     */
    public RebuildOutcome rebuildFromReports() {
        RebuildOutcome outcome = new RebuildOutcome();
        if (!Files.isDirectory(reportsRoot)) {
            outcome.errors.add("reports_dir_missing:" + reportsRoot);
            return outcome;
        }
        try (Stream<Path> stream = Files.list(reportsRoot)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(ReportSummaryStore::isCandidateReportFile)
                    .toList();
            for (Path file : files) {
                try {
                    boolean fullParse = upsertFromFile(file);
                    if (fullParse) outcome.indexed++;
                    else {
                        outcome.skipped++;
                        outcome.errors.add(file.getFileName() + ":parse_failed_placeholder_written");
                    }
                } catch (IOException | RuntimeException e) {
                    outcome.skipped++;
                    outcome.errors.add(file.getFileName() + ":" + e.getMessage());
                    writeFailedPlaceholder(file, e);
                }
            }
        } catch (IOException e) {
            outcome.errors.add("list_failed:" + e.getMessage());
        }
        return outcome;
    }

    /**
     * 只读 + 状态更新:遍历每个摘要,检查 source 当前 size/mtime,
     * 写回新的 source_status。不删除任何摘要文件。
     */
    public VerifyOutcome verifySummaryStore() {
        VerifyOutcome outcome = new VerifyOutcome();
        Path dir = getSummaryDir();
        if (!Files.isDirectory(dir)) return outcome;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUMMARY_SUFFIX))
                    .toList();
            for (Path summaryFile : files) {
                try {
                    JsonNode tree = mapper.readTree(summaryFile.toFile());
                    if (!tree.isObject()) {
                        outcome.corrupted.add(summaryFile.getFileName().toString());
                        continue;
                    }
                    ObjectNode summary = (ObjectNode) tree;
                    SourceCheck check = computeSourceStatus(summary);
                    applySourceCheck(summary, check);
                    writeSummaryFile(summaryFile, summary);
                    switch (check.status) {
                        case "present": outcome.present++; break;
                        case "stale": outcome.stale++; break;
                        case "missing": outcome.missing++; break;
                        case "deleted": outcome.deleted++; break;
                        default: outcome.unverified++; break;
                    }
                } catch (IOException e) {
                    outcome.corrupted.add(summaryFile.getFileName().toString());
                }
            }
        } catch (IOException e) {
            outcome.errors.add("list_failed:" + e.getMessage());
        }
        return outcome;
    }

    /**
     * 把无法解析的摘要 move 到 quarantine/,其他原位不动。返回被隔离的文件名列表。
     */
    public RepairOutcome repairSummaryStore() {
        RepairOutcome outcome = new RepairOutcome();
        Path dir = getSummaryDir();
        if (!Files.isDirectory(dir)) return outcome;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUMMARY_SUFFIX))
                    .toList();
            for (Path file : files) {
                try {
                    JsonNode tree = mapper.readTree(file.toFile());
                    if (!tree.isObject()) {
                        moveToQuarantine(file, outcome);
                    }
                } catch (IOException e) {
                    moveToQuarantine(file, outcome);
                }
            }
        } catch (IOException e) {
            outcome.errors.add("list_failed:" + e.getMessage());
        }
        return outcome;
    }

    /** 占位:第一版禁用,需 phase 2/3 显式启用。 */
    public void compactSummaryStore() {
        throw new UnsupportedOperationException("compactSummaryStore is disabled in phase 1");
    }

    /** 占位:危险操作,第一版禁用。 */
    public void fullResetSummaryStore() {
        throw new UnsupportedOperationException("fullResetSummaryStore is disabled in phase 1");
    }

    // ---- helpers ----

    private void moveToQuarantine(Path file, RepairOutcome outcome) {
        try {
            Files.createDirectories(getQuarantineDir());
            String stamp = String.format(Locale.ROOT, "%d", System.currentTimeMillis());
            String name = file.getFileName().toString();
            String renamed = name.replace(SUMMARY_SUFFIX,
                    ".summary.broken-" + stamp + ".json");
            Path target = getQuarantineDir().resolve(renamed);
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            outcome.quarantined.add(file.getFileName().toString());
        } catch (IOException e) {
            outcome.errors.add(file.getFileName() + ":" + e.getMessage());
        }
    }

    private void applySourceCheck(ObjectNode summary, SourceCheck check) {
        ObjectNode source = (ObjectNode) summary.path("source");
        if (source.isMissingNode() || source.isNull()) {
            source = mapper.createObjectNode();
            summary.set("source", source);
        }
        String prevStatus = source.path("source_status").asText("unverified");
        String newStatus = check.status;
        if ("missing".equals(newStatus) && "present".equals(prevStatus)) {
            newStatus = "deleted"; // 主动观测到 present → missing 的转移
        }
        source.put("source_status", newStatus);
        source.put("source_status_checked_at_epoch_millis", System.currentTimeMillis());
        if (check.status.equals("stale")) {
            ObjectNode precheck = (ObjectNode) summary.path("precheck");
            if (precheck.isObject()) {
                JsonNode warnings = precheck.path("warnings");
                if (warnings.isArray()) {
                    boolean has = false;
                    for (JsonNode w : warnings) {
                        if ("source_modified_after_index".equals(w.asText(""))) { has = true; break; }
                    }
                    if (!has) ((com.fasterxml.jackson.databind.node.ArrayNode) warnings)
                            .add("source_modified_after_index");
                }
            }
        }
    }

    private SourceCheck computeSourceStatus(ObjectNode summary) {
        SourceCheck check = new SourceCheck();
        JsonNode src = summary.path("source");
        String pathStr = src.path("original_report_path").asText("");
        long recordedSize = src.path("source_size_bytes").asLong(-1L);
        long recordedMtime = src.path("source_modified_time_epoch_millis").asLong(-1L);
        if (pathStr.isEmpty()) {
            check.status = "unverified";
            return check;
        }
        Path p = Paths.get(pathStr);
        if (!Files.exists(p)) {
            check.status = "missing";
            return check;
        }
        try {
            long curSize = Files.size(p);
            long curMtime = Files.getLastModifiedTime(p).toMillis();
            if (recordedSize == curSize && recordedMtime == curMtime) {
                check.status = "present";
            } else {
                check.status = "stale";
            }
        } catch (IOException e) {
            check.status = "unverified";
        }
        return check;
    }

    private void writeSummary(String reportId, ObjectNode summary) throws IOException {
        Path target = summaryFileFor(reportId);
        writeSummaryFile(target, summary);
    }

    private void writeSummaryFile(Path target, ObjectNode summary) throws IOException {
        Files.createDirectories(target.getParent());
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                    + Thread.currentThread().getId() + "-" + attempt);
            try {
                mapper.writeValue(tmp.toFile(), summary);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                sleepBeforeRetry(attempt);
            }
        }
        throw last == null ? new IOException("failed to write summary " + target) : last;
    }

    private void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(40L * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while writing summary", ie);
        }
    }

    private void writeFailedPlaceholder(Path sourceFile, Throwable cause) {
        try {
            String reportId = deriveIdFromFileName(sourceFile.getFileName().toString());
            if (reportId == null || !isSafeReportId(reportId)) return;
            ObjectNode summary = mapper.createObjectNode();
            summary.put("schema_version", ReportSummaryExtractor.SCHEMA_VERSION);
            summary.put("report_id", reportId);
            summary.put("indexed_at_epoch_millis", System.currentTimeMillis());

            ObjectNode source = mapper.createObjectNode();
            source.put("original_report_path", sourceFile.toString());
            source.put("source_file_name", sourceFile.getFileName().toString());
            try {
                source.put("source_size_bytes", Files.size(sourceFile));
                source.put("source_modified_time_epoch_millis",
                        Files.getLastModifiedTime(sourceFile).toMillis());
            } catch (IOException ignored) {
                source.put("source_size_bytes", -1L);
                source.put("source_modified_time_epoch_millis", -1L);
            }
            source.put("source_exists_when_indexed", true);
            source.put("source_status", "unverified");
            source.put("source_status_checked_at_epoch_millis", System.currentTimeMillis());
            summary.set("source", source);

            ObjectNode precheck = mapper.createObjectNode();
            precheck.put("has_required_fields", false);
            precheck.putArray("missing_fields");
            precheck.put("basic_invariants_valid", false);
            precheck.putArray("invariant_violations");
            precheck.put("timeline_monotonic", false);
            precheck.put("parse_status", "failed");
            precheck.put("parse_error_code", "rebuild_failed");
            precheck.putArray("warnings").add(cause.getClass().getSimpleName() + ":" + cause.getMessage());
            summary.set("precheck", precheck);

            writeSummary(reportId, summary);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to write failure placeholder for {}: {}", sourceFile.getFileName(), e.getMessage());
        }
    }

    private Path summaryFileFor(String reportId) {
        return getSummaryDir().resolve(reportId + SUMMARY_SUFFIX);
    }

    static boolean isCandidateReportFile(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(REPORT_PREFIX)
                && name.endsWith(REPORT_SUFFIX)
                && !LATEST_REPORT_FILE.equals(name)
                && !name.startsWith(HISTORY_FILE_PREFIX);
    }

    static String deriveIdFromFileName(String fileName) {
        if (fileName == null) return null;
        if (!fileName.startsWith(REPORT_PREFIX) || !fileName.endsWith(REPORT_SUFFIX)) return null;
        String mid = fileName.substring(REPORT_PREFIX.length(),
                fileName.length() - REPORT_SUFFIX.length());
        // 文件名形如 simulation-report-{yyyyMMdd-HHmmss}-{reportId}.json
        // 取最后一段作为 reportId(允许 reportId 内含 . _ -)
        int firstDash = mid.indexOf('-');
        int secondDash = mid.indexOf('-', firstDash + 1);
        if (secondDash < 0) return mid; // 无 timestamp,整段就是 id
        return mid.substring(secondDash + 1);
    }

    static boolean isSafeReportId(String reportId) {
        return reportId != null && reportId.matches("[A-Za-z0-9._-]+");
    }

    public static final class RebuildOutcome {
        public int indexed;
        public int skipped;
        public final List<String> errors = new ArrayList<>();
    }

    public static final class VerifyOutcome {
        public int present;
        public int stale;
        public int missing;
        public int deleted;
        public int unverified;
        public final List<String> corrupted = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    public static final class RepairOutcome {
        public final List<String> quarantined = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    private static final class SourceCheck {
        String status = "unverified";
    }
}
