package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.BottleneckDiagnosis;
import com.bjtu.simulation.dto.BottleneckEvidence;
import com.bjtu.simulation.dto.BottleneckSeverity;
import com.bjtu.simulation.dto.BottleneckType;
import com.bjtu.simulation.dto.DetectedBottleneck;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationSummary;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * RFC-012:T-12-1 ~ T-12-19。
 *
 * <p>合成 {@link SimulationSummary} + {@link SimConfig},直接验证
 * {@link BottleneckAnalyzer#analyze} 在所有规则路径下的行为,守:</p>
 * <ul>
 *   <li>severity 边界手算(0.85/0.90/0.95)</li>
 *   <li>4 类公式正确性(尤其 ARRIVAL 分母 windowCount × queueLimit)</li>
 *   <li>排序规则:severity desc → enum 序 tiebreak</li>
 *   <li>BALANCED 兜底:primary=BALANCED, secondary=null, bottlenecks=[]</li>
 *   <li>evidence 4 字段结构</li>
 *   <li>null / 空 / 长度不匹配的防御式守护</li>
 *   <li>同输入两次 analyze 字节级一致(确定性)</li>
 *   <li>enum 经 {@code @JsonValue} 输出 lower_snake_case(T-12-17)</li>
 *   <li>meanService 三段兜底(T-12-18)</li>
 *   <li>windowTypes 长度不匹配 / null 不抛(T-12-19)</li>
 * </ul>
 */
class BottleneckAnalyzerTest {

    private final BottleneckAnalyzer analyzer = new BottleneckAnalyzer();

    private SimConfig makeConfig(int windowCount, int queueLimit, List<Integer> serviceRange) {
        SimConfig c = new SimConfig();
        c.setQueueLimit(queueLimit);
        c.getBaseConfig().setWindowCount(windowCount);
        if (serviceRange != null) {
            c.getRandomBounds().setServiceRange(new ArrayList<>(serviceRange));
        } else {
            c.getRandomBounds().setServiceRange(null);
        }
        return c;
    }

    /**
     * 合成最小 {@link SimulationSummary}:只控制 analyzer 实际读的 6 个字段,
     * 其它全部填 0 / 空 list / null。委派 64 参构造器(自动补 0.0/null 给 67 参重载)。
     */
    private SimulationSummary makeSummary(long endSeconds,
                                          List<Integer> servedCounts,
                                          List<String> windowTypes,
                                          double seatUtil,
                                          int totalSeats,
                                          int maxTotalQueueSize) {
        return new SimulationSummary(
                List.of(), List.of(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0.0, 0.0, null,
                0.0, 0.0, 0,
                0L, 0L,
                0, maxTotalQueueSize, 0.0,
                0, 0.0, seatUtil,
                servedCounts, windowTypes,
                0, 0, 0, 0,
                0.0, 0.0, 0.0, 0.0, 0.0,
                endSeconds, 0L,
                totalSeats, 0, 0,
                List.of(), List.of(), List.of(), List.of(),
                null,
                0, 0, 0.0, 0.0, 0.0,
                0, 0.0, 0,
                0.0, 0.0,
                0.0, 0.0, 0.0, 0.0);
    }

    // ---- T-12-1 ----

    @Test
    void t12_1_windowServiceLowSeverityBoundaries() {
        // meanService=120s(serviceRange=[60,180] 中点),endSec=2400
        // served=20 → util=20*120/2400=1.0 → HIGH
        // served=18 → util=0.9 → MEDIUM
        // served=17 → util=0.85 → LOW
        SimConfig config = makeConfig(1, 10, List.of(60, 180));

        SimulationSummary high = makeSummary(2400L, List.of(20),
                List.of("NORMAL"), 0.0, 0, 0);
        BottleneckDiagnosis dHigh = analyzer.analyze(high, config);
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, dHigh.getPrimary());
        DetectedBottleneck b = dHigh.getBottlenecks().get(0);
        assertEquals(BottleneckSeverity.HIGH, b.getSeverity());
        BottleneckEvidence ev = b.getEvidence();
        assertEquals("windowUtilizationMax", ev.getMetricName());
        assertEquals(1.0, ev.getObservedValue(), 1e-3);
        assertEquals(0.85, ev.getThreshold(), 1e-9);
        assertEquals(0, ev.getWindowId());

        SimulationSummary mid = makeSummary(2400L, List.of(18),
                List.of("NORMAL"), 0.0, 0, 0);
        assertEquals(BottleneckSeverity.MEDIUM,
                analyzer.analyze(mid, config).getBottlenecks().get(0).getSeverity());

        SimulationSummary low = makeSummary(2400L, List.of(17),
                List.of("NORMAL"), 0.0, 0, 0);
        assertEquals(BottleneckSeverity.LOW,
                analyzer.analyze(low, config).getBottlenecks().get(0).getSeverity());
    }

    // ---- T-12-2 ----

    @Test
    void t12_2_windowServiceBelowTrigger() {
        // util = 17*120/2425 ≈ 0.8412 → 0.84xx < 0.85 → 不触发
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2425L, List.of(17),
                List.of("NORMAL"), 0.0, 0, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.BALANCED, d.getPrimary());
        assertNull(d.getSecondary());
        assertTrue(d.getBottlenecks().isEmpty());
    }

    // ---- T-12-3 ----

    @Test
    void t12_3_seatCapacityHighSeverity() {
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(0L, List.of(), List.of(), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.SEAT_CAPACITY, d.getPrimary());
        DetectedBottleneck b = d.getBottlenecks().get(0);
        assertEquals(BottleneckSeverity.HIGH, b.getSeverity());
        assertEquals("seatUtilizationRate", b.getEvidence().getMetricName());
        assertEquals(-1, b.getEvidence().getWindowId());
    }

    // ---- T-12-4 ----

    @Test
    void t12_4_seatBelowTrigger() {
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(0L, List.of(), List.of(), 0.5, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.BALANCED, d.getPrimary());
        assertTrue(d.getBottlenecks().isEmpty());
    }

    // ---- T-12-5 ----

    @Test
    void t12_5_takeawayCapacityMedium() {
        // 1 个 TAKEAWAY 窗口 served=18 → util=18*120/2400=0.9 → MEDIUM
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(18),
                List.of("TAKEAWAY"), 0.0, 0, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.TAKEAWAY_CAPACITY, d.getPrimary());
        DetectedBottleneck b = d.getBottlenecks().get(0);
        assertEquals(BottleneckSeverity.MEDIUM, b.getSeverity());
        assertEquals("takeawayWindowUtilizationMax", b.getEvidence().getMetricName());
        assertEquals(0, b.getEvidence().getWindowId());
    }

    // ---- T-12-6 ----

    @Test
    void t12_6_takeawayNoTakeawayWindow() {
        // 全 NORMAL 窗口,SEAT 触发 → 只输出 SEAT,不输出 TAKEAWAY
        SimConfig config = makeConfig(2, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(10, 10),
                List.of("NORMAL", "NORMAL"), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.SEAT_CAPACITY, d.getPrimary());
        for (DetectedBottleneck b : d.getBottlenecks()) {
            assertNotEquals(BottleneckType.TAKEAWAY_CAPACITY, b.getType());
        }
    }

    // ---- T-12-7 ----

    @Test
    void t12_7_arrivalSurgeHigh() {
        // maxTotalQueueSize=100, windowCount=5, queueLimit=20 → pressure=100/(5*20)=1.0 → HIGH
        SimConfig config = makeConfig(5, 20, List.of(60, 180));
        SimulationSummary s = makeSummary(0L, List.of(), List.of(), 0.0, 0, 100);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.ARRIVAL_SURGE, d.getPrimary());
        DetectedBottleneck b = d.getBottlenecks().get(0);
        assertEquals(BottleneckSeverity.HIGH, b.getSeverity());
        assertEquals("queuePressureMax", b.getEvidence().getMetricName());
        assertEquals(-1, b.getEvidence().getWindowId());
        assertEquals(1.0, b.getEvidence().getObservedValue(), 1e-9);
    }

    // ---- T-12-8 ----

    @Test
    void t12_8_arrivalSurgeFormulaUsesWindowCountTimesQueueLimit() {
        // 守 ARRIVAL 分母为 windowCount × queueLimit(本 RFC 修订),非 v2 §4 的 queueLimit
        // maxTotalQueueSize=20, windowCount=5, queueLimit=20
        // 修订式:20/(5*20)=0.2 → 不触发
        // v2 误式:20/20=1.0 → 会触发(本测试守)
        SimConfig config = makeConfig(5, 20, List.of(60, 180));
        SimulationSummary s = makeSummary(0L, List.of(), List.of(), 0.0, 0, 20);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.BALANCED, d.getPrimary(),
                "ARRIVAL 分母必须是 windowCount × queueLimit;若误用 queueLimit 单独作分母会触发本断言");
    }

    // ---- T-12-9 ----

    @Test
    void t12_9_multipleBottlenecksSorted() {
        // WINDOW util=14.55*120/2050=0.851 LOW + SEAT 0.97 HIGH
        // bottlenecks[0]=SEAT HIGH, bottlenecks[1]=WINDOW LOW
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        // util = 17*120/2400 = 0.85 → LOW
        SimulationSummary s = makeSummary(2400L, List.of(17),
                List.of("NORMAL"), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(2, d.getBottlenecks().size());
        assertEquals(BottleneckType.SEAT_CAPACITY, d.getPrimary());
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d.getSecondary());
        assertEquals(BottleneckSeverity.HIGH, d.getBottlenecks().get(0).getSeverity());
        assertEquals(BottleneckSeverity.LOW, d.getBottlenecks().get(1).getSeverity());
    }

    // ---- T-12-10 ----

    @Test
    void t12_10_sameSeverityEnumOrder() {
        // WINDOW util=18*120/2400=0.9 MEDIUM + SEAT 0.92 MEDIUM
        // 同 severity 按 enum 序:WINDOW > SEAT
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(18),
                List.of("NORMAL"), 0.92, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(2, d.getBottlenecks().size());
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d.getPrimary());
        assertEquals(BottleneckType.SEAT_CAPACITY, d.getSecondary());
        assertEquals(BottleneckSeverity.MEDIUM, d.getBottlenecks().get(0).getSeverity());
        assertEquals(BottleneckSeverity.MEDIUM, d.getBottlenecks().get(1).getSeverity());
    }

    // ---- T-12-11 ----

    @Test
    void t12_11_balancedAllUnderTrigger() {
        SimConfig config = makeConfig(5, 20, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(10, 10, 10, 10, 10),
                List.of("NORMAL", "NORMAL", "NORMAL", "NORMAL", "TAKEAWAY"),
                0.5, 80, 10);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.BALANCED, d.getPrimary());
        assertNull(d.getSecondary());
        assertTrue(d.getBottlenecks().isEmpty());
    }

    // ---- T-12-12 ----

    @Test
    void t12_12_evidenceShape() {
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(20),
                List.of("NORMAL"), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        for (DetectedBottleneck b : d.getBottlenecks()) {
            BottleneckEvidence ev = b.getEvidence();
            assertNotNull(ev.getMetricName());
            assertFalse(ev.getMetricName().isBlank());
            assertEquals(0.85, ev.getThreshold(), 1e-9);
            // observedValue >= threshold(否则不应触发)
            assertTrue(ev.getObservedValue() >= 0.85 - 1e-9);
        }
    }

    // ---- T-12-13 ----

    @Test
    void t12_13_nullSummaryFallsBackToBalanced() {
        BottleneckDiagnosis d = analyzer.analyze(null, makeConfig(1, 10, List.of(60, 180)));
        assertEquals(BottleneckType.BALANCED, d.getPrimary());
        assertNull(d.getSecondary());
        assertTrue(d.getBottlenecks().isEmpty());
    }

    // ---- T-12-14 ----

    @Test
    void t12_14_nullConfigFallsBackToBalanced() {
        SimulationSummary s = makeSummary(2400L, List.of(20),
                List.of("NORMAL"), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, null);
        assertEquals(BottleneckType.BALANCED, d.getPrimary());
        assertTrue(d.getBottlenecks().isEmpty());
    }

    // ---- T-12-15 ----

    @Test
    void t12_15_zeroDurationSkipsWindowAndTakeaway() {
        SimConfig config = makeConfig(1, 10, List.of(60, 180));
        // endSec=0 → WINDOW / TAKEAWAY 跳过;SEAT 0.97 仍触发
        SimulationSummary s = makeSummary(0L, List.of(20),
                List.of("NORMAL"), 0.97, 80, 0);
        BottleneckDiagnosis d = analyzer.analyze(s, config);
        assertEquals(BottleneckType.SEAT_CAPACITY, d.getPrimary());
        assertEquals(1, d.getBottlenecks().size());
    }

    // ---- T-12-16 ----

    @Test
    void t12_16_deterministic() {
        SimConfig config = makeConfig(2, 10, List.of(60, 180));
        SimulationSummary s = makeSummary(2400L, List.of(20, 18),
                List.of("NORMAL", "TAKEAWAY"), 0.97, 80, 30);
        BottleneckDiagnosis d1 = analyzer.analyze(s, config);
        BottleneckDiagnosis d2 = analyzer.analyze(s, config);
        assertEquals(d1.getPrimary(), d2.getPrimary());
        assertEquals(d1.getSecondary(), d2.getSecondary());
        assertEquals(d1.getBottlenecks().size(), d2.getBottlenecks().size());
        for (int i = 0; i < d1.getBottlenecks().size(); i++) {
            DetectedBottleneck a = d1.getBottlenecks().get(i);
            DetectedBottleneck b = d2.getBottlenecks().get(i);
            assertEquals(a.getType(), b.getType());
            assertEquals(a.getSeverity(), b.getSeverity());
            assertEquals(a.getEvidence().getMetricName(), b.getEvidence().getMetricName());
            assertEquals(a.getEvidence().getObservedValue(), b.getEvidence().getObservedValue(), 0.0);
            assertEquals(a.getEvidence().getThreshold(), b.getEvidence().getThreshold(), 0.0);
            assertEquals(a.getEvidence().getWindowId(), b.getEvidence().getWindowId());
        }
    }

    // ---- T-12-17 ----

    @Test
    void t12_17_enumJsonValueLowerSnakeCase() throws Exception {
        ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();
        SimConfig config = makeConfig(1, 10, List.of(60, 180));

        // BALANCED:含 primary=balanced, bottlenecks=[],无 secondary 键
        SimulationSummary balanced = makeSummary(2400L, List.of(10),
                List.of("NORMAL"), 0.5, 80, 0);
        BottleneckDiagnosis dBal = analyzer.analyze(balanced, config);
        String jsonBal = mapper.writeValueAsString(dBal);
        assertTrue(jsonBal.contains("\"primary\":\"balanced\""), "BALANCED 应输出 primary:balanced,实际:" + jsonBal);
        assertFalse(jsonBal.contains("\"secondary\""), "BALANCED 时 secondary 应被 NON_NULL 省略");

        // SEAT_CAPACITY HIGH:primary=seat_capacity, severity=high, metric_name 原样
        SimulationSummary seat = makeSummary(0L, List.of(), List.of(), 0.97, 80, 0);
        BottleneckDiagnosis dSeat = analyzer.analyze(seat, config);
        String jsonSeat = mapper.writeValueAsString(dSeat);
        assertTrue(jsonSeat.contains("\"primary\":\"seat_capacity\""), jsonSeat);
        assertTrue(jsonSeat.contains("\"severity\":\"high\""), jsonSeat);
        assertTrue(jsonSeat.contains("\"metric_name\":\"seatUtilizationRate\""), jsonSeat);
        assertTrue(jsonSeat.contains("\"window_id\":-1"), jsonSeat);

        // WINDOW_SERVICE_CAPACITY MEDIUM
        SimulationSummary win = makeSummary(2400L, List.of(18),
                List.of("NORMAL"), 0.0, 0, 0);
        BottleneckDiagnosis dWin = analyzer.analyze(win, config);
        String jsonWin = mapper.writeValueAsString(dWin);
        assertTrue(jsonWin.contains("\"primary\":\"window_service_capacity\""), jsonWin);
        assertTrue(jsonWin.contains("\"severity\":\"medium\""), jsonWin);

        // TAKEAWAY_CAPACITY LOW:util=17*120/2400=0.85
        SimulationSummary tk = makeSummary(2400L, List.of(17),
                List.of("TAKEAWAY"), 0.0, 0, 0);
        BottleneckDiagnosis dTk = analyzer.analyze(tk, config);
        String jsonTk = mapper.writeValueAsString(dTk);
        assertTrue(jsonTk.contains("\"primary\":\"takeaway_capacity\""), jsonTk);
        assertTrue(jsonTk.contains("\"severity\":\"low\""), jsonTk);

        // ARRIVAL_SURGE HIGH:metric_name=queuePressureMax
        SimConfig bigConfig = makeConfig(5, 20, List.of(60, 180));
        SimulationSummary arr = makeSummary(0L, List.of(), List.of(), 0.0, 0, 100);
        BottleneckDiagnosis dArr = analyzer.analyze(arr, bigConfig);
        String jsonArr = mapper.writeValueAsString(dArr);
        assertTrue(jsonArr.contains("\"primary\":\"arrival_surge\""), jsonArr);
        assertTrue(jsonArr.contains("\"metric_name\":\"queuePressureMax\""), jsonArr);
    }

    // ---- T-12-18 ----

    @Test
    void t12_18_meanServiceSecondsFallbackChain() {
        // serviceRange=null → 走 (45+180)/2=112.5s 兜底
        // served=10, endSec=1125 → util=10*112.5/1125=1.0 → HIGH
        // 若错走 0 → util=0 不触发,本断言会失败
        SimConfig nullRange = makeConfig(1, 10, null);
        SimulationSummary s1 = makeSummary(1125L, List.of(10),
                List.of("NORMAL"), 0.0, 0, 0);
        BottleneckDiagnosis d1 = analyzer.analyze(s1, nullRange);
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d1.getPrimary(),
                "serviceRange=null 应回退到 (45+180)/2=112.5s,util=10*112.5/1125=1.0 → HIGH");
        assertEquals(BottleneckSeverity.HIGH, d1.getBottlenecks().get(0).getSeverity());

        // serviceRange=空列表 → 同样兜底
        SimConfig emptyRange = makeConfig(1, 10, List.of());
        BottleneckDiagnosis d2 = analyzer.analyze(s1, emptyRange);
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d2.getPrimary(),
                "serviceRange=[] 应回退到 (45+180)/2=112.5s 兜底");

        // serviceRange size<2 → 同样兜底
        SimConfig oneElemRange = makeConfig(1, 10, List.of(60));
        BottleneckDiagnosis d3 = analyzer.analyze(s1, oneElemRange);
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d3.getPrimary(),
                "serviceRange.size=1 应回退到 (45+180)/2=112.5s 兜底");

        // randomBounds=null → 同样兜底
        SimConfig noBounds = new SimConfig();
        noBounds.setQueueLimit(10);
        noBounds.getBaseConfig().setWindowCount(1);
        noBounds.setRandomBounds(null);
        BottleneckDiagnosis d4 = analyzer.analyze(s1, noBounds);
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d4.getPrimary(),
                "randomBounds=null 应回退到 (45+180)/2=112.5s 兜底");
    }

    // ---- T-12-19 ----

    @Test
    void t12_19_windowTypesLengthMismatchAndNull() {
        // size mismatch:windowServedCounts=[20,18,17], windowTypes=[NORMAL,NORMAL]
        // → 按 min(3,2)=2 遍历:前 2 个非 TAKEAWAY,第 3 个跳过
        // util_0 = 20*120/2400 = 1.0,util_1 = 18*120/2400 = 0.9
        // max=1.0 → HIGH on window 0,不抛 IndexOutOfBoundsException
        SimConfig config = makeConfig(3, 10, List.of(60, 180));
        SimulationSummary mismatch = makeSummary(2400L, List.of(20, 18, 17),
                Arrays.asList("NORMAL", "NORMAL"), 0.0, 0, 0);
        BottleneckDiagnosis d = assertDoesNotThrow(() -> analyzer.analyze(mismatch, config));
        assertEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, d.getPrimary());
        assertEquals(0, d.getBottlenecks().get(0).getEvidence().getWindowId());

        // windowTypes=null → WINDOW / TAKEAWAY 跳过(无类型信息保守不触发)
        // SEAT 0.97 仍正常诊断
        SimulationSummary nullTypes = makeSummary(2400L, List.of(20, 18, 17),
                null, 0.97, 80, 0);
        BottleneckDiagnosis dNull = assertDoesNotThrow(() -> analyzer.analyze(nullTypes, config));
        assertEquals(BottleneckType.SEAT_CAPACITY, dNull.getPrimary());
        for (DetectedBottleneck b : dNull.getBottlenecks()) {
            assertNotEquals(BottleneckType.WINDOW_SERVICE_CAPACITY, b.getType(),
                    "windowTypes=null 时 WINDOW 必须跳过");
            assertNotEquals(BottleneckType.TAKEAWAY_CAPACITY, b.getType(),
                    "windowTypes=null 时 TAKEAWAY 必须跳过");
        }

        // windowTypes 含 null 元素 → 该索引按非 TAKEAWAY 处理(不抛 NPE)
        SimulationSummary withNull = makeSummary(2400L, List.of(20),
                Arrays.asList((String) null), 0.0, 0, 0);
        BottleneckDiagnosis dWithNull = assertDoesNotThrow(() -> analyzer.analyze(withNull, config));
        assertSame(BottleneckType.WINDOW_SERVICE_CAPACITY, dWithNull.getPrimary(),
                "windowTypes 含 null 元素时 idx 应按 NORMAL 处理,不抛 NPE");
    }
}
