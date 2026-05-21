package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.service.SimulationConfigNormalizer;
import com.bjtu.simulation.service.SimulationRunService;

import org.junit.jupiter.api.Test;

/**
 * RFC-009 PR-9C 集成验收测试。
 *
 * <ul>
 *   <li>T2b: PREFERENCE_AWARE 集成链路下 windowPreference 分布方向正确(精度由
 *       {@link WindowAttractivenessSamplerTest} T2a 守住,本测试只断言方向 + sum≈1 + 复现)。</li>
 *   <li>T3 : 热门窗口服务占比 > uniform 基线,且不超过 §11 pilot guardrail 0.6。</li>
 *   <li>T4 : STATIC_SPLIT vs PREFERENCE_AWARE 同 seed 宏观指标在预算内
 *       (typical_wait ≤ 1.25×;seat_utilization 差 ≤ 5pp;takeaway_rate 差 ≤ 8pp)。</li>
 *   <li>T6 : 同 seed PREFERENCE_AWARE 角色分配 + 权重 + 核心指标全部字节级一致。</li>
 * </ul>
 */
class QueueChoiceModelPr9cIntegrationTest {

    private final SimulationConfigNormalizer normalizer = new SimulationConfigNormalizer();
    private final SimulationRunService runService = new SimulationRunService();

    // ---- T2b ----

    @Test
    void t2b_preferenceAwarePreferenceShareLeansToPopular() {
        SimConfig normalized = normalizer.normalize(preferenceAwareConfig(20260521L));
        SimulationEngine engine = new SimulationEngine(normalized);

        List<WindowRole> roles = engine.getWindowRolesForTests();
        assertNotNull(roles, "PREFERENCE_AWARE 必须给出窗口角色分配");
        double[] weights = engine.getWindowChoiceWeightsForTests();
        assertNotNull(weights, "PREFERENCE_AWARE 必须给出加权权重数组");

        int popularWindowCount = 0;
        int normalRoleCount = 0;
        int coldWindowCount = 0;
        for (WindowRole r : roles) {
            switch (r) {
                case POPULAR -> popularWindowCount++;
                case NORMAL -> normalRoleCount++;
                case COLD -> coldWindowCount++;
                case TAKEAWAY -> { /* 不计入普通窗口池 */ }
            }
        }
        int normalWindowTotal = popularWindowCount + normalRoleCount + coldWindowCount;
        assertTrue(normalWindowTotal > 0, "config 必须至少有一个普通窗口");

        int n = 8000;
        for (int i = 0; i < n; i++) {
            engine.registerStudent("s" + i, ArrivalGroup.NORMAL, 1);
        }

        int popPref = 0;
        int normPref = 0;
        int coldPref = 0;
        for (int i = 0; i < n; i++) {
            int wp = engine.getStudent("s" + i).getWindowPreference();
            switch (roles.get(wp)) {
                case POPULAR -> popPref++;
                case NORMAL -> normPref++;
                case COLD -> coldPref++;
                case TAKEAWAY -> { /* 打包窗口落点不进入普通窗口池统计 */ }
            }
        }
        double normalGroupTotal = popPref + normPref + coldPref;
        assertTrue(normalGroupTotal > 0, "至少有一部分偏好落到普通窗口池");
        double popShare = popPref / normalGroupTotal;
        double normShare = normPref / normalGroupTotal;
        double coldShare = coldPref / normalGroupTotal;

        double uniformPopShare = popularWindowCount / (double) normalWindowTotal;
        double uniformColdShare = coldWindowCount / (double) normalWindowTotal;

        assertTrue(popShare > uniformPopShare,
                () -> "popular preference share = " + popShare
                        + " 应大于 uniform = " + uniformPopShare);
        assertTrue(coldShare < uniformColdShare,
                () -> "cold preference share = " + coldShare
                        + " 应小于 uniform = " + uniformColdShare);
        assertEquals(1.0, popShare + normShare + coldShare, 1e-9,
                "三类 preference share 之和必须为 1.0(普通窗口池内部归一)");

        // 同 seed 复现:相同分布
        SimulationEngine engine2 = new SimulationEngine(
                normalizer.normalize(preferenceAwareConfig(20260521L)));
        for (int i = 0; i < n; i++) {
            engine2.registerStudent("s" + i, ArrivalGroup.NORMAL, 1);
        }
        for (int i = 0; i < n; i++) {
            assertEquals(engine.getStudent("s" + i).getWindowPreference(),
                    engine2.getStudent("s" + i).getWindowPreference(),
                    "windowPreference 必须在同 seed 下逐学生可复现 i=" + i);
        }
    }

    // ---- T3 ----

    @Test
    void t3_preferenceAwarePopularServedShareRisesAndStaysWithinGuardrail() {
        SimConfig config = preferenceAwareConfig(20260521L);
        SimulationReport report = runService.run(config, "rfc009-pr9c-t3");

        WindowAttractivenessConfig attr =
                report.getConfig().getBaseConfig().getWindowAttractiveness();
        List<WindowRole> roles = WindowRoleAssigner.assign(
                report.getSummary().getWindowTypes(), attr, report.getEffectiveSeed());
        List<Integer> served = report.getSummary().getWindowServedCounts();

        long popServed = 0;
        long normServed = 0;
        long coldServed = 0;
        int popularWindowCount = 0;
        int normalRoleCount = 0;
        int coldWindowCount = 0;
        for (int i = 0; i < roles.size(); i++) {
            int s = served.get(i);
            switch (roles.get(i)) {
                case POPULAR -> {
                    popServed += s;
                    popularWindowCount++;
                }
                case NORMAL -> {
                    normServed += s;
                    normalRoleCount++;
                }
                case COLD -> {
                    coldServed += s;
                    coldWindowCount++;
                }
                case TAKEAWAY -> { /* 不进入普通窗口池统计 */ }
            }
        }
        double normalPoolServed = popServed + normServed + coldServed;
        assertTrue(normalPoolServed > 0,
                () -> "普通窗口池累计服务数应 > 0,实际 = " + normalPoolServed);

        int normalWindowTotal = popularWindowCount + normalRoleCount + coldWindowCount;
        double popServedShare = popServed / normalPoolServed;
        double uniformPopShare = popularWindowCount / (double) normalWindowTotal;

        assertTrue(popServedShare > uniformPopShare,
                () -> "popular_served_share = " + popServedShare
                        + " 应高于 uniform = " + uniformPopShare);
        assertTrue(popServedShare <= 0.60,
                () -> "popular_served_share = " + popServedShare
                        + " 超出 §11 pilot guardrail 0.6");
    }

