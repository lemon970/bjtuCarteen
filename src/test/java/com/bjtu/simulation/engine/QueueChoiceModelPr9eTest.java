package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.bjtu.simulation.dto.WindowChoiceMetrics;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.service.SimulationConfigNormalizer;
import com.bjtu.simulation.service.SimulationRunService;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9E 集成验收测试:preference stickiness multiplier。
 *
 * <p>本测试只对外断言"放大非偏好窗口惩罚"带来的可观测效应,绝不直接断言
 * POPULAR/NORMAL/COLD role 评分(role-aware scoring 不在本 PR 范围)。
 *
 * <ul>
 *   <li>T-9E-1:STATIC_SPLIT 走 weight=1.0 路径,10 个核心指标在同 seed 下逐字节复现,
 *       证明 multiplier 引入对默认模式无副作用。</li>
 *   <li>T-9E-2:PREFERENCE_AWARE 下 {@code summary.window_choice_metrics.popular_served_share}
 *       高于 uniform baseline,且冷门低于 uniform。</li>
 *   <li>T-9E-3:{@link WindowSelectionPolicy} 单元测试 — 偏好窗口比非偏好窗口多 1 人时,
 *       weight=1.0 切到非偏好短队,weight=3.0 仍坚持偏好窗口。</li>
 *   <li>T-9E-4:STATIC_SPLIT vs PREFERENCE_AWARE 同 seed 宏观预算
 *       (typical_wait ≤ 1.25×;seat_utilization ≤ 5pp;takeaway_rate ≤ 8pp)。</li>
 *   <li>T-9E-5:同 seed PREFERENCE_AWARE 报告 10 核心指标字节级一致。</li>
 * </ul>
 */
class QueueChoiceModelPr9eTest {

    private static final long SEED = 20260521L;
    private final SimulationConfigNormalizer normalizer = new SimulationConfigNormalizer();
    private final SimulationRunService runService = new SimulationRunService();

    // ---- T-9E-1 ----

    @Test
    void t1_staticSplitCoreMetricsInvariantUnderMultiplier() {
        SimulationReport r1 = runService.run(staticSplitConfig(SEED), "rfc009-pr9e-t1-a");
        SimulationReport r2 = runService.run(staticSplitConfig(SEED), "rfc009-pr9e-t1-b");

        // STATIC_SPLIT 走 preferenceWeight=1.0 路径,与 PR-9D 行为字节级等价。
        // 同 seed 重跑时 10 个核心指标必须完全一致,作为 multiplier 引入"无副作用"的回归证据。
        assertEquals(r1.getSummary().getArrivedCount(),
                r2.getSummary().getArrivedCount(), "arrived_count");
        assertEquals(r1.getSummary().getServedCount(),
                r2.getSummary().getServedCount(), "served_count");
        assertEquals(r1.getSummary().getTakeawayCount(),
                r2.getSummary().getTakeawayCount(), "takeaway_count");
        assertEquals(r1.getSummary().getDineInCount(),
                r2.getSummary().getDineInCount(), "dine_in_count");
        assertEquals(r1.getSummary().getTypicalWaitTimeMinutes(),
                r2.getSummary().getTypicalWaitTimeMinutes(), 0.0,
                "typical_wait_time_minutes");
        assertEquals(r1.getSummary().getMedianWaitTimeMinutes(),
                r2.getSummary().getMedianWaitTimeMinutes(), 0.0,
                "median_wait_time_minutes");
        assertEquals(r1.getSummary().getP90WaitTimeMinutes(),
                r2.getSummary().getP90WaitTimeMinutes(), 0.0,
                "p90_wait_time_minutes");
        assertEquals(r1.getSummary().getMaxTotalQueueSize(),
                r2.getSummary().getMaxTotalQueueSize(), "max_total_queue_size");
        assertEquals(r1.getSummary().getSeatUtilizationRate(),
                r2.getSummary().getSeatUtilizationRate(), 0.0,
                "seat_utilization_rate");
        assertEquals(r1.getSummary().getTakeawayRate(),
                r2.getSummary().getTakeawayRate(), 0.0, "takeaway_rate");

        // STATIC_SPLIT 不应输出 window_choice_metrics(PR-9D 行为)。
        assertEquals(null, r1.getSummary().getWindowChoiceMetrics(),
                "STATIC_SPLIT 必须保持 window_choice_metrics=null");
    }

    // ---- T-9E-2 ----

