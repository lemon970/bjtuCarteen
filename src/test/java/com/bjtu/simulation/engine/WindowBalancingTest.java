package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.service.SimulationRunService;

import org.junit.jupiter.api.Test;

/**
 * 第七轮重构核心断言:删除 WindowSelectionPolicy 的 willTakeaway 硬路由后,
 * 高 packProb + 少量打包窗口场景下,打包窗口和普通窗口的负载应该自然分流,
 * 不会出现"打包窗口爆 30 人 + 普通窗口空"的极端不均衡。
 */
class WindowBalancingTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void highTakeawayDemandShouldSpilloverToNormalWindows() {
        SimConfig config = balancingConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();

        List<Integer> served = summary.getWindowServedCounts();
        List<String> types = summary.getWindowTypes();
        int takeawayMaxServed = 0;
        int normalMaxServed = 0;
        int totalServed = 0;
        for (int i = 0; i < served.size(); i++) {
            int s = served.get(i);
            totalServed += s;
            if ("TAKEAWAY".equalsIgnoreCase(types.get(i))) {
                takeawayMaxServed = Math.max(takeawayMaxServed, s);
            } else {
                normalMaxServed = Math.max(normalMaxServed, s);
            }
        }

        // 关键不变量:即使打包需求高,打包窗口的单窗口峰值不能远超普通窗口
        // 旧实现下打包窗口会 hard-route,峰值能达普通窗口的 5-10 倍。
        assertTrue(totalServed > 0, "totalServed=" + totalServed);
        assertTrue(takeawayMaxServed > 0, "takeawayMaxServed=" + takeawayMaxServed);
        assertTrue(normalMaxServed > 0, "normalMaxServed=" + normalMaxServed);
        assertTrue(takeawayMaxServed <= normalMaxServed * 2,
                "takeawayMaxServed=" + takeawayMaxServed + ", normalMaxServed=" + normalMaxServed
                        + " — 打包窗口负载远超普通窗口,分流失败");
    }

    private SimConfig balancingConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-balancing");
        config.setDuration(1.0);
        config.setArrivalRate(300);
        config.setQueueLimit(40);
        config.setPackProbability(0.30);  // 高打包需求
        config.setSeed(20260606L);
        config.getBaseConfig().setTotalSeats(220);
        config.getBaseConfig().setTotalStudents(300);
        config.getBaseConfig().setWindowCount(9);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.10, 0.50));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }
}