    // ---- T4 ----

    @Test
    void t4_macroBudgetWithinTolerance() {
        long seed = 20260521L;

        SimulationReport staticReport = runService.run(
                baselineConfig(seed), "rfc009-pr9c-t4-static");
        SimulationReport prefReport = runService.run(
                preferenceAwareConfig(seed), "rfc009-pr9c-t4-pref");

        double waitStatic = staticReport.getSummary().getTypicalWaitTimeMinutes();
        double waitPref = prefReport.getSummary().getTypicalWaitTimeMinutes();
        double seatStatic = staticReport.getSummary().getSeatUtilizationRate();
        double seatPref = prefReport.getSummary().getSeatUtilizationRate();
        double awayStatic = staticReport.getSummary().getTakeawayRate();
        double awayPref = prefReport.getSummary().getTakeawayRate();

        // typical_wait_minutes:PREFERENCE_AWARE ≤ 1.25 × STATIC_SPLIT
        if (waitStatic > 0) {
            assertTrue(waitPref <= 1.25 * waitStatic + 1e-6,
                    () -> "typical_wait_minutes static=" + waitStatic
                            + " pref=" + waitPref + " 超出 1.25× 预算");
        }
        // seat_utilization_rate 差异 ≤ 5pp
        assertTrue(Math.abs(seatPref - seatStatic) <= 0.05,
                () -> "seat_utilization_rate static=" + seatStatic
                        + " pref=" + seatPref + " 差异超出 5pp");
        // takeaway_rate 差异 ≤ 8pp
        assertTrue(Math.abs(awayPref - awayStatic) <= 0.08,
                () -> "takeaway_rate static=" + awayStatic
                        + " pref=" + awayPref + " 差异超出 8pp");
    }

    // ---- T6 ----

    @Test
    void t6_preferenceAwareSeedDeterminism() {
        long seed = 20260521L;

        SimulationReport r1 = runService.run(preferenceAwareConfig(seed), "rfc009-pr9c-t6-a");
        SimulationReport r2 = runService.run(preferenceAwareConfig(seed), "rfc009-pr9c-t6-b");

        assertEquals(r1.getEffectiveSeed(), r2.getEffectiveSeed(), "effective_seed");

        // 角色分配 + 权重数组字节级一致
        WindowAttractivenessConfig attr1 =
                r1.getConfig().getBaseConfig().getWindowAttractiveness();
        WindowAttractivenessConfig attr2 =
                r2.getConfig().getBaseConfig().getWindowAttractiveness();
        List<WindowRole> roles1 = WindowRoleAssigner.assign(
                r1.getSummary().getWindowTypes(), attr1, r1.getEffectiveSeed());
        List<WindowRole> roles2 = WindowRoleAssigner.assign(
                r2.getSummary().getWindowTypes(), attr2, r2.getEffectiveSeed());
        assertEquals(roles1, roles2, "windowRoles 必须在同 seed 下一致");

        double[] w1 = WindowRoleAssigner.buildWeights(roles1, attr1);
        double[] w2 = WindowRoleAssigner.buildWeights(roles2, attr2);
        assertArrayEquals(w1, w2, 0.0, "windowChoiceWeights 必须字节级一致");

        // 核心指标字节级一致
        assertEquals(r1.getSummary().getServedCount(),
                r2.getSummary().getServedCount(), "served_count");
        assertEquals(r1.getSummary().getTakeawayCount(),
                r2.getSummary().getTakeawayCount(), "takeaway_count");
        assertEquals(r1.getSummary().getTypicalWaitTimeMinutes(),
                r2.getSummary().getTypicalWaitTimeMinutes(), 0.0,
                "typical_wait_time_minutes");
        assertEquals(r1.getSummary().getSeatUtilizationRate(),
                r2.getSummary().getSeatUtilizationRate(), 0.0,
                "seat_utilization_rate");
        assertEquals(r1.getSummary().getTakeawayRate(),
                r2.getSummary().getTakeawayRate(), 0.0, "takeaway_rate");
        assertEquals(r1.getSummary().getWindowServedCounts(),
                r2.getSummary().getWindowServedCounts(), "window_served_counts");
    }

    // ---- helpers ----

    /** 10 窗口(8 普通 + 2 打包),负载控制在偏好信号主导区(score 中 preferencePenalty>=queueSize)。 */
    private SimConfig baselineConfig(long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName("rfc009-pr9c-baseline");
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
        SimConfig config = baselineConfig(seed);
        config.getBaseConfig().setQueueChoiceModel(QueueChoiceModel.PREFERENCE_AWARE);
        config.getBaseConfig().setWindowAttractiveness(new WindowAttractivenessConfig());
        return config;
    }
}