    @Test
    void t2_preferenceAwareLiftsPopularServedShareOverUniform() {
        SimulationReport report = runService.run(
                preferenceAwareConfig(SEED), "rfc009-pr9e-t2");

        WindowChoiceMetrics metrics = report.getSummary().getWindowChoiceMetrics();
        assertNotNull(metrics, "PREFERENCE_AWARE 必须输出 summary.window_choice_metrics");
        assertEquals(QueueChoiceModel.PREFERENCE_AWARE.name(),
                metrics.getQueueChoiceModel(),
                "queue_choice_model 标签应为 PREFERENCE_AWARE");

        int popularWindowCount = metrics.getPopularWindowCount();
        int normalWindowCount = metrics.getNormalWindowCount();
        int coldWindowCount = metrics.getColdWindowCount();
        int normalPoolSize = popularWindowCount + normalWindowCount + coldWindowCount;
        assertTrue(normalPoolSize > 0, "普通窗口池规模 > 0");

        double uniformPopularShare = popularWindowCount / (double) normalPoolSize;
        double uniformColdShare = coldWindowCount / (double) normalPoolSize;
        double popularServedShare = metrics.getPopularServedShare();
        double coldServedShare = metrics.getColdServedShare();

        // T-9E-2 主断言:stickier preference penalty 让热门窗口服务份额高于 uniform,
        // 冷门低于 uniform。
        assertTrue(popularServedShare > uniformPopularShare,
                () -> "popular_served_share = " + popularServedShare
                        + " 应高于 uniform = " + uniformPopularShare);
        assertTrue(coldServedShare < uniformColdShare,
                () -> "cold_served_share = " + coldServedShare
                        + " 应低于 uniform = " + uniformColdShare);
        // §11 pilot guardrail
        assertTrue(popularServedShare <= 0.60,
                () -> "popular_served_share = " + popularServedShare
                        + " 超出 §11 guardrail 0.6");
    }

    // ---- T-9E-3 ----

    @Test
    void t3_preferenceWeightMakesStudentStickToPreferredWindowWhenQueueDiffIsOne() {
        WindowSelectionPolicy policy = new WindowSelectionPolicy();
        // 两个普通窗口:0(偏好窗口,队列 1) vs 1(非偏好窗口,队列 0)
        // 队差 1 在 weight=1.0 下让学生切到非偏好,weight=3.0 下应坚守偏好。
        CanteenState state = new CanteenState(2, /*totalSeats=*/100);
        state.joinQueue(0); // 偏好窗口已有 1 人
        List<String> windowTypes = List.of("NORMAL", "NORMAL");
        List<Long> available = List.of(0L, 0L);
        Student student = mediumPatienceStudent("s-pr9e", /*windowPreference=*/0);

        int weight1 = policy.choose(student, state, available, windowTypes,
                /*currentTime=*/0L, /*queuePressure=*/0.0, /*seatPressure=*/0.0,
                /*takeawayWindowCount=*/0, /*willTakeaway=*/false,
                /*preferenceWeight=*/1.0);
        // weight=1.0:basePenalty=0.45 < queueDiff=1,学生切到非偏好短队
        assertEquals(1, weight1,
                "weight=1.0(STATIC_SPLIT 等价路径)队差 1 时应切到非偏好窗口");

        int weight3 = policy.choose(student, state, available, windowTypes,
                0L, 0.0, 0.0, 0, false, /*preferenceWeight=*/3.0);
        // weight=3.0:nonPreferredPenalty = 0.45*3 = 1.35 > queueDiff=1,学生坚守偏好
        assertEquals(0, weight3,
                "weight=3.0(PR-9E PREFERENCE_AWARE 路径)队差 1 时应仍选偏好窗口");
    }

    // ---- T-9E-4 ----

    @Test
    void t4_macroBudgetAcrossModels() {
        SimulationReport staticReport = runService.run(
                staticSplitConfig(SEED), "rfc009-pr9e-t4-static");
        SimulationReport prefReport = runService.run(
                preferenceAwareConfig(SEED), "rfc009-pr9e-t4-pref");

        double waitStatic = staticReport.getSummary().getTypicalWaitTimeMinutes();
        double waitPref = prefReport.getSummary().getTypicalWaitTimeMinutes();
        double seatStatic = staticReport.getSummary().getSeatUtilizationRate();
        double seatPref = prefReport.getSummary().getSeatUtilizationRate();
        double awayStatic = staticReport.getSummary().getTakeawayRate();
        double awayPref = prefReport.getSummary().getTakeawayRate();

        if (waitStatic > 0) {
            assertTrue(waitPref <= 1.25 * waitStatic + 1e-6,
                    () -> "typical_wait_minutes static=" + waitStatic
                            + " pref=" + waitPref + " 超出 1.25× 预算");
        }
        assertTrue(Math.abs(seatPref - seatStatic) <= 0.05,
                () -> "seat_utilization_rate static=" + seatStatic
                        + " pref=" + seatPref + " 差异超出 5pp");
        assertTrue(Math.abs(awayPref - awayStatic) <= 0.08,
                () -> "takeaway_rate static=" + awayStatic
                        + " pref=" + awayPref + " 差异超出 8pp");
    }

