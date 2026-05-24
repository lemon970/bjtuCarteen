package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.AggregateMetrics;
import com.bjtu.simulation.dto.BatchRunReport;
import com.bjtu.simulation.dto.BatchRunRequest;
import com.bjtu.simulation.dto.MetricStat;
import com.bjtu.simulation.dto.PerSeedMetric;
import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-010A + RFC-010B 验收套件:T-10A-1..12 + T-10B-AGG-1..4。
 *
 * <p>所有测试用 真 {@link SimulationRunService} + 真 {@link PerSeedMetricExtractor} + 真
 * {@link AggregateMetricsCalculator}。本套件不引入 mocking 框架,与项目现有测试风格保持一致。</p>
 */
class BatchRunServiceTest {

    private final SimulationRunService runService = new SimulationRunService();
    private final PerSeedMetricExtractor extractor = new PerSeedMetricExtractor();
    private final AggregateMetricsCalculator aggregateCalculator = new AggregateMetricsCalculator();
    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
    private final BatchRunService service = new BatchRunService(
            runService, extractor, aggregateCalculator, mapper);

    // ---- helpers ----

    private SimConfig staticSplitConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc010a-static");
        config.setDuration(0.25);
        config.setArrivalRate(60);
        config.setQueueLimit(10);
        config.setPackProbability(0.2);
        // 注意:不在这里 setSeed,seed 由 BatchRunService 内部循环写入
        config.getBaseConfig().setWindowCount(4);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTotalSeats(40);
        config.getBaseConfig().setTotalStudents(40);
        return config;
    }

    private SimConfig preferenceAwareConfig() {
        SimConfig config = staticSplitConfig();
        config.setSimulationName("rfc010a-pref");
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return config;
    }

    private static long[] seedList(long... seeds) {
        return seeds.clone();
    }

    private byte[] serialize(Object obj) {
        try {
            return mapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- T-10A-1 ----

    @Test
    void t10a1_sameSeedsAndExplicitRunIdProducesBytewiseIdenticalPerSeedMetrics() {
        BatchRunRequest req1 = new BatchRunRequest(staticSplitConfig(), seedList(1L, 2L, 3L));
        req1.setRunId("batch-fixed-1");
        BatchRunRequest req2 = new BatchRunRequest(staticSplitConfig(), seedList(1L, 2L, 3L));
        req2.setRunId("batch-fixed-1");

        BatchRunReport r1 = service.run(req1);
        BatchRunReport r2 = service.run(req2);

        assertEquals(r1.getRunId(), r2.getRunId());
        assertEquals(r1.getBaseConfigDigest(), r2.getBaseConfigDigest());
        assertArrayEquals(r1.getSeeds(), r2.getSeeds());
        // 每个 PerSeedMetric 序列化字节级一致(包括 reportId = "batch-fixed-1-"+i)
        List<PerSeedMetric> m1 = r1.getPerSeedMetrics();
        List<PerSeedMetric> m2 = r2.getPerSeedMetrics();
        assertEquals(m1.size(), m2.size());
        for (int i = 0; i < m1.size(); i++) {
            assertArrayEquals(serialize(m1.get(i)), serialize(m2.get(i)),
                    "perSeedMetrics[" + i + "] 字节级必须一致");
            assertEquals("batch-fixed-1-" + i, m1.get(i).getReportId(),
                    "reportId 必须按 runId+'-'+i 确定性派生");
        }
    }

    // ---- T-10A-2 ----

    @Test
    void t10a2_defaultMetricsOnlyJsonHasNoRunsField() throws Exception {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(), seedList(7L));
        req.setRunId("batch-no-runs");
        BatchRunReport report = service.run(req);

        // class-level: 不允许声明 runs 字段
        Set<String> fieldNames = new HashSet<>();
        for (Field f : BatchRunReport.class.getDeclaredFields()) {
            fieldNames.add(f.getName());
        }
        assertFalse(fieldNames.contains("runs"),
                "BatchRunReport 类不应声明 runs 字段");

        // JSON-level: 序列化后不出现 runs 节点
        JsonNode root = mapper.readTree(mapper.writeValueAsBytes(report));
        assertFalse(root.has("runs"), "BatchRunReport JSON 不应包含 'runs' 字段");
    }

    // ---- T-10A-3 ----

    @Test
    void t10a4_staticSplitElevenFieldsLayout() {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(), seedList(11L, 22L));
        req.setRunId("batch-static-fields");
        BatchRunReport report = service.run(req);

        for (PerSeedMetric m : report.getPerSeedMetrics()) {
            assertNotNull(m.getReportId());
            assertTrue(m.getArrivedCount() >= 0);
            assertTrue(m.getServedCount() >= 0);
            assertTrue(m.getTypicalWaitTimeMinutes() >= 0.0);
            assertTrue(m.getMedianWaitTimeMinutes() >= 0.0);
            assertTrue(m.getP90WaitTimeMinutes() >= 0.0);
            assertTrue(m.getSeatUtilizationRate() >= 0.0);
            assertTrue(m.getTakeawayRate() >= 0.0);
            assertTrue(m.getMaxTotalQueueSize() >= 0);
            // 3 个 PR-9D 字段在 STATIC_SPLIT 下必须 null
            assertNull(m.getPopularServedShare(), "STATIC_SPLIT 下 popularServedShare 必须 null");
            assertNull(m.getColdServedShare(), "STATIC_SPLIT 下 coldServedShare 必须 null");
            assertNull(m.getWindowServedCountCv(), "STATIC_SPLIT 下 windowServedCountCv 必须 null");
        }
    }

    // ---- T-10A-5 ----

    @Test
    void t10a5_preferenceAwareElevenFieldsAllPresent() {
        BatchRunRequest req = new BatchRunRequest(preferenceAwareConfig(), seedList(11L, 22L));
        req.setRunId("batch-pref-fields");
        BatchRunReport report = service.run(req);

        for (PerSeedMetric m : report.getPerSeedMetrics()) {
            assertNotNull(m.getPopularServedShare());
            assertNotNull(m.getColdServedShare());
            assertNotNull(m.getWindowServedCountCv());
            // share 在 [0, 1]
            assertTrue(m.getPopularServedShare() >= 0.0 && m.getPopularServedShare() <= 1.0,
                    () -> "popularServedShare 应在 [0,1],实际 " + m.getPopularServedShare());
            assertTrue(m.getColdServedShare() >= 0.0 && m.getColdServedShare() <= 1.0,
                    () -> "coldServedShare 应在 [0,1],实际 " + m.getColdServedShare());
            assertTrue(m.getWindowServedCountCv() >= 0.0,
                    () -> "windowServedCountCv 必须 >= 0,实际 " + m.getWindowServedCountCv());
        }
    }

    // ---- T-10A-6 ----

    @Test
    void t10a7_emptyOrNullSeedsThrowsIae() {
        BatchRunRequest reqEmpty = new BatchRunRequest(staticSplitConfig(), new long[0]);
        assertThrows(IllegalArgumentException.class, () -> service.run(reqEmpty));

        BatchRunRequest reqNull = new BatchRunRequest(staticSplitConfig(), null);
        assertThrows(IllegalArgumentException.class, () -> service.run(reqNull));
    }

    // ---- T-10A-8 ----

    @Test
    void t10a8_baseConfigIsNotMutated() {
        SimConfig original = staticSplitConfig();
        // 调用前 seed 是 null,baseConfig 引用应保持不变
        assertNull(original.getSeed());
        BatchRunRequest req = new BatchRunRequest(original, seedList(101L, 202L, 303L));
        req.setRunId("batch-no-mutate");

        service.run(req);

        // 调用后,原 baseConfig 引用的 seed 仍然为 null,其他业务字段也未被改写
        assertNull(original.getSeed(),
                "调用后 baseConfig.seed 必须仍为 null(不被 mutate)");
        assertEquals("rfc010a-static", original.getSimulationName());
        assertEquals(0.25, original.getDuration(), 0.0);
        assertEquals(60.0, original.getArrivalRate(), 0.0);
        assertEquals(4, original.getBaseConfig().getWindowCount());
    }

    // ---- T-10A-9 ----

    @Test
    void t10a9_n20JsonUnder10kb() throws Exception {
        long[] seeds = new long[20];
        for (int i = 0; i < 20; i++) {
            seeds[i] = 1000L + i;
        }
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(), seeds);
        req.setRunId("batch-volume-20");

        BatchRunReport report = service.run(req);
        byte[] json = mapper.writeValueAsBytes(report);
        assertTrue(json.length < 10_240,
                () -> "N=20 默认 METRICS_ONLY 报告 JSON 字节数应 < 10KB,实际 = " + json.length);
    }

    // ---- T-10A-10 ----

    @Test
    void t10a10a_sameBaseConfigSameDigest() {
        BatchRunRequest req1 = new BatchRunRequest(staticSplitConfig(), seedList(1L));
        req1.setRunId("digest-a-1");
        BatchRunRequest req2 = new BatchRunRequest(staticSplitConfig(), seedList(1L));
        req2.setRunId("digest-a-2");
        assertEquals(service.run(req1).getBaseConfigDigest(),
                service.run(req2).getBaseConfigDigest(),
                "(a) 同 baseConfig 两次调用 → digest 必须相等");
    }

    @Test
    void t10a10b_differentArrivalRateDifferentDigest() {
        BatchRunRequest req1 = new BatchRunRequest(staticSplitConfig(), seedList(1L));
        req1.setRunId("digest-b-1");
        SimConfig altered = staticSplitConfig();
        altered.setArrivalRate(120);
        BatchRunRequest req2 = new BatchRunRequest(altered, seedList(1L));
        req2.setRunId("digest-b-2");
        assertNotEquals(service.run(req1).getBaseConfigDigest(),
                service.run(req2).getBaseConfigDigest(),
                "(b) 改 arrivalRate → digest 必须不同");
    }

    @Test
    void t10a10c_differentWindowCountDifferentDigest() {
        BatchRunRequest req1 = new BatchRunRequest(staticSplitConfig(), seedList(1L));
        req1.setRunId("digest-c-1");
        SimConfig altered = staticSplitConfig();
        altered.getBaseConfig().setWindowCount(6);
        BatchRunRequest req2 = new BatchRunRequest(altered, seedList(1L));
        req2.setRunId("digest-c-2");
        assertNotEquals(service.run(req1).getBaseConfigDigest(),
                service.run(req2).getBaseConfigDigest(),
                "(c) 改 windowCount → digest 必须不同");
    }

    @Test
    void t10a10d_differentBaseConfigSeedSameDigest() {
        SimConfig c1 = staticSplitConfig();
        c1.setSeed(111L);
        SimConfig c2 = staticSplitConfig();
        c2.setSeed(999L);
        BatchRunRequest req1 = new BatchRunRequest(c1, seedList(1L));
        req1.setRunId("digest-d-1");
        BatchRunRequest req2 = new BatchRunRequest(c2, seedList(1L));
        req2.setRunId("digest-d-2");
        assertEquals(service.run(req1).getBaseConfigDigest(),
                service.run(req2).getBaseConfigDigest(),
                "(d) baseConfig.seed 不同其他字段相同 → digest 必须相等(seed 不进 digest)");
    }

    @Test
    void t10a10e_differentSeedsArraySameDigest() {
        BatchRunRequest req1 = new BatchRunRequest(staticSplitConfig(), seedList(1L, 2L));
        req1.setRunId("digest-e-1");
        BatchRunRequest req2 = new BatchRunRequest(staticSplitConfig(), seedList(7L, 8L, 9L));
        req2.setRunId("digest-e-2");
        assertEquals(service.run(req1).getBaseConfigDigest(),
                service.run(req2).getBaseConfigDigest(),
                "(e) seeds 数组不同 baseConfig 相同 → digest 必须相等(seeds 不进 digest)");
    }

    // ---- T-10A-11 ----

    @Test
    void t10a11_missingRunIdFallsBackToUuid() {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(), seedList(42L));
        // 不设置 runId

        BatchRunReport report = service.run(req);
        assertNotNull(report.getRunId());
        // UUID 格式校验:能被 UUID.fromString 解析
        UUID parsed = UUID.fromString(report.getRunId());
        assertNotNull(parsed);
        // 不断言两次缺省调用结果相等(这条路径不承诺字节级一致)
    }

    // ---- T-10A-12 ----

    @Test
    void t10a12_topLevelFieldsAreSixIncludingAggregate() throws Exception {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(), seedList(1L));
        req.setRunId("batch-six-fields");
        BatchRunReport report = service.run(req);

        // class-level: 仍然不允许声明 runs 字段
        for (Field f : BatchRunReport.class.getDeclaredFields()) {
            assertNotEquals("runs", f.getName(),
                    "BatchRunReport 类不应声明 runs 字段");
        }

        // JSON-level: 序列化后不出现 runs 节点
        JsonNode root = mapper.readTree(mapper.writeValueAsBytes(report));
        assertFalse(root.has("runs"), "BatchRunReport JSON 不应包含 'runs' 字段");

        // 顶层精确 5 字段(snake_case),含 aggregate
        Set<String> top = new HashSet<>();
        root.fieldNames().forEachRemaining(top::add);
        Set<String> expected = new HashSet<>(
                Arrays.asList("run_id", "base_config_digest", "seeds",
                        "per_seed_metrics", "aggregate"));
        assertEquals(expected, top,
                "BatchRunReport JSON 顶层字段必须严格为 5 个(snake_case):" + expected + ",实际:" + top);
    }

    // ---- T-10B-AGG-1 ----

    @Test
    void t10bAgg1_aggregateNotNullAndSampleCountMatches() {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(),
                seedList(1L, 2L, 3L, 4L, 5L));
        req.setRunId("agg-1");
        BatchRunReport report = service.run(req);

        assertNotNull(report.getAggregate(), "aggregate 必须非 null");
        assertEquals(5, report.getAggregate().getSampleCount(),
                "aggregate.sampleCount 必须等于 seeds.length");
    }

    // ---- T-10B-AGG-2 ----

    @Test
    void t10bAgg2_preferenceAwareAllElevenStatsValid() {
        BatchRunRequest req = new BatchRunRequest(preferenceAwareConfig(),
                seedList(11L, 22L, 33L, 44L, 55L));
        req.setRunId("agg-2");
        AggregateMetrics agg = service.run(req).getAggregate();

        MetricStat[] stats = new MetricStat[]{
                agg.getArrivedCount(),
                agg.getServedCount(),
                agg.getTypicalWaitTimeMinutes(),
                agg.getMedianWaitTimeMinutes(),
                agg.getP90WaitTimeMinutes(),
                agg.getSeatUtilizationRate(),
                agg.getTakeawayRate(),
                agg.getMaxTotalQueueSize(),
                agg.getPopularServedShare(),
                agg.getColdServedShare(),
                agg.getWindowServedCountCv()
        };
        for (int i = 0; i < stats.length; i++) {
            int idx = i;
            MetricStat s = stats[i];
            assertNotNull(s, () -> "PREFERENCE_AWARE 路径下 stats[" + idx + "] 必须非 null");
            assertEquals("t", s.getCiMethod(), () -> "stats[" + idx + "].ciMethod 必须为 't'");
            assertTrue(s.getCi95Lower() <= s.getMean() + 1e-9,
                    () -> "stats[" + idx + "] ci95Lower 应 <= mean");
            assertTrue(s.getMean() <= s.getCi95Upper() + 1e-9,
                    () -> "stats[" + idx + "] mean 应 <= ci95Upper");
            assertTrue(s.getStddev() >= 0.0,
                    () -> "stats[" + idx + "] stddev 应 >= 0");
            assertTrue(s.getP10() <= s.getMedian() + 1e-9,
                    () -> "stats[" + idx + "] p10 应 <= median");
            assertTrue(s.getMedian() <= s.getP90() + 1e-9,
                    () -> "stats[" + idx + "] median 应 <= p90");
        }
    }

    // ---- T-10B-AGG-3 ----

    @Test
    void t10bAgg3_staticSplitOmitsThreePr9dStatsInJson() throws Exception {
        BatchRunRequest req = new BatchRunRequest(staticSplitConfig(),
                seedList(1L, 2L, 3L));
        req.setRunId("agg-3");
        BatchRunReport report = service.run(req);
        AggregateMetrics agg = report.getAggregate();

        // 8 核心字段非 null
        assertNotNull(agg.getArrivedCount());
        assertNotNull(agg.getServedCount());
        assertNotNull(agg.getTypicalWaitTimeMinutes());
        assertNotNull(agg.getMedianWaitTimeMinutes());
        assertNotNull(agg.getP90WaitTimeMinutes());
        assertNotNull(agg.getSeatUtilizationRate());
        assertNotNull(agg.getTakeawayRate());
        assertNotNull(agg.getMaxTotalQueueSize());

        // 3 PR-9D 字段在 STATIC_SPLIT 路径下整组 null
        assertNull(agg.getPopularServedShare());
        assertNull(agg.getColdServedShare());
        assertNull(agg.getWindowServedCountCv());

        // JSON 中不包含 3 个 PR-9D 节点
        JsonNode aggregateNode = mapper.readTree(mapper.writeValueAsBytes(report)).get("aggregate");
        assertNotNull(aggregateNode);
        assertFalse(aggregateNode.has("popular_served_share"));
        assertFalse(aggregateNode.has("cold_served_share"));
        assertFalse(aggregateNode.has("window_served_count_cv"));
    }

    // ---- T-10B-AGG-4 ----

    @Test
    void t10bAgg4_aggregateBytewiseDeterministic() {
        BatchRunRequest req1 = new BatchRunRequest(preferenceAwareConfig(),
                seedList(1L, 2L, 3L));
        req1.setRunId("agg-deterministic");
        BatchRunRequest req2 = new BatchRunRequest(preferenceAwareConfig(),
                seedList(1L, 2L, 3L));
        req2.setRunId("agg-deterministic");

        AggregateMetrics a1 = service.run(req1).getAggregate();
        AggregateMetrics a2 = service.run(req2).getAggregate();

        assertArrayEquals(serialize(a1), serialize(a2),
                "同 baseConfig + 同 seeds + 同 runId,aggregate 必须字节级一致");
    }
}
