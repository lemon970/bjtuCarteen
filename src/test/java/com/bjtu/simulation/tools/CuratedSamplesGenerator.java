package com.bjtu.simulation.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.service.ReportSummaryExtractor;
import com.bjtu.simulation.service.SimulationRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 一次性受控历史样本生成器(50 条)。
 *
 * 默认 mvn test 不跑;仅在显式开启 -Dsamples.run=true 时执行:
 *   mvn -DskipFrontend=true -Dtest=CuratedSamplesGenerator -Dsamples.run=true test
 *
 * 严格不调 SimulationReportRepository.write,严格不实例化 ReportSummaryStore;
 * 输出全部落到 samples/curated-history/{reports,summaries,manifest.json},
 * setUp/tearDown 双校验真实 reports/ 与 analysis-store/ 文件数不变。
 */
@Tag("samples")
@EnabledIfSystemProperty(named = "samples.run", matches = "true")
class CuratedSamplesGenerator {

    private static final Path SAMPLES_ROOT = Path.of("samples", "curated-history");
    private static final Path REPORTS_DIR = SAMPLES_ROOT.resolve("reports");
    private static final Path SUMMARIES_DIR = SAMPLES_ROOT.resolve("summaries");
    private static final Path MANIFEST_PATH = SAMPLES_ROOT.resolve("manifest.json");

    private static final Path REAL_REPORTS = Path.of("reports");
    private static final Path REAL_ANALYSIS_STORE = Path.of("analysis-store", "report-summaries");

    private static final ObjectMapper MAPPER = AppBeansConfig.createReportObjectMapper();

    private long realReportsBefore;
    private long realStoreBefore;

