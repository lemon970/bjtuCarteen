package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.ScanAxis;
import com.bjtu.simulation.dto.SensitivityReport;
import com.bjtu.simulation.dto.SensitivityRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WhitelistedParam;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-010C 验收套件:T-10C-1 ~ T-10C-12。
 *
 * <p>用真 {@link BatchRunService} + 真 {@link WhitelistedParameterMutator},不引入 mocking。
 * baseConfig 全部用 {@code duration=0.1h(6min)} + 小窗口 / 小座位,以控制 K×M×N 总测试时长。</p>
 */
class SensitivityAnalysisServiceTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
    private final BatchRunService batchRunService = new BatchRunService(
            new SimulationRunService(),
            new PerSeedMetricExtractor(),
            new AggregateMetricsCalculator(),
            mapper);
    private final WhitelistedParameterMutator mutator = new WhitelistedParameterMutator();
    private final SensitivityAnalysisService service = new SensitivityAnalysisService(
            batchRunService, mutator, mapper);

    // ---- helpers ----

    private SimConfig staticSplitConfig() {
        SimConfig c = new SimConfig();
        c.setSimulationName("rfc010c-static");
        c.setDuration(0.1);
        c.setArrivalRate(60);
        c.setQueueLimit(10);
        c.setPackProbability(0.2);
        c.getBaseConfig().setWindowCount(4);
        c.getBaseConfig().setTakeawayWindowCount(1);
        c.getBaseConfig().setTotalSeats(40);
        c.getBaseConfig().setTotalStudents(40);
        return c;
    }

    private SimConfig preferenceAwareConfig() {
        SimConfig c = staticSplitConfig();
        c.setSimulationName("rfc010c-pref");
        c.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        c.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return c;
    }

    private static double[] points(double... vs) {
        return vs.clone();
    }

    private static long[] seeds(long... s) {
        return s.clone();
    }

    private byte[] serialize(Object obj) {
        try {
            return mapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- T-10C-1 ----

    @Test
    void t10c1_singleAxisStaticSplitOmitsThreePr9d() {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(30, 60, 120))),
                seeds(1L, 2L));
        req.setRunId("c1");
        SensitivityReport report = service.run(req);

        assertEquals(1, report.getAxes().size());
        assertEquals(2, report.getSeedsPerPoint());

        var axis = report.getAxes().get(0);
        assertEquals(WhitelistedParam.ARRIVAL_RATE, axis.getParameter());
        assertArrayEquals(new double[]{30, 60, 120}, axis.getPoints(), 0.0);

        // 8 核心 curve 全有 3 点
        assertNotNull(axis.getArrivedCount());
        assertEquals(3, axis.getArrivedCount().getMeanAtPoint().length);
        assertNotNull(axis.getServedCount());
        assertNotNull(axis.getTypicalWaitTimeMinutes());
        assertNotNull(axis.getMedianWaitTimeMinutes());
        assertNotNull(axis.getP90WaitTimeMinutes());
        assertNotNull(axis.getSeatUtilizationRate());
        assertNotNull(axis.getTakeawayRate());
        assertNotNull(axis.getMaxTotalQueueSize());

        // 3 PR-9D curve 在 STATIC_SPLIT 下为 null
        assertNull(axis.getPopularServedShare());
        assertNull(axis.getColdServedShare());
        assertNull(axis.getWindowServedCountCv());
    }

    // ---- T-10C-2 ----

    @Test
    void t10c2_bytewiseDeterministic() {
        SensitivityRequest req1 = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(30, 60))),
                seeds(1L, 2L, 3L));
        req1.setRunId("sens-fixed-1");

        SensitivityRequest req2 = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(30, 60))),
                seeds(1L, 2L, 3L));
        req2.setRunId("sens-fixed-1");

        byte[] r1 = serialize(service.run(req1));
        byte[] r2 = serialize(service.run(req2));
        assertArrayEquals(r1, r2,
                "同 baseConfig + 同 seeds + 同 axes + 同 runId,SensitivityReport 必须字节级一致");
    }

    // ---- T-10C-3 ----

    @Test
    void t10c3_arrivalRateMonotonic() {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(0, 60, 120))),
                seeds(1L, 2L, 3L));
        req.setRunId("c3");
        SensitivityReport report = service.run(req);

        double[] arrived = report.getAxes().get(0).getArrivedCount().getMeanAtPoint();
        assertEquals(0.0, arrived[0], 0.0,
                "arrivalRate=0 时 arrivedCount=0(没有人到达)");
        assertTrue(arrived[1] >= arrived[0],
                () -> "arrivalRate=60 应 >= arrivalRate=0,实际 " + arrived[1] + " vs " + arrived[0]);
        assertTrue(arrived[2] >= arrived[1],
                () -> "arrivalRate=120 应 >= arrivalRate=60,实际 " + arrived[2] + " vs " + arrived[1]);
    }

    // ---- T-10C-4 ----

    @Test
    void t10c4_topLevelFourFieldsExact() throws Exception {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60))),
                seeds(1L));
        req.setRunId("c4");
        SensitivityReport report = service.run(req);

        JsonNode root = mapper.readTree(mapper.writeValueAsBytes(report));
        Set<String> top = new HashSet<>();
        root.fieldNames().forEachRemaining(top::add);
        Set<String> expected = new HashSet<>(
                Arrays.asList("run_id", "base_config_digest", "seeds_per_point", "axes"));
        assertEquals(expected, top,
                "SensitivityReport JSON 顶层字段必须严格为 4 个(snake_case):" + expected + ",实际:" + top);
    }

    // ---- T-10C-5 ----

    @Test
    void t10c5_preferenceAwareAllElevenCurvesPresent() {
        SensitivityRequest req = new SensitivityRequest(preferenceAwareConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60, 120))),
                seeds(1L, 2L));
        req.setRunId("c5");
        var axis = service.run(req).getAxes().get(0);

        assertNotNull(axis.getArrivedCount());
        assertNotNull(axis.getServedCount());
        assertNotNull(axis.getTypicalWaitTimeMinutes());
        assertNotNull(axis.getMedianWaitTimeMinutes());
        assertNotNull(axis.getP90WaitTimeMinutes());
        assertNotNull(axis.getSeatUtilizationRate());
        assertNotNull(axis.getTakeawayRate());
        assertNotNull(axis.getMaxTotalQueueSize());
        assertNotNull(axis.getPopularServedShare(),
                "PREFERENCE_AWARE 下 popularServedShare curve 必须非 null");
        assertNotNull(axis.getColdServedShare(),
                "PREFERENCE_AWARE 下 coldServedShare curve 必须非 null");
        assertNotNull(axis.getWindowServedCountCv(),
                "PREFERENCE_AWARE 下 windowServedCountCv curve 必须非 null");
    }

    // ---- T-10C-6 ----

    @Test
    void t10c6_staticSplitJsonOmitsThreePr9dCurves() throws Exception {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(30, 60))),
                seeds(1L));
        req.setRunId("c6");
        SensitivityReport report = service.run(req);

        JsonNode axisNode = mapper.readTree(mapper.writeValueAsBytes(report))
                .get("axes").get(0);
        assertNotNull(axisNode);
        assertFalse(axisNode.has("popular_served_share"));
        assertFalse(axisNode.has("cold_served_share"));
        assertFalse(axisNode.has("window_served_count_cv"));
        assertTrue(axisNode.has("arrived_count"), "8 核心 curve 仍然必须出现在 JSON 中");
    }

    // ---- T-10C-7 ----

    @Test
    void t10c7_multiAxis() {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(
                        new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60, 90)),
                        new ScanAxis(WhitelistedParam.TOTAL_SEATS, points(20, 40, 60))),
                seeds(1L));
        req.setRunId("c7");
        SensitivityReport report = service.run(req);

        assertEquals(2, report.getAxes().size());
        assertEquals(2, report.getAxes().get(0).getPoints().length);
        assertEquals(3, report.getAxes().get(1).getPoints().length);
        assertEquals(WhitelistedParam.ARRIVAL_RATE, report.getAxes().get(0).getParameter());
        assertEquals(WhitelistedParam.TOTAL_SEATS, report.getAxes().get(1).getParameter());
    }

    // ---- T-10C-8 ----

    @Test
    void t10c8_duplicateParameterThrows() {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(
                        new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60)),
                        new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(120))),
                seeds(1L));
        req.setRunId("c8");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.run(req));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate parameter"),
                () -> "异常 message 必须含 'duplicate parameter',实际:" + ex.getMessage());
    }

    // ---- T-10C-9 ----

    @Test
    void t10c9_emptyAxesOrSeedsThrows() {
        SensitivityRequest reqEmptyAxes = new SensitivityRequest(staticSplitConfig(),
                List.of(), seeds(1L));
        assertThrows(IllegalArgumentException.class, () -> service.run(reqEmptyAxes));

        SensitivityRequest reqNullSeeds = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60))),
                null);
        assertThrows(IllegalArgumentException.class, () -> service.run(reqNullSeeds));

        SensitivityRequest reqEmptyPoints = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, new double[0])),
                seeds(1L));
        assertThrows(IllegalArgumentException.class, () -> service.run(reqEmptyPoints));
    }

    // ---- T-10C-10 ----

    @Test
    void t10c10_volumeFullWhitelistJsonUnder8kb() throws Exception {
        SimConfig base = staticSplitConfig();
        // takeawayWindowCount 锚到 1,WINDOW_COUNT 扫描点 [3,4,5] 都合法
        base.getBaseConfig().setTakeawayWindowCount(1);

        SensitivityRequest req = new SensitivityRequest(base,
                List.of(
                        new ScanAxis(WhitelistedParam.ARRIVAL_RATE,
                                points(30, 45, 60, 90, 120)),
                        new ScanAxis(WhitelistedParam.WINDOW_COUNT,
                                points(3, 4, 5)),
                        new ScanAxis(WhitelistedParam.TAKEAWAY_WINDOW_COUNT,
                                points(0, 1, 2)),
                        new ScanAxis(WhitelistedParam.TOTAL_SEATS,
                                points(20, 30, 40, 50, 60)),
                        new ScanAxis(WhitelistedParam.SERVICE_RANGE_SCALE,
                                points(0.7, 0.85, 1.0, 1.15, 1.3)),
                        new ScanAxis(WhitelistedParam.PACK_PROBABILITY,
                                points(0.0, 0.25, 0.5, 0.75, 1.0))),
                seeds(1L, 2L, 3L));
        req.setRunId("c10");

        SensitivityReport report = service.run(req);
        byte[] json = mapper.writeValueAsBytes(report);
        assertTrue(json.length < 8192,
                () -> "K=6 / M up to 5 / N=3 报告 JSON 字节数应 < 8KB,实际 = " + json.length);
    }

    // ---- T-10C-11 ----

    @Test
    void t10c11_missingRunIdFallsBackToUuid() {
        SensitivityRequest req = new SensitivityRequest(staticSplitConfig(),
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(60))),
                seeds(42L));
        // 不设置 runId

        SensitivityReport report = service.run(req);
        assertNotNull(report.getRunId());
        UUID parsed = UUID.fromString(report.getRunId());
        assertNotNull(parsed);
    }

    // ---- T-10C-12 ----

    @Test
    void t10c12_summarySensitivityFormula() {
        // 直接对静态方法做手算对照
        // 数据:[10, 20, 50],centerIdx=3/2=1,centerY=20,(50-10)/20 = 2.0
        double s1 = SensitivityAnalysisService.summarySensitivity(new double[]{10, 20, 50});
        assertEquals(2.0, s1, 1e-9);

        // 数据:[5, 5, 5],max-min=0,summary=0
        double s2 = SensitivityAnalysisService.summarySensitivity(new double[]{5, 5, 5});
        assertEquals(0.0, s2, 1e-9);

        // 数据:[1],单点 max=min=1,summary=0
        double s3 = SensitivityAnalysisService.summarySensitivity(new double[]{1});
        assertEquals(0.0, s3, 1e-9);

        // 数据:[0, 0, 0] 中心为 0,denom = 1e-9,(0-0)/1e-9 = 0
        double s4 = SensitivityAnalysisService.summarySensitivity(new double[]{0, 0, 0});
        assertEquals(0.0, s4, 1e-9);

        // 数据:[-1, 0, 1] center=0,denom=1e-9,(1-(-1))/1e-9 = 2e9
        double s5 = SensitivityAnalysisService.summarySensitivity(new double[]{-1, 0, 1});
        assertEquals(2e9, s5, 1.0);
    }

    @Test
    void baseConfigNotMutatedAfterRun() {
        SimConfig original = staticSplitConfig();
        double originalArrivalRate = original.getArrivalRate();
        int originalWindowCount = original.getBaseConfig().getWindowCount();

        SensitivityRequest req = new SensitivityRequest(original,
                List.of(new ScanAxis(WhitelistedParam.ARRIVAL_RATE, points(30, 90, 120))),
                seeds(1L));
        req.setRunId("no-mutate");
        service.run(req);

        assertEquals(originalArrivalRate, original.getArrivalRate(), 0.0,
                "baseConfig.arrivalRate 必须保持调用前值");
        assertEquals(originalWindowCount, original.getBaseConfig().getWindowCount(),
                "baseConfig.windowCount 必须保持调用前值");
        assertEquals(45, original.getRandomBounds().getServiceRange().get(0).intValue(),
                "baseConfig.serviceRange 也必须保持 baseline");
    }
}