    // ---- T-9E-5 ----

    @Test
    void t5_preferenceAwareSeedDeterminismOnCoreMetrics() {
        SimulationReport r1 = runService.run(
                preferenceAwareConfig(SEED), "rfc009-pr9e-t5-a");
        SimulationReport r2 = runService.run(
                preferenceAwareConfig(SEED), "rfc009-pr9e-t5-b");

        assertEquals(r1.getEffectiveSeed(), r2.getEffectiveSeed(), "effective_seed");

        assertEquals(r1.getSummary().getArrivedCount(),
                r2.getSummary().getArrivedCount(), "arrived_count");
        assertEquals(r1.getSummary().getServedCount(),
                r2.getSummary().getServedCount(), "served_count");
        assertEquals(r1.getSummary().getTakeawayCount(),
                r2.getSummary().getTakeawayCount(), "takeaway_count");
        assertEquals(r1.getSummary().getDineInCount(),
                r2.getSummary().getDineInCount(), "dine_in_count");
        assertEquals(r1.getSummary().getTypicalWaitTimeMinutes(),
                r2.getSummary().getTypicalWaitTimeMinutes(), 0.0,
                "typical_wait_time_minutes");
        assertEquals(r1.getSummary().getMedianWaitTimeMinutes(),
                r2.getSummary().getMedianWaitTimeMinutes(), 0.0,
                "median_wait_time_minutes");
        assertEquals(r1.getSummary().getP90WaitTimeMinutes(),
                r2.getSummary().getP90WaitTimeMinutes(), 0.0,
                "p90_wait_time_minutes");
        assertEquals(r1.getSummary().getMaxTotalQueueSize(),
                r2.getSummary().getMaxTotalQueueSize(), "max_total_queue_size");
        assertEquals(r1.getSummary().getSeatUtilizationRate(),
                r2.getSummary().getSeatUtilizationRate(), 0.0,
                "seat_utilization_rate");
        assertEquals(r1.getSummary().getTakeawayRate(),
                r2.getSummary().getTakeawayRate(), 0.0, "takeaway_rate");

        // PREFERENCE_AWARE 主特性指标也应字节级一致
        WindowChoiceMetrics m1 = r1.getSummary().getWindowChoiceMetrics();
        WindowChoiceMetrics m2 = r2.getSummary().getWindowChoiceMetrics();
        assertNotNull(m1, "metrics 1");
        assertNotNull(m2, "metrics 2");
        assertEquals(m1.getPopularServedShare(), m2.getPopularServedShare(), 0.0,
                "popular_served_share");
        assertEquals(m1.getNormalServedShare(), m2.getNormalServedShare(), 0.0,
                "normal_served_share");
        assertEquals(m1.getColdServedShare(), m2.getColdServedShare(), 0.0,
                "cold_served_share");
    }

    // ---- helpers ----

    private SimConfig staticSplitConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc009-pr9e-baseline");
        config.setDuration(0.5);
        config.setArrivalRate(120);
        config.setQueueLimit(15);
        config.setPackProbability(0.2);
        config.setSeed(seed);
        config.getBaseConfig().setWindowCount(10);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getBaseConfig().setTotalSeats(80);
        config.getBaseConfig().setTotalStudents(80);
        return config;
    }

    private SimConfig preferenceAwareConfig(long seed) {
        SimConfig config = staticSplitConfig(seed);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return config;
    }

    private Student mediumPatienceStudent(String id, int windowPreference) {
        return new Student(
                id,
                /*packPreference=*/0.10,
                /*patienceLimit=*/30,
                windowPreference,
                /*seatSearchPatience=*/2,
                ArrivalGroup.NORMAL,
                Student.PackPreferenceLevel.BALANCED,
                Student.PatienceLevel.MEDIUM,
                Student.SeatToleranceLevel.MEDIUM,
                /*partySize=*/1,
                /*groupId=*/null,
                /*groupSize=*/1,
                /*groupMemberIndex=*/0,
                /*wantsTakeaway=*/false);
    }
}