    @BeforeEach
    void setUp() throws IOException {
        realReportsBefore = countFilesSafe(REAL_REPORTS);
        realStoreBefore = countFilesSafe(REAL_ANALYSIS_STORE);
        Files.createDirectories(REPORTS_DIR);
        Files.createDirectories(SUMMARIES_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        long realReportsAfter = countFilesSafe(REAL_REPORTS);
        long realStoreAfter = countFilesSafe(REAL_ANALYSIS_STORE);
        assertEquals(realReportsBefore, realReportsAfter,
                "real reports/ contaminated: before=" + realReportsBefore + ", after=" + realReportsAfter);
        assertEquals(realStoreBefore, realStoreAfter,
                "real analysis-store/report-summaries/ contaminated: before=" + realStoreBefore + ", after=" + realStoreAfter);
    }

    @Test
    @Disabled("Round 1 generator: writes large report files (~1.8 GB). "
            + "Manifest already produced; rerun only if you must regenerate report files. "
            + "Round 2 (generate30DiverseSamples) is in-memory only and is the default.")
    void generate50CuratedSamples() throws IOException {
        SimulationRunService runService = new SimulationRunService();
        ReportSummaryExtractor extractor = new ReportSummaryExtractor();

        List<SamplePlan> plans = buildPlans();
        assertEquals(50, plans.size(), "plan list must contain exactly 50 entries");

        List<ObjectNode> manifestSamples = new ArrayList<>();
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        Set<String> sampleIds = new HashSet<>();
        Set<String> aFingerprints = new HashSet<>();
        List<String> failed = new ArrayList<>();

        long startMillis = System.currentTimeMillis();
        for (SamplePlan plan : plans) {
            try {
                SimConfig config = baseConfig();
                plan.mutator.accept(config);
                config.setSeed(plan.seed);

                SimulationReport report = runService.run(config, plan.sampleId);
                ObjectNode reportNode = (ObjectNode) MAPPER.valueToTree(report);
                reportNode.put("scenario_id", plan.scenarioId);

                Path reportPath = REPORTS_DIR.resolve(plan.sampleId + ".json");
                MAPPER.writeValue(reportPath.toFile(), reportNode);

                long size = Files.size(reportPath);
                long mtime = Files.getLastModifiedTime(reportPath).toMillis();
                ObjectNode summary = extractor.extractFromJsonNode(
                        reportNode, reportPath, size, mtime, System.currentTimeMillis());

                Path summaryPath = SUMMARIES_DIR.resolve(plan.sampleId + ".summary.json");
                MAPPER.writeValue(summaryPath.toFile(), summary);

                ObjectNode entry = MAPPER.createObjectNode();
                entry.put("sample_id", plan.sampleId);
                entry.put("report_id", plan.sampleId);
                entry.put("scenario_group", plan.group);
                entry.put("scenario_id", plan.scenarioId);
                entry.put("seed", plan.seed);
                entry.put("synthetic", true);
                entry.put("curated", true);

                ObjectNode expected = entry.putObject("expected_baseline_behavior");
                expected.put("target_confidence", plan.expectedConfidence);
                expected.put("target_strategy", plan.expectedStrategy);

                ObjectNode files = entry.putObject("files");
                files.put("report", "reports/" + plan.sampleId + ".json");
                files.put("summary", "summaries/" + plan.sampleId + ".summary.json");

                entry.put("notes", plan.notes);
                JsonNode summaryConfig = summary.path("config");
                if (summaryConfig.isObject()) entry.set("config", summaryConfig.deepCopy());

                manifestSamples.add(entry);
                sampleIds.add(plan.sampleId);
                groupCounts.merge(plan.group, 1, Integer::sum);

                if ("high_confidence_baseline".equals(plan.group)) {
                    aFingerprints.add(summaryConfig.path("config_fingerprint").asText(""));
                }
            } catch (Throwable t) {
                failed.add(plan.sampleId + ":" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }
        }
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("schema_version", "1.0");
        manifest.put("synthetic", true);
        manifest.put("curated", true);
        manifest.put("warning",
                "These samples are synthetic and DO NOT represent real cafeteria business distribution. "
                        + "For diagnostic / WNN tuning / UI verification only.");
        manifest.put("generated_at",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        manifest.put("generated_at_epoch_millis", System.currentTimeMillis());
        manifest.put("generator", "CuratedSamplesGenerator");
        manifest.put("generator_version", "1.0");
        manifest.put("elapsed_millis", elapsedMillis);
        manifest.put("sample_count", manifestSamples.size());

        ObjectNode groups = manifest.putObject("groups");
        groupCounts.forEach(groups::put);

        manifest.put("intended_use_1", "baseline_confidence calibration (high/medium/low/very_low/none)");
        manifest.put("intended_use_2", "WNN top-k / ESS tuning");
        manifest.put("intended_use_3", "frontend HistoricalQualityCard rendering verification");

        ArrayNode samplesArray = manifest.putArray("samples");
        manifestSamples.forEach(samplesArray::add);

        ArrayNode failedArray = manifest.putArray("failed_samples");
        failed.forEach(failedArray::add);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(MANIFEST_PATH.toFile(), manifest);

        // ---- 内联验证 V1–V9 ----
        long reportFiles = countJsonFiles(REPORTS_DIR);
        long summaryFiles = countSummaryFiles(SUMMARIES_DIR);

        assertEquals(50L, reportFiles, "V1: reports/ must contain 50 curated reports");
        assertEquals(50L, summaryFiles, "V2: summaries/ must contain 50 summaries");
        assertEquals(50, manifest.get("sample_count").asInt(), "V3: manifest sample_count must be 50");

        try (Stream<Path> stream = Files.list(SUMMARIES_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".summary.json"))
                    .forEach(p -> {
                        try {
                            JsonNode tree = MAPPER.readTree(p.toFile());
                            assertNotNull(tree, "V4: summary parsed null: " + p);
                        } catch (IOException e) {
                            fail("V4: cannot parse summary " + p + ": " + e.getMessage());
                        }
                    });
        }

        assertEquals(50, sampleIds.size(), "V5: sample_id must be unique across all 50 plans");
        assertEquals(1, aFingerprints.size(),
                "V6: A series fingerprints must be identical, got: " + aFingerprints);

        assertEquals(Integer.valueOf(10), groupCounts.get("high_confidence_baseline"),
                "V9-A: high_confidence_baseline count");
        assertEquals(Integer.valueOf(10), groupCounts.get("medium_confidence_baseline"),
                "V9-B: medium_confidence_baseline count");
        assertEquals(Integer.valueOf(14), groupCounts.get("low_confidence_baseline"),
                "V9-C: low_confidence_baseline count");
        assertEquals(Integer.valueOf(7), groupCounts.get("very_low_confidence_baseline"),
                "V9-D: very_low_confidence_baseline count");
        assertEquals(Integer.valueOf(2), groupCounts.get("no_comparable_history_target"),
                "V9-E: no_comparable_history_target count");
        assertEquals(Integer.valueOf(7), groupCounts.get("anomaly_cases"),
                "V9-F: anomaly_cases count");

        // ---- 9 项汇报输出 ----
        long postReports = countFilesSafe(REAL_REPORTS);
        long postStore = countFilesSafe(REAL_ANALYSIS_STORE);
        long reportsTotalBytes = dirSizeBytes(REPORTS_DIR);
        long summariesTotalBytes = dirSizeBytes(SUMMARIES_DIR);

        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("===== Curated Samples Generation Report =====\n");
        sb.append("1. actual_count                   : ").append(manifestSamples.size()).append('\n');
        sb.append("2. group_counts                   : ").append(groupCounts).append('\n');
        sb.append("3. output_dir                     : ").append(SAMPLES_ROOT.toAbsolutePath()).append('\n');
        sb.append("4. reports_total_bytes            : ").append(reportsTotalBytes)
                .append(" (").append(humanBytes(reportsTotalBytes)).append(")\n");
        sb.append("5. summaries_total_bytes          : ").append(summariesTotalBytes)
                .append(" (").append(humanBytes(summariesTotalBytes)).append(")\n");
        sb.append("6. manifest_path                  : ").append(MANIFEST_PATH.toAbsolutePath()).append('\n');
        sb.append("7. real_reports_polluted          : ")
                .append(postReports != realReportsBefore)
                .append(" (before=").append(realReportsBefore)
                .append(", after=").append(postReports).append(")\n");
        sb.append("8. real_analysis_store_polluted   : ")
                .append(postStore != realStoreBefore)
                .append(" (before=").append(realStoreBefore)
                .append(", after=").append(postStore).append(")\n");
        sb.append("9. failed_samples                 : ")
                .append(failed.isEmpty() ? "[none]" : failed)
                .append('\n');
        sb.append("==============================================\n");
        sb.append("elapsed_millis                    : ").append(elapsedMillis).append('\n');
        sb.append("a_series_fingerprint              : ").append(aFingerprints).append('\n');
        sb.append("==============================================");
        System.out.println(sb);
    }

    /**
     * Round 2: append 30 diverse samples to the existing manifest.
     * In-memory only — does not write report files (avoids the 1.8 GB issue
     * caused by full SimulationReport serialisation). Source size for the
     * summary is computed from {@code mapper.writeValueAsBytes(reportNode).length}
     * so the reported byte count remains a faithful "if it had been written"
     * value, but no on-disk report exists.
     */
    @Test
    void generate30DiverseSamples() throws IOException {
        if (!Files.exists(MANIFEST_PATH)) {
            fail("Round 1 manifest must exist at " + MANIFEST_PATH
                    + " before round 2 (generate50CuratedSamples must have run first)");
        }
        ObjectNode existingManifest = (ObjectNode) MAPPER.readTree(MANIFEST_PATH.toFile());
        int existingCount = existingManifest.path("sample_count").asInt(0);
        ArrayNode existingSamples = (ArrayNode) existingManifest.path("samples");
        if (existingSamples == null || existingSamples.isMissingNode()) {
            fail("Round 1 manifest must contain a samples array");
        }

        Set<String> existingIds = new HashSet<>();
        for (JsonNode s : existingSamples) existingIds.add(s.path("sample_id").asText(""));

        SimulationRunService runService = new SimulationRunService();
        ReportSummaryExtractor extractor = new ReportSummaryExtractor();

        List<SamplePlan> plans = buildDiversePlans();
        assertEquals(30, plans.size(), "round 2 plan list must contain exactly 30 entries");

        List<ObjectNode> newSamples = new ArrayList<>();
        Map<String, Integer> newGroupCounts = new LinkedHashMap<>();
        Set<String> newIds = new HashSet<>();
        List<String> failed = new ArrayList<>();

        long startMillis = System.currentTimeMillis();
        for (SamplePlan plan : plans) {
            try {
                if (existingIds.contains(plan.sampleId) || newIds.contains(plan.sampleId)) {
                    failed.add(plan.sampleId + ":duplicate_id");
                    continue;
                }
                SimConfig config = baseConfig();
                plan.mutator.accept(config);
                config.setSeed(plan.seed);

                SimulationReport report = runService.run(config, plan.sampleId);
                ObjectNode reportNode = (ObjectNode) MAPPER.valueToTree(report);
                reportNode.put("scenario_id", plan.scenarioId);

                // 关键:不写 report 文件,但 size 字段需要一个有意义的值。
                // 用序列化字节长度作为 "would-have-been-written" 大小;mtime=now。
                long pseudoSize = MAPPER.writeValueAsBytes(reportNode).length;
                long pseudoMtime = System.currentTimeMillis();
                Path pseudoReportPath = REPORTS_DIR.resolve(plan.sampleId + ".json");

                ObjectNode summary = extractor.extractFromJsonNode(
                        reportNode, pseudoReportPath, pseudoSize, pseudoMtime, System.currentTimeMillis());
                // 没真文件就不能声称 present;改 source_status=unverified
                ObjectNode sourceNode = (ObjectNode) summary.path("source");
                if (sourceNode != null && sourceNode.isObject()) {
                    sourceNode.put("source_status", "unverified");
                    sourceNode.put("source_exists_when_indexed", false);
                }

                Path summaryPath = SUMMARIES_DIR.resolve(plan.sampleId + ".summary.json");
                MAPPER.writeValue(summaryPath.toFile(), summary);

                ObjectNode entry = MAPPER.createObjectNode();
                entry.put("sample_id", plan.sampleId);
                entry.put("report_id", plan.sampleId);
                entry.put("scenario_group", plan.group);
                entry.put("scenario_id", plan.scenarioId);
                entry.put("seed", plan.seed);
                entry.put("synthetic", true);
                entry.put("curated", true);
                entry.put("report_inline_only", true);
                entry.put("round", 2);

                ObjectNode expected = entry.putObject("expected_baseline_behavior");
                expected.put("target_confidence", plan.expectedConfidence);
                expected.put("target_strategy", plan.expectedStrategy);

                ObjectNode files = entry.putObject("files");
                files.putNull("report"); // 显式声明没有 report 文件
                files.put("summary", "summaries/" + plan.sampleId + ".summary.json");

                entry.put("notes", plan.notes);
                JsonNode summaryConfig = summary.path("config");
                if (summaryConfig.isObject()) entry.set("config", summaryConfig.deepCopy());

                newSamples.add(entry);
                newIds.add(plan.sampleId);
                newGroupCounts.merge(plan.group, 1, Integer::sum);
            } catch (Throwable t) {
                failed.add(plan.sampleId + ":" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }
        }
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        // ---- 合并 manifest ----
        for (ObjectNode entry : newSamples) existingSamples.add(entry);
        int totalCount = existingCount + newSamples.size();
        existingManifest.put("sample_count", totalCount);

        ObjectNode groups = (ObjectNode) existingManifest.path("groups");
        if (groups == null || !groups.isObject()) {
            groups = existingManifest.putObject("groups");
        }
        for (Map.Entry<String, Integer> e : newGroupCounts.entrySet()) {
            int prev = groups.path(e.getKey()).asInt(0);
            groups.put(e.getKey(), prev + e.getValue());
        }

        existingManifest.put("round2_generated_at",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        existingManifest.put("round2_elapsed_millis", elapsedMillis);
        existingManifest.put("round2_added", newSamples.size());
        ArrayNode failedArray = existingManifest.putArray("round2_failed_samples");
        failed.forEach(failedArray::add);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(MANIFEST_PATH.toFile(), existingManifest);

        // ---- 内联验证(round 2) ----
        long summaryFiles = countSummaryFiles(SUMMARIES_DIR);
        assertEquals(80L, summaryFiles, "summaries/ must contain 50 + 30 = 80 files");
        assertEquals(80, existingManifest.get("sample_count").asInt(),
                "manifest sample_count must be 80 after round 2");
        assertEquals(30, newSamples.size(), "round 2 must produce exactly 30 samples");

        Set<String> allIds = new HashSet<>();
        for (JsonNode s : existingSamples) allIds.add(s.path("sample_id").asText(""));
        assertEquals(80, allIds.size(), "all 80 sample_id must be unique");

        try (Stream<Path> stream = Files.list(SUMMARIES_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".summary.json"))
                    .forEach(p -> {
                        try {
                            JsonNode tree = MAPPER.readTree(p.toFile());
                            assertNotNull(tree, "summary parsed null: " + p);
                        } catch (IOException e) {
                            fail("cannot parse summary " + p + ": " + e.getMessage());
                        }
                    });
        }

        // ---- 9 项汇报输出(round 2) ----
        long postReports = countFilesSafe(REAL_REPORTS);
        long postStore = countFilesSafe(REAL_ANALYSIS_STORE);
        long reportsTotalBytes = dirSizeBytes(REPORTS_DIR);
        long summariesTotalBytes = dirSizeBytes(SUMMARIES_DIR);

        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("===== Curated Samples Round 2 (Diverse) Report =====\n");
        sb.append("1. round2_actual_count            : ").append(newSamples.size()).append('\n');
        sb.append("2. round2_group_counts            : ").append(newGroupCounts).append('\n');
        sb.append("   manifest_total_count           : ").append(totalCount).append('\n');
        sb.append("   manifest_total_groups          : ").append(groups).append('\n');
        sb.append("3. output_dir                     : ").append(SAMPLES_ROOT.toAbsolutePath()).append('\n');
        sb.append("4. reports_total_bytes            : ").append(reportsTotalBytes)
                .append(" (").append(humanBytes(reportsTotalBytes))
                .append(", round 2 wrote 0 report files by design)\n");
        sb.append("5. summaries_total_bytes          : ").append(summariesTotalBytes)
                .append(" (").append(humanBytes(summariesTotalBytes)).append(")\n");
        sb.append("6. manifest_path                  : ").append(MANIFEST_PATH.toAbsolutePath()).append('\n');
        sb.append("7. real_reports_polluted          : ")
                .append(postReports != realReportsBefore)
                .append(" (before=").append(realReportsBefore)
                .append(", after=").append(postReports).append(")\n");
        sb.append("8. real_analysis_store_polluted   : ")
                .append(postStore != realStoreBefore)
                .append(" (before=").append(realStoreBefore)
                .append(", after=").append(postStore).append(")\n");
        sb.append("9. failed_samples                 : ")
                .append(failed.isEmpty() ? "[none]" : failed)
                .append('\n');
        sb.append("=====================================================\n");
        sb.append("round2_elapsed_millis             : ").append(elapsedMillis).append('\n');
        sb.append("=====================================================");
        System.out.println(sb);
    }

    private List<SamplePlan> buildPlans() {
        List<SamplePlan> plans = new ArrayList<>();

        // ---------- A. high_confidence_baseline (10) ----------
        // 同 scenario_id "lunch_peak_curated" + 完全等于基准 + 仅 seed 不同。
        for (int i = 1; i <= 10; i++) {
            String id = String.format("curated-A%02d", i);
            long seed = 4242L + (i - 1);
            plans.add(new SamplePlan(id, "high_confidence_baseline", "lunch_peak_curated",
                    seed, "high", "scenario_id_exact",
                    c -> {}, // no override
                    "Identical config to all A series, seed-only variation; targets scenario_id_exact match."));
        }

        // ---------- B. medium_confidence_baseline (10) ----------
        // 同 scenario_id "dinner_peak_curated", strict 阈值内动 ar/dur/pack
        // wc=8, ts=250, twc=1, weather=sunny 全部固定不变
        plans.add(planB("curated-B01", 5000L, ar(315),                "ar +5%"));
        plans.add(planB("curated-B02", 5001L, ar(285),                "ar -5%"));
        plans.add(planB("curated-B03", 5002L, ar(320),                "ar +6.7%"));
        plans.add(planB("curated-B04", 5003L, dur(2.1),               "duration +5%"));
        plans.add(planB("curated-B05", 5004L, dur(1.85),              "duration -7.5%"));
        plans.add(planB("curated-B06", 5005L, pack(0.16),             "pack +0.03"));
        plans.add(planB("curated-B07", 5006L, pack(0.10),             "pack -0.03"));
        plans.add(planB("curated-B08", 5007L, chain(ar(290), dur(2.05)),    "ar -3.3% + dur +2.5%"));
        plans.add(planB("curated-B09", 5008L, chain(ar(310), pack(0.15)),   "ar +3.3% + pack +0.02"));
        plans.add(planB("curated-B10", 5009L, chain(ar(305), pack(0.17)),   "ar +1.7% + pack +0.04"));

        // ---------- C. low_confidence_baseline (14) ----------
        // 同 scenario_id "mixed_curated", relaxed 范围:wc±1, ts±20%, dur±25%, ar±25%, pack±0.15
        plans.add(planC("curated-C01", 6000L, wc(7),                                  "wc -1 (relaxed)"));
        plans.add(planC("curated-C02", 6001L, wc(9),                                  "wc +1 (relaxed)"));
        plans.add(planC("curated-C03", 6002L, ts(200),                                "ts -20%"));
        plans.add(planC("curated-C04", 6003L, ts(300),                                "ts +20%"));
        plans.add(planC("curated-C05", 6004L, twc(2),                                 "takeaway_window +1"));
        plans.add(planC("curated-C06", 6005L, twc(0),                                 "takeaway_window -1 (still relaxed)"));
        plans.add(planC("curated-C07", 6006L, dur(2.4),                               "duration +20%"));
        plans.add(planC("curated-C08", 6007L, dur(1.6),                               "duration -20%"));
        plans.add(planC("curated-C09", 6008L, pack(0.20),                             "pack +0.07"));
        plans.add(planC("curated-C10", 6009L, pack(0.05),                             "pack -0.08"));
        plans.add(planC("curated-C11", 6010L, chain(wc(7), ts(210), ar(270)),         "multi: wc-1, ts-16%, ar-10%"));
        plans.add(planC("curated-C12", 6011L, chain(wc(9), ts(290), dur(2.3)),        "multi: wc+1, ts+16%, dur+15%"));
        plans.add(planC("curated-C13", 6012L, chain(ar(345), pack(0.18)),             "ar +15% + pack +0.05 (multi)"));
        plans.add(planC("curated-C14", 6013L, chain(ar(240), ts(275), dur(1.85)),     "ar -20%, ts +10%, dur -7.5%"));

        // ---------- D. very_low_confidence_baseline (7) ----------
        plans.add(new SamplePlan("curated-D01", "very_low_confidence_baseline", "offpeak_quiet",
                7000L, "very_low", "global_reference_baseline",
                chain(ar(80), ts(300), wc(4), dur(1.0), peak(false), totalStudents(400)),
                "Off-peak quiet: very different from baseline."));
        plans.add(new SamplePlan("curated-D02", "very_low_confidence_baseline", "mega_peak",
                7001L, "very_low", "global_reference_baseline",
                chain(ar(600), ts(400), wc(12), ql(80), totalStudents(2000)),
                "Mega peak: scale up window/seat/queue."));
        plans.add(new SamplePlan("curated-D03", "very_low_confidence_baseline", "rainy_emergency",
                7002L, "very_low", "global_reference_baseline",
                chain(weather("rainy", 1.3), pack(0.30), wc(9), ar(350), totalStudents(1200)),
                "Rainy emergency: weather + pack + wc shift."));
        plans.add(new SamplePlan("curated-D04", "very_low_confidence_baseline", "seat_crisis_baseline",
                7003L, "very_low", "global_reference_baseline",
                chain(ts(80), ar(200), wc(5), totalStudents(800)),
                "Seat crisis: severe seat shortage."));
        plans.add(new SamplePlan("curated-D05", "very_low_confidence_baseline", "takeaway_heavy",
                7004L, "very_low", "global_reference_baseline",
                chain(pack(0.45), twc(3), wc(10), ts(200)),
                "Takeaway heavy: high pack + multi-takeaway windows."));
        plans.add(new SamplePlan("curated-D06", "very_low_confidence_baseline", "tiny_setup",
                7005L, "very_low", "global_reference_baseline",
                chain(wc(2), ts(40), ar(60), dur(1.0), peak(false), totalStudents(200)),
                "Tiny setup: minimal facilities."));
        plans.add(new SamplePlan("curated-D07", "very_low_confidence_baseline", "weekend_low",
                7006L, "very_low", "global_reference_baseline",
                chain(ar(120), dur(3.0), ts(180), wc(5), peak(false), totalStudents(600)),
                "Weekend low: long duration, low rate."));

        // ---------- E. no_comparable_history_target (2) ----------
        plans.add(new SamplePlan("curated-E01", "no_comparable_history_target", "extreme_isolated_low",
                8000L, "none", "none",
                chain(ar(22), dur(4.0), ts(15), wc(2), pack(0.85),
                        weather("rainy", 1.5), ql(10), peak(false), totalStudents(150)),
                "Extreme low-volume isolated: foggy edge case."));
        plans.add(new SamplePlan("curated-E02", "no_comparable_history_target", "extreme_isolated_high",
                8001L, "none", "none",
                chain(ar(900), dur(0.5), ts(600), wc(20), pack(0.02),
                        ql(200), peak(false), totalStudents(1500)),
                "Extreme high-volume isolated: short duration mass arrival."));

        // ---------- F. anomaly_cases (7) ----------
        plans.add(new SamplePlan("curated-F01", "anomaly_cases", "overload_high_wait",
                9000L, "low", "weighted_nearest_neighbors",
                chain(ar(500), wc(4), ql(80), ts(300), totalStudents(1500)),
                "High wait time anomaly: ar=500 + wc=4."));
        plans.add(new SamplePlan("curated-F02", "anomaly_cases", "high_abandonment",
                9001L, "low", "weighted_nearest_neighbors",
                chain(ar(600), ql(10), wc(5), ts(200), totalStudents(2000)),
                "High abandonment anomaly: queue_limit=10 with ar=600."));
        plans.add(new SamplePlan("curated-F03", "anomaly_cases", "queue_peak_spike",
                9002L, "low", "weighted_nearest_neighbors",
                chain(ar(550), wc(5), ql(200), peakScale(1.5), totalStudents(1500)),
                "Queue peak anomaly: high arrival + amplified peak."));
        plans.add(new SamplePlan("curated-F04", "anomaly_cases", "seat_crisis_anomaly",
                9003L, "low", "weighted_nearest_neighbors",
                chain(ar(380), ts(80), wc(8), totalStudents(1000)),
                "Seat crisis anomaly: ts=80 vs ar=380."));
        plans.add(new SamplePlan("curated-F05", "anomaly_cases", "takeaway_storm",
                9004L, "low", "weighted_nearest_neighbors",
                chain(pack(0.85), weather("rainy", 1.5), ar(350), twc(3), wc(9)),
                "Takeaway storm anomaly: heavy pack + rainy."));
        plans.add(new SamplePlan("curated-F06", "anomaly_cases", "peak_pressure",
                9005L, "low", "weighted_nearest_neighbors",
                chain(ar(450), peakScale(1.8), wc(7), totalStudents(1200)),
                "Peak pressure anomaly: amplified class peak."));
        plans.add(new SamplePlan("curated-F07", "anomaly_cases", "window_shortage",
                9006L, "low", "weighted_nearest_neighbors",
                chain(twc(0), wc(3), ar(320), pack(0.6), totalStudents(900)),
                "Window shortage anomaly: only 3 windows."));

        return plans;
    }

    private SamplePlan planB(String id, long seed, Consumer<SimConfig> mutator, String notes) {
        return new SamplePlan(id, "medium_confidence_baseline", "dinner_peak_curated",
                seed, "medium", "strict_similar_config", mutator, notes);
    }

    private SamplePlan planC(String id, long seed, Consumer<SimConfig> mutator, String notes) {
        return new SamplePlan(id, "low_confidence_baseline", "mixed_curated",
                seed, "low", "relaxed_similar_config_or_wnn", mutator, notes);
    }

    /**
     * Round 2: 30 diverse samples covering 10 domains (breakfast/dinner/late_night/weather/
     * weekend/exam/festival/layout/takeaway/edge). Each scenario_id is unique;
     * intent is to enrich the global reference pool, not to self-match.
     */
    private List<SamplePlan> buildDiversePlans() {
        List<SamplePlan> plans = new ArrayList<>();

        // ---------- G. breakfast (3) ----------
        plans.add(diverse("curated-G01", "breakfast_diverse", "breakfast_quiet",  10000L,
                chain(ar(80),  ts(200), wc(4), dur(1.5), peak(false), pack(0.10)),
                "Breakfast quiet: low rate + early hour."));
        plans.add(diverse("curated-G02", "breakfast_diverse", "breakfast_rush",   10001L,
                chain(ar(180), ts(200), wc(5), dur(1.0), peakScale(1.4), pack(0.15)),
                "Breakfast rush: short surge before classes."));
        plans.add(diverse("curated-G03", "breakfast_diverse", "breakfast_normal", 10002L,
                chain(ar(120), ts(180), wc(4), dur(1.5), pack(0.12)),
                "Breakfast normal: typical weekday morning."));

        // ---------- H. dinner (3) ----------
        plans.add(diverse("curated-H01", "dinner_diverse", "dinner_peak",     10100L,
                chain(ar(400), ts(280), wc(10), dur(2.0), peakScale(1.5), pack(0.18), totalStudents(1400)),
                "Dinner peak: 5-7pm rush."));
        plans.add(diverse("curated-H02", "dinner_diverse", "dinner_relaxed",  10101L,
                chain(ar(200), ts(280), wc(8), dur(2.5), pack(0.20)),
                "Dinner relaxed: longer window, moderate flow."));
        plans.add(diverse("curated-H03", "dinner_diverse", "dinner_late",     10102L,
                chain(ar(80), ts(280), wc(3), dur(2.0), pack(0.35), peak(false)),
                "Dinner late: pre-close hour, fewer windows, more takeaway."));

        // ---------- I. late_night (2) ----------
        plans.add(diverse("curated-I01", "late_night_diverse", "late_night_low",      10200L,
                chain(ar(40), ts(100), wc(2), dur(2.0), pack(0.50), peak(false)),
                "Late night low traffic, takeaway-heavy."));
        plans.add(diverse("curated-I02", "late_night_diverse", "late_night_takeaway", 10201L,
                chain(ar(60), ts(80), wc(2), dur(2.0), pack(0.70), peak(false)),
                "Late night takeaway dominant."));

        // ---------- J. weather (5) ----------
        plans.add(diverse("curated-J01", "weather_diverse", "rainy_heavy",  10300L,
                chain(weather("rainy",  1.4), pack(0.35), ar(280), dur(2.0), wc(8), ts(250)),
                "Heavy rain: pack/factor surge."));
        plans.add(diverse("curated-J02", "weather_diverse", "snowy",        10301L,
                chain(weather("snowy",  1.5), pack(0.45), ar(200), dur(2.0), wc(7), ts(250)),
                "Snowy day: high stay-in tendency."));
        plans.add(diverse("curated-J03", "weather_diverse", "foggy",        10302L,
                chain(weather("foggy",  1.2), pack(0.20), ar(240), dur(2.0), wc(8), ts(250)),
                "Foggy: mild weather effect."));
        plans.add(diverse("curated-J04", "weather_diverse", "hot",          10303L,
                chain(weather("hot",    1.3), pack(0.40), ar(260), dur(2.0), wc(8), ts(250)),
                "Hot day: takeaway preferred."));
        plans.add(diverse("curated-J05", "weather_diverse", "cold",         10304L,
                chain(weather("cold",   0.9), pack(0.10), ar(200), dur(2.0), wc(7), ts(250)),
                "Cold day: dine-in preferred."));

        // ---------- K. weekend (3) ----------
        plans.add(diverse("curated-K01", "weekend_diverse", "weekend_brunch", 10400L,
                chain(ar(180), ts(300), wc(6), dur(3.0), peak(false), pack(0.18)),
                "Weekend brunch: long relaxed period."));
        plans.add(diverse("curated-K02", "weekend_diverse", "weekend_mid",    10401L,
                chain(ar(120), ts(300), wc(5), dur(3.0), peak(false), pack(0.15)),
                "Weekend midday: evenly distributed."));
        plans.add(diverse("curated-K03", "weekend_diverse", "weekend_low",    10402L,
                chain(ar(80),  ts(250), wc(4), dur(2.5), peak(false), pack(0.12)),
                "Weekend low: minimal demand."));

        // ---------- L. exam_period (3) ----------
        plans.add(diverse("curated-L01", "exam_diverse", "exam_morning_rush", 10500L,
                chain(ar(350), ts(200), wc(8), dur(1.5), pack(0.50), peakScale(1.5), totalStudents(1200)),
                "Exam morning: heavy takeaway for studying."));
        plans.add(diverse("curated-L02", "exam_diverse", "exam_afternoon",    10501L,
                chain(ar(280), ts(200), wc(7), dur(2.0), pack(0.40), totalStudents(1100)),
                "Exam afternoon: sustained takeaway."));
        plans.add(diverse("curated-L03", "exam_diverse", "exam_late",         10502L,
                chain(ar(120), ts(180), wc(4), dur(2.0), pack(0.55), peak(false)),
                "Exam late: late-hour study takeaway."));

        // ---------- M. festival (3) ----------
        plans.add(diverse("curated-M01", "festival_diverse", "alumni_day_surge",  10600L,
                chain(ar(700), ts(400), wc(14), dur(2.5), ql(100), totalStudents(2000)),
                "Alumni day: visitor surge."));
        plans.add(diverse("curated-M02", "festival_diverse", "holiday_low",       10601L,
                chain(ar(60), ts(200), wc(3), dur(2.0), peak(false)),
                "Holiday low: most students gone."));
        plans.add(diverse("curated-M03", "festival_diverse", "orientation_week",  10602L,
                chain(ar(500), ts(350), wc(12), dur(3.0), ql(80), totalStudents(1800)),
                "Orientation: extended sustained crowd."));

        // ---------- N. layout (3) ----------
        plans.add(diverse("curated-N01", "layout_diverse", "mega_hall",     10700L,
                chain(ts(800), wc(15), ar(400), dur(2.0), totalStudents(1800)),
                "Mega hall: many seats + many windows."));
        plans.add(diverse("curated-N02", "layout_diverse", "cramped",       10701L,
                chain(ts(60), wc(3), ar(180), dur(2.0), totalStudents(700)),
                "Cramped layout: tight space."));
        plans.add(diverse("curated-N03", "layout_diverse", "wide_takeaway", 10702L,
                chain(twc(4), wc(12), ts(150), ar(300), pack(0.40)),
                "Wide takeaway: 4 takeaway windows out of 12."));

        // ---------- O. takeaway (3) ----------
        plans.add(diverse("curated-O01", "takeaway_diverse", "takeaway_low",      10800L,
                chain(pack(0.05), twc(0), wc(8), ts(300), ar(280)),
                "Takeaway low: dine-in dominant."));
        plans.add(diverse("curated-O02", "takeaway_diverse", "takeaway_mid",      10801L,
                chain(pack(0.30), twc(2), wc(8), ts(250), ar(300)),
                "Takeaway mid: balanced split."));
        plans.add(diverse("curated-O03", "takeaway_diverse", "takeaway_dominant", 10802L,
                chain(pack(0.65), twc(4), wc(10), ts(180), ar(320)),
                "Takeaway dominant: most go takeaway."));

        // ---------- P. edge (2) ----------
        plans.add(diverse("curated-P01", "edge_diverse", "micro_setup", 10900L,
                chain(ar(15), ts(10), wc(1), dur(1.0), pack(0.10), peak(false), totalStudents(50)),
                "Micro setup: minimal legal configuration."));
        plans.add(diverse("curated-P02", "edge_diverse", "huge_setup",  10901L,
                chain(ar(1000), ts(800), wc(24), dur(2.0), ql(200), totalStudents(2500)),
                "Huge setup: max-scale configuration."));

        return plans;
    }

    private SamplePlan diverse(String id, String group, String scenarioId, long seed,
                               Consumer<SimConfig> mutator, String notes) {
        return new SamplePlan(id, group, scenarioId, seed,
                "very_low", "global_reference_baseline", mutator, notes);
    }

    /** 基准:lunch_peak_curated 配置。改动这里需同步 §4 plan 表。 */
    private SimConfig baseConfig() {
        SimConfig c = new SimConfig();
        c.setSimulationName("curated-base");
        c.setDuration(2.0);
        c.setArrivalRate(300);
        c.setQueueLimit(40);
        c.setPackProbability(0.13);
        c.setGroupArrivalProb(0.08);
        c.setPartySize(3);
        c.setWalkTimeMean(8.0);
        c.setCongestionPenalty(0.35);

        c.getBaseConfig().setTotalSeats(250);
        c.getBaseConfig().setTotalStudents(1000);
        c.getBaseConfig().setWindowCount(8);
        c.getBaseConfig().setTakeawayWindowCount(1);
        c.getBaseConfig().setTakeawayServiceTimeMultiplier(1.2);

        c.getWeatherConfig().setCurrentWeather("sunny");
        c.getWeatherConfig().setWeatherImpactFactor(1.0);

        c.getRandomBounds().setArrivalInterval(0);
        c.getRandomBounds().setServiceRange(List.of(45, 180));
        c.getRandomBounds().setDiningRange(List.of(900, 2400));
        c.getRandomBounds().setPreferenceRange(List.of(0.05, 0.42));

        SimConfig.DistributionSpec arrival = new SimConfig.DistributionSpec();
        arrival.setType("POISSON");
        arrival.setLambda(300);
        c.setArrivalDist(arrival);

        SimConfig.DistributionSpec service = new SimConfig.DistributionSpec();
        service.setType("NORMAL");
        service.setMean(90);
        service.setStd(Math.max(1, (180 - 45) / 6.0));
        service.setMin(45);
        service.setMax(180);
        c.setNormalServiceDist(copyDist(service));
        c.setWindowServiceDist(copyDist(service));

        SimConfig.DistributionSpec dining = new SimConfig.DistributionSpec();
        dining.setType("NORMAL");
        dining.setMean(1500);
        dining.setStd(Math.max(1, (2400 - 900) / 6.0));
        dining.setMin(900);
        dining.setMax(2400);
        c.setDiningTimeDist(dining);

        c.getPeakConfig().setClassPeakEnabled(true);
        c.getPeakConfig().setClassPeakWindows(List.of(
                new SimConfig.PeakConfig.PeakWindow(12, 32, 2.6),
                new SimConfig.PeakConfig.PeakWindow(64, 86, 1.8)));
        return c;
    }

    private SimConfig.DistributionSpec copyDist(SimConfig.DistributionSpec src) {
        SimConfig.DistributionSpec d = new SimConfig.DistributionSpec();
        d.setType(src.getType());
        d.setLambda(src.getLambda());
        d.setMean(src.getMean());
        d.setStd(src.getStd());
        d.setMin(src.getMin());
        d.setMax(src.getMax());
        return d;
    }

    // ---- 简洁 mutator 工厂 ----

    private Consumer<SimConfig> ar(double v) {
        return c -> {
            c.setArrivalRate(v);
            if (c.getArrivalDist() != null) c.getArrivalDist().setLambda(v);
        };
    }

    private Consumer<SimConfig> dur(double v) {
        return c -> c.setDuration(v);
    }

    private Consumer<SimConfig> pack(double v) {
        return c -> c.setPackProbability(v);
    }

    private Consumer<SimConfig> wc(int v) {
        return c -> c.getBaseConfig().setWindowCount(v);
    }

    private Consumer<SimConfig> ts(int v) {
        return c -> c.getBaseConfig().setTotalSeats(v);
    }

    private Consumer<SimConfig> twc(int v) {
        return c -> c.getBaseConfig().setTakeawayWindowCount(v);
    }

    private Consumer<SimConfig> ql(int v) {
        return c -> c.setQueueLimit(v);
    }

    private Consumer<SimConfig> totalStudents(int v) {
        return c -> c.getBaseConfig().setTotalStudents(v);
    }

    private Consumer<SimConfig> weather(String type, double factor) {
        return c -> {
            c.getWeatherConfig().setCurrentWeather(type);
            c.getWeatherConfig().setWeatherImpactFactor(factor);
        };
    }

    private Consumer<SimConfig> peak(boolean enabled) {
        return c -> c.getPeakConfig().setClassPeakEnabled(enabled);
    }

    private Consumer<SimConfig> peakScale(double scale) {
        return c -> {
            c.getPeakConfig().setClassPeakEnabled(true);
            c.getPeakConfig().setClassPeakWindows(List.of(
                    new SimConfig.PeakConfig.PeakWindow(12, 32, 2.6 * scale),
                    new SimConfig.PeakConfig.PeakWindow(64, 86, 1.8 * scale)));
        };
    }

    private Consumer<SimConfig> groupProb(double v) {
        return c -> c.setGroupArrivalProb(v);
    }

    private Consumer<SimConfig> partySize(int v) {
        return c -> c.setPartySize(v);
    }

    @SafeVarargs
    private final Consumer<SimConfig> chain(Consumer<SimConfig>... fns) {
        return c -> {
            for (Consumer<SimConfig> f : fns) f.accept(c);
        };
    }

    // ---- IO helpers ----

    private static long countFilesSafe(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static long countJsonFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".summary.json"))
                    .count();
        }
    }

    private static long countSummaryFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".summary.json"))
                    .count();
        }
    }

    private static long dirSizeBytes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    }).sum();
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static final class SamplePlan {
        final String sampleId;
        final String group;
        final String scenarioId;
        final long seed;
        final String expectedConfidence;
        final String expectedStrategy;
        final Consumer<SimConfig> mutator;
        final String notes;

        SamplePlan(String sampleId, String group, String scenarioId, long seed,
                   String expectedConfidence, String expectedStrategy,
                   Consumer<SimConfig> mutator, String notes) {
            this.sampleId = sampleId;
            this.group = group;
            this.scenarioId = scenarioId;
            this.seed = seed;
            this.expectedConfidence = expectedConfidence;
            this.expectedStrategy = expectedStrategy;
            this.mutator = mutator;
            this.notes = notes;
        }
    }
}
