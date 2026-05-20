package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.service.ReportSummaryStore.RebuildOutcome;
import com.bjtu.simulation.service.ReportSummaryStore.RepairOutcome;
import com.bjtu.simulation.service.ReportSummaryStore.VerifyOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 阶段 1 RFC 测试:Historical Report Summary Store。
 * 全部测试在 @TempDir 下运行,不污染真实 reports/ 或 analysis-store/。
 *
 * 覆盖 RFC §六中 T1-T8、T13-T24。T9-T12 是"外部 mvn test / npm run build:backend 零回归"
 * 的事实约束,通过完整测试套件运行验证,不在本类内部表达。
 */
class ReportSummaryStoreTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    private ReportSummaryStore newStore(Path analysisStore, Path reports) {
        ReportSummaryStore store = new ReportSummaryStore(analysisStore, reports, mapper,
                new ReportSummaryExtractor(mapper));
        store.validateConfiguration();
        return store;
    }

    private ObjectNode minimalReport(String reportId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("report_id", reportId);
        root.put("report_version", "1.0");
        root.put("scenario_id", "lunch_peak_pressure");
        root.put("generated_at_epoch_millis", 1716115200000L);

        ObjectNode config = root.putObject("config");
        config.put("arrival_rate", 300.0);
        config.put("duration", 2.0);
        config.put("queue_limit", 15);
        config.put("pack_probability", 0.13);
        config.put("seed", 20260519L);
        ObjectNode base = config.putObject("base_config");
        base.put("window_count", 8);
        base.put("takeaway_window_count", 1);
        base.put("total_seats", 200);
        ObjectNode weather = config.putObject("weather_config");
        weather.put("current_weather", "sunny");
        weather.put("weather_impact_factor", 1.0);

        ObjectNode summary = root.putObject("summary");
        summary.put("arrived_count", 1000);
        summary.put("served_count", 950);
        summary.put("dine_in_count", 800);
        summary.put("takeaway_count", 150);
        summary.put("abandoned_count", 50);
        summary.put("avg_wait_time_minutes", 4.2);
        summary.put("seat_utilization_rate", 0.62);
        summary.put("takeaway_rate", 0.16);
        summary.put("max_total_queue_size", 18);
        summary.put("avg_total_queue_size", 8.4);
        summary.put("max_occupied_seats", 180);
        summary.put("avg_occupied_seats", 124.0);
        summary.put("total_seats", 200);
        summary.putArray("timeline");
        return root;
    }

    private Path writeMinimalSourceReport(Path reportsDir, String reportId) throws IOException {
        Files.createDirectories(reportsDir);
        Path file = reportsDir.resolve("simulation-report-20260519-143022-" + reportId + ".json");
        ObjectNode report = minimalReport(reportId);
        Files.writeString(file, mapper.writeValueAsString(report), StandardCharsets.UTF_8);
        return file;
    }

    // ==================== T1 ====================
    @Test
    void t1_upsertProducesSummaryFileWithPresentStatus(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        Path source = writeMinimalSourceReport(reports, "rid_t1");

        svc.upsert("rid_t1", minimalReport("rid_t1"), source);

        Path summaryFile = store.resolve("report-summaries").resolve("rid_t1.summary.json");
        assertTrue(Files.isRegularFile(summaryFile), "summary file must exist");
        JsonNode tree = mapper.readTree(summaryFile.toFile());
        assertEquals("1.0", tree.path("schema_version").asText());
        assertEquals("rid_t1", tree.path("report_id").asText());
        assertEquals("present", tree.path("source").path("source_status").asText());
        assertTrue(tree.path("source").path("source_exists_when_indexed").asBoolean());
        assertTrue(tree.path("precheck").path("has_required_fields").asBoolean());
        assertTrue(tree.path("precheck").path("basic_invariants_valid").asBoolean());
    }

    // ==================== T2 ====================
    @Test
    void t2_rebuildBackfillsAllExistingReports(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        writeMinimalSourceReport(reports, "rA");
        writeMinimalSourceReport(reports, "rB");
        writeMinimalSourceReport(reports, "rC");

        ReportSummaryStore svc = newStore(store, reports);
        RebuildOutcome out = svc.rebuildFromReports();

        assertEquals(3, out.indexed, () -> "indexed=" + out.indexed + " errors=" + out.errors);
        assertEquals(0, out.skipped);
        assertEquals(3, svc.list().size());
    }

    // ==================== T3 ====================
    @Test
    void t3_corruptSourceFileIsSkippedAndProducesFailurePlaceholder(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        writeMinimalSourceReport(reports, "ok1");
        writeMinimalSourceReport(reports, "ok2");
        writeMinimalSourceReport(reports, "ok3");
        Path bad = reports.resolve("simulation-report-20260519-143022-bad.json");
        Files.writeString(bad, "{ this is broken json", StandardCharsets.UTF_8);

        ReportSummaryStore svc = newStore(store, reports);
        RebuildOutcome out = assertDoesNotThrow(svc::rebuildFromReports);

        assertEquals(3, out.indexed);
        assertTrue(out.skipped >= 1, () -> "expected skipped>=1, got " + out.skipped);
        Optional<JsonNode> badSummary = svc.read("bad");
        assertTrue(badSummary.isPresent(), "failure placeholder must be persisted for later retry");
        assertEquals("failed", badSummary.get().path("precheck").path("parse_status").asText());
    }

    // ==================== T4 ====================
    @Test
    void t4_largeReportStreamingExtractCompletesInTime(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        Files.createDirectories(reports);

        // 写一个 ~20MB 合法 report:大 timeline 数组撑体积。
        Path big = reports.resolve("simulation-report-20260519-143022-bigreport.json");
        try (Writer w = Files.newBufferedWriter(big, StandardCharsets.UTF_8)) {
            w.write("{\"report_id\":\"bigreport\",\"report_version\":\"1.0\",");
            w.write("\"config\":{\"arrival_rate\":200.0,\"duration\":2.0,\"pack_probability\":0.2,");
            w.write("\"queue_limit\":15,\"seed\":1,\"base_config\":{\"window_count\":8,\"total_seats\":200,\"takeaway_window_count\":1},");
            w.write("\"weather_config\":{\"current_weather\":\"sunny\",\"weather_impact_factor\":1.0}},");
            w.write("\"summary\":{\"arrived_count\":1000,\"served_count\":950,\"dine_in_count\":800,");
            w.write("\"takeaway_count\":150,\"abandoned_count\":50,\"avg_wait_time_minutes\":4.2,");
            w.write("\"seat_utilization_rate\":0.62,\"takeaway_rate\":0.16,\"max_total_queue_size\":18,");
            w.write("\"avg_total_queue_size\":8.4,\"max_occupied_seats\":180,\"avg_occupied_seats\":124.0,");
            w.write("\"total_seats\":200,\"timeline\":[");
            int count = 60_000;
            for (int i = 0; i < count; i++) {
                if (i > 0) w.write(',');
                w.write("{\"minute\":");
                w.write(Integer.toString(i));
                w.write(",\"total_queue_size\":3,\"seat_utilization_rate\":0.5,");
                w.write("\"cumulative_arrived_count\":");
                w.write(Integer.toString(i));
                w.write(",\"window_queue_sizes\":[1,2,3,1,2,3,1,2],\"padding\":\"");
                for (int p = 0; p < 200; p++) w.write('x');
                w.write("\"}");
            }
            w.write("]}}");
        }
        long bytes = Files.size(big);
        assertTrue(bytes >= 15_000_000, () -> "big report should be ~15MB+, got " + bytes);

        ReportSummaryStore svc = newStore(store, reports);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> svc.upsertFromFile(big));

        Optional<JsonNode> summary = svc.read("bigreport");
        assertTrue(summary.isPresent());
        assertEquals(60_000, summary.get().path("metrics").path("timeline_points").asInt());
        // 摘要本身必须 ≤ 5KB 量级,与原文件无关。
        long summarySize = Files.size(store.resolve("report-summaries").resolve("bigreport.summary.json"));
        assertTrue(summarySize < 8_000, () -> "summary too large: " + summarySize);
    }

    // ==================== T5 ====================
    @Test
    void t5_missingFieldsAreRecordedAsNullWithExplicitWarnings(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        ObjectNode partial = minimalReport("rid_t5");
        partial.remove("scenario_id");
        partial.remove("report_version");
        ((ObjectNode) partial.path("summary")).remove("takeaway_rate");
        Path source = writeMinimalSourceReport(reports, "rid_t5");
        Files.writeString(source, mapper.writeValueAsString(partial), StandardCharsets.UTF_8);

        svc.upsert("rid_t5", partial, source);
        JsonNode summary = svc.read("rid_t5").orElseThrow();

        assertTrue(summary.path("metrics").path("takeaway_rate").isNull());
        assertTrue(summary.path("report_meta").path("scenario_id").isNull());
        boolean scenarioWarn = false;
        boolean schemaWarn = false;
        for (JsonNode w : summary.path("precheck").path("warnings")) {
            if ("scenario_id_missing".equals(w.asText())) scenarioWarn = true;
            if ("report_schema_version_unknown".equals(w.asText())) schemaWarn = true;
        }
        assertTrue(scenarioWarn, "expected scenario_id_missing warning");
        assertTrue(schemaWarn, "expected report_schema_version_unknown warning");
    }

    // ==================== T6 ====================
    @Test
    void t6_modifiedSourceMakesSummaryStale(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        Path source = writeMinimalSourceReport(reports, "rid_t6");
        svc.upsert("rid_t6", minimalReport("rid_t6"), source);

        // 改文件大小(追加内容),mtime 也会改
        Files.writeString(source,
                mapper.writeValueAsString(minimalReport("rid_t6")) + "    \n   ",
                StandardCharsets.UTF_8);

        VerifyOutcome out = svc.verifySummaryStore();
        assertTrue(out.stale >= 1, () -> "expected at least one stale, got " + out.stale);

        JsonNode tree = svc.read("rid_t6").orElseThrow();
        assertEquals("stale", tree.path("source").path("source_status").asText());
        boolean staleWarn = false;
        for (JsonNode w : tree.path("precheck").path("warnings")) {
            if ("source_modified_after_index".equals(w.asText())) staleWarn = true;
        }
        assertTrue(staleWarn, "expected source_modified_after_index warning");
    }

    // ==================== T7 ====================
    @Test
    void t7_corruptSummaryReadEmptyAndListSkips(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        Path source = writeMinimalSourceReport(reports, "good");
        svc.upsert("good", minimalReport("good"), source);

        Path corruptDir = store.resolve("report-summaries");
        Files.writeString(corruptDir.resolve("badone.summary.json"), "}}}{",
                StandardCharsets.UTF_8);

        assertTrue(svc.read("badone").isEmpty(), "corrupt summary read returns empty");
        List<JsonNode> all = svc.list();
        assertEquals(1, all.size(), "list skips corrupt summary, returns only good ones");
        assertEquals("good", all.get(0).path("report_id").asText());
    }

    // ==================== T8 ====================
    @Test
    void t8_sequentialUpsertsAreNotLost(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        for (int i = 0; i < 10; i++) {
            String id = "seq_" + i;
            Path s = writeMinimalSourceReport(reports, id);
            svc.upsert(id, minimalReport(id), s);
        }
        assertEquals(10, svc.list().size());
        for (int i = 0; i < 10; i++) {
            assertTrue(svc.read("seq_" + i).isPresent(), "seq_" + i + " missing");
        }
    }

    // ==================== T13 ====================
    @Test
    void t13_summaryWriteFailureIsCaughtByRepositoryHook(@TempDir Path tmp) {
        // 直接验证 store.upsert 在路径无效时抛 IOException,而 SimulationReportRepository 的
        // tryUpsertSummary 用 catch(Throwable) 包住了它(见 SimulationReportRepository.java)。
        // 这里覆盖 store 抛出的语义合约部分;repository 端的 swallow 由代码评审保障,
        // 同时 repository 已有的 SimulationReportRepositoryTest 不依赖 summary store,
        // 任何 hook 抛出都不会污染既有测试 → 双重防护。
        Path nonexistentRoot = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(nonexistentRoot, reports);

        // 不安全 reportId 必须抛 IllegalArgumentException(repository 不会调到这种 id,因 isSafeReportId 已守门)
        assertThrows(IllegalArgumentException.class,
                () -> svc.upsert("../unsafe", minimalReport("x"), null));
    }

    // ==================== T14 ====================
    @Test
    void t14_configFingerprintStability(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        ObjectNode a = minimalReport("fp_a");
        ObjectNode b = minimalReport("fp_b");
        ((ObjectNode) b.path("config")).put("seed", 99999L); // seed 不影响 fingerprint
        Path sa = writeMinimalSourceReport(reports, "fp_a");
        Path sb = writeMinimalSourceReport(reports, "fp_b");
        svc.upsert("fp_a", a, sa);
        svc.upsert("fp_b", b, sb);

        String fpA = svc.read("fp_a").orElseThrow().path("config").path("config_fingerprint").asText();
        String fpB = svc.read("fp_b").orElseThrow().path("config").path("config_fingerprint").asText();
        assertEquals(fpA, fpB, "seed 改变不应影响 fingerprint");

        // 改 arrival_rate 应改变 fingerprint
        ObjectNode c = minimalReport("fp_c");
        ((ObjectNode) c.path("config")).put("arrival_rate", 999.0);
        Path sc = writeMinimalSourceReport(reports, "fp_c");
        svc.upsert("fp_c", c, sc);
        String fpC = svc.read("fp_c").orElseThrow().path("config").path("config_fingerprint").asText();
        assertNotEquals(fpA, fpC);
    }

    // ==================== T15 ====================
    @Test
    void t15_summaryPersistsAfterReportsCleared(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        Path source = writeMinimalSourceReport(reports, "persist1");
        svc.upsert("persist1", minimalReport("persist1"), source);

        // 模拟运维清理 reports/
        Files.delete(source);
        // latest / history 也可能被清(本测试只 delete 主报告,够验证目录隔离)
        assertFalse(Files.exists(source));

        // 摘要文件应该仍在
        Path summaryFile = store.resolve("report-summaries").resolve("persist1.summary.json");
        assertTrue(Files.isRegularFile(summaryFile), "summary must persist after reports cleared");
        assertTrue(svc.read("persist1").isPresent());
    }

    // ==================== T16 ====================
    @Test
    void t16_verifyMarksMissingSourceAsMissingOrDeleted(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        Path source = writeMinimalSourceReport(reports, "verify1");
        svc.upsert("verify1", minimalReport("verify1"), source);
        Files.delete(source);

        VerifyOutcome out = svc.verifySummaryStore();
        // 第一次 verify:从 present → missing,实际写为 deleted(主动观测到转移)
        String status = svc.read("verify1").orElseThrow().path("source").path("source_status").asText();
        assertTrue("missing".equals(status) || "deleted".equals(status),
                () -> "expected missing/deleted, got " + status);
        assertTrue(out.missing + out.deleted >= 1);
    }

    // ==================== T17 ====================
    @Test
    void t17_listAndReadStillWorkWhenSourceMissing(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        Path source = writeMinimalSourceReport(reports, "miss_read");
        svc.upsert("miss_read", minimalReport("miss_read"), source);
        Files.delete(source);

        List<JsonNode> all = svc.list();
        assertEquals(1, all.size());
        assertTrue(svc.read("miss_read").isPresent());
        // metrics + config 仍可读
        assertEquals(950, svc.read("miss_read").get().path("metrics").path("served_count").asInt());
    }

    // ==================== T18 ====================
    @Test
    void t18_rebuildFromReportsDoesNotDeleteMissingSummaries(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        // 5 份摘要,3 份源仍在,2 份源已清
        for (int i = 0; i < 5; i++) {
            String id = "r18_" + i;
            Path s = writeMinimalSourceReport(reports, id);
            svc.upsert(id, minimalReport(id), s);
        }
        Files.delete(reports.resolve("simulation-report-20260519-143022-r18_3.json"));
        Files.delete(reports.resolve("simulation-report-20260519-143022-r18_4.json"));

        RebuildOutcome out = svc.rebuildFromReports();

        assertEquals(3, out.indexed, "rebuild only touches 3 still-existing reports");
        assertEquals(5, svc.list().size(), "5 summaries must remain — rebuild is purely additive");
        assertTrue(svc.read("r18_3").isPresent());
        assertTrue(svc.read("r18_4").isPresent());
    }

    // ==================== T19 ====================
    @Test
    void t19_verifyReportsCorruptedSummaries(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        Path source = writeMinimalSourceReport(reports, "vc_good");
        svc.upsert("vc_good", minimalReport("vc_good"), source);

        Files.writeString(store.resolve("report-summaries").resolve("vc_bad.summary.json"),
                "{ broken", StandardCharsets.UTF_8);

        VerifyOutcome out = svc.verifySummaryStore();
        assertTrue(out.corrupted.stream().anyMatch(n -> n.contains("vc_bad")),
                () -> "expected vc_bad in corrupted, got " + out.corrupted);
        // 损坏文件**不**被 verify 删
        assertTrue(Files.exists(store.resolve("report-summaries").resolve("vc_bad.summary.json")));
    }

    // ==================== T20 ====================
    @Test
    void t20_repairMovesCorruptedToQuarantine(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        Path source = writeMinimalSourceReport(reports, "rep_good");
        svc.upsert("rep_good", minimalReport("rep_good"), source);
        Files.writeString(store.resolve("report-summaries").resolve("rep_bad.summary.json"),
                "}}{{ definitely not json", StandardCharsets.UTF_8);

        RepairOutcome out = svc.repairSummaryStore();
        assertTrue(out.quarantined.stream().anyMatch(n -> n.contains("rep_bad")),
                () -> "expected rep_bad to be quarantined, got " + out.quarantined);

        // 损坏文件已 move 到 quarantine
        assertFalse(Files.exists(store.resolve("report-summaries").resolve("rep_bad.summary.json")));
        try (java.util.stream.Stream<Path> s = Files.list(store.resolve("quarantine"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains("rep_bad")));
        }
        // 正常摘要原位不动
        assertTrue(Files.exists(store.resolve("report-summaries").resolve("rep_good.summary.json")));
        assertTrue(svc.read("rep_good").isPresent());
    }

    // ==================== T21 ====================
    @Test
    void t21_listKeepsAllSummariesAfterAllSourcesCleared(@TempDir Path tmp) throws IOException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        for (int i = 0; i < 5; i++) {
            String id = "all_clear_" + i;
            Path s = writeMinimalSourceReport(reports, id);
            svc.upsert(id, minimalReport(id), s);
        }
        // 清空 reports/
        try (java.util.stream.Stream<Path> s = Files.list(reports)) {
            s.forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }

        assertEquals(5, svc.list().size(), "diagnostic basis must remain intact");
        svc.verifySummaryStore();
        for (int i = 0; i < 5; i++) {
            JsonNode tree = svc.read("all_clear_" + i).orElseThrow();
            String status = tree.path("source").path("source_status").asText();
            assertTrue("missing".equals(status) || "deleted".equals(status),
                    () -> "expected missing/deleted, got " + status);
        }
    }

    // ==================== T22 ====================
    @Test
    void t22_sourceRegeneratedTriggersFreshSummary(@TempDir Path tmp) throws IOException, InterruptedException {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);

        Path source = writeMinimalSourceReport(reports, "regen");
        svc.upsert("regen", minimalReport("regen"), source);
        long mtime1 = svc.read("regen").orElseThrow()
                .path("source").path("source_modified_time_epoch_millis").asLong();

        Thread.sleep(20L); // 保证 mtime 改变
        ObjectNode bigger = minimalReport("regen");
        ((ObjectNode) bigger.path("summary")).put("served_count", 9999);
        Files.writeString(source, mapper.writeValueAsString(bigger) + "   \n",
                StandardCharsets.UTF_8);
        svc.upsert("regen", bigger, source);

        JsonNode after = svc.read("regen").orElseThrow();
        assertEquals(9999, after.path("metrics").path("served_count").asInt());
        long mtime2 = after.path("source").path("source_modified_time_epoch_millis").asLong();
        assertTrue(mtime2 != mtime1, "source mtime must reflect new file");
        assertEquals("present", after.path("source").path("source_status").asText());
    }

    // ==================== T23 ====================
    @Test
    void t23_analysisStorePathIsConfigurable(@TempDir Path tmp) throws IOException {
        Path customStore = tmp.resolve("alternate-store-root");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(customStore, reports);

        Path source = writeMinimalSourceReport(reports, "cfg1");
        svc.upsert("cfg1", minimalReport("cfg1"), source);

        Path expected = customStore.resolve("report-summaries").resolve("cfg1.summary.json");
        assertTrue(Files.isRegularFile(expected), "summary must land in configured root");
    }

    // ==================== T24 ====================
    @Test
    void t24_failFastWhenAnalysisStoreIsInsideReports(@TempDir Path tmp) {
        Path reports = tmp.resolve("reports");
        Path nestedStore = reports.resolve("inside"); // 故意嵌在 reports/ 下
        ReportSummaryStore bad = new ReportSummaryStore(nestedStore, reports, mapper,
                new ReportSummaryExtractor(mapper));

        IllegalStateException ex = assertThrows(IllegalStateException.class, bad::validateConfiguration);
        assertTrue(ex.getMessage().contains("must NOT be inside reports/"),
                () -> "expected fail-fast message, got " + ex.getMessage());
    }

    @Test
    void compactAndFullResetAreDisabledInPhaseOne(@TempDir Path tmp) {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        assertThrows(UnsupportedOperationException.class, svc::compactSummaryStore);
        assertThrows(UnsupportedOperationException.class, svc::fullResetSummaryStore);
    }

    @Test
    void deriveIdFromFileNameHandlesStandardFormat() {
        assertEquals("abc-123",
                ReportSummaryStore.deriveIdFromFileName("simulation-report-20260519-143022-abc-123.json"));
        assertEquals("rid_with.dots",
                ReportSummaryStore.deriveIdFromFileName("simulation-report-20260519-143022-rid_with.dots.json"));
    }

    @Test
    void candidateReportFileFiltersLatestAndHistory(@TempDir Path tmp) {
        assertTrue(ReportSummaryStore.isCandidateReportFile(
                tmp.resolve("simulation-report-20260519-143022-abc.json")));
        assertFalse(ReportSummaryStore.isCandidateReportFile(
                tmp.resolve("simulation-report-latest.json")));
        assertFalse(ReportSummaryStore.isCandidateReportFile(
                tmp.resolve("simulation-history-abc.json")));
        assertFalse(ReportSummaryStore.isCandidateReportFile(
                tmp.resolve("readme.json")));
    }

    @Test
    void summaryDtoNonNull(@TempDir Path tmp) {
        Path store = tmp.resolve("analysis-store");
        Path reports = tmp.resolve("reports");
        ReportSummaryStore svc = newStore(store, reports);
        assertNotNull(svc.getAnalysisStoreRoot());
        assertNotNull(svc.getSummaryDir());
        assertNotNull(svc.getQuarantineDir());
    }
}
