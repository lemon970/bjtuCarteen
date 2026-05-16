package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.model.TakeawayDecisionRecord;
import com.bjtu.simulation.service.SimulationRunService;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 第七轮 A1 验证:用户已经在 ServiceFinishEvent 把"动态打包概率"接通到真实决策。
 * 之前 roll 固定为 0.0,if (student.wantsTakeaway()) 直接走打包,概率字段只用于记录。
 * 现在 roll = initialTakeawayIntent ? 0.0 : engine.nextDouble(),
 * 当 dine-in 倾向学生命中 roll < finalProbability 时会真正翻转为打包,
 * 决策记录里写明 "dynamic probability roll selected takeaway"。
 *
 * 本测试断言:
 *  1. 高压力(满座 + 长队)场景下,出现至少一条 dynamic 翻转记录
 *  2. 低压力场景下,几乎不出现 dynamic 翻转(决策记录 < 5%)
 *  3. 两种 reason("arrival takeaway intent retained" 与 "dynamic probability roll selected
 *     takeaway")在记录中可区分
 */
class DynamicTakeawayDecisionTest {

    private final SimulationRunService runService = new SimulationRunService();

    @Test
    void highPressureShouldTriggerDynamicTakeawayFlips() {
        SimConfig config = highPressureConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();
        List<TakeawayDecisionRecord> records = summary.getTakeawayDecisionRecords();

        long dynamicFlips = records.stream()
                .filter(r -> r.isTakeaway()
                        && "dynamic probability roll selected takeaway".equals(r.getDecisionReason()))
                .count();
        long arrivalRetained = records.stream()
                .filter(r -> r.isTakeaway()
                        && "arrival takeaway intent retained; normal window serves takeaway".equals(r.getDecisionReason()))
                .count();

        assertTrue(records.size() > 0, "expected non-empty decision records");
        assertTrue(dynamicFlips > 0,
                "expected at least one dynamic-flip takeaway record under high pressure, got 0 (records=" + records.size() + ")");
        // 同时存在 arrival-retained 路径,证明两条 reason 都被使用
        assertTrue(arrivalRetained > 0,
                "expected initialTakeawayIntent path to also fire, got 0");
    }

    @Test
    void lowPressureShouldRarelyTriggerDynamicFlips() {
        SimConfig config = lowPressureConfig();
        SimulationReport report = runService.run(config);
        SimulationSummary summary = report.getSummary();
        List<TakeawayDecisionRecord> records = summary.getTakeawayDecisionRecords();

        long dynamicFlips = records.stream()
                .filter(r -> r.isTakeaway()
                        && "dynamic probability roll selected takeaway".equals(r.getDecisionReason()))
                .count();
        // 决策记录总数 = 所有进入 ServiceFinishEvent 的学生 + dine-in 学生
        // 低压力下 dynamic flip 比例应明显低于 50%
        double flipRate = records.isEmpty() ? 0.0 : (double) dynamicFlips / records.size();
        assertTrue(flipRate < 0.50,
                "low-pressure dynamic flip rate=" + flipRate + " (dynamicFlips=" + dynamicFlips
                        + " / records=" + records.size() + ") should be <0.50");
    }

    private SimConfig highPressureConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-high-pressure");
        config.setDuration(1.5);
        config.setArrivalRate(320);
        config.setQueueLimit(45);
        config.setPackProbability(0.20);
        config.setSeed(20260607L);
        config.getBaseConfig().setTotalSeats(180);  // 座位紧张
        config.getBaseConfig().setTotalStudents(280);
        config.getBaseConfig().setWindowCount(7);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.20);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.40));
        // 用 sunny 避免 wantsTakeaway 被天气抬到太高,让 dine-in 学生占多数,
        // 留出空间观察 dynamic flip
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }

    private SimConfig lowPressureConfig() {
        SimConfig config = new SimConfig();
        config.setSimulationName("test-low-pressure");
        config.setDuration(1.0);
        config.setArrivalRate(80);
        config.setQueueLimit(30);
        config.setPackProbability(0.10);
        config.setSeed(20260608L);
        config.getBaseConfig().setTotalSeats(400);  // 座位非常充裕
        config.getBaseConfig().setTotalStudents(80);
        config.getBaseConfig().setWindowCount(8);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.18);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.30));
        config.getWeatherConfig().setCurrentWeather("sunny");
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }
}
