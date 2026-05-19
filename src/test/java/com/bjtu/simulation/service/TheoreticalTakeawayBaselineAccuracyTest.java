package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimulationSummary;

import org.junit.jupiter.api.Test;

/**
 * Bug-03 复现:打包率基准(theoreticalTakeawayRate)与实际观测(takeawayRate)
 * 大部分预设上偏离明显,用户描述"基准大部分时候偏低,无法精确描述理论打包率"。
 *
 * 现状:computeTheoreticalTakeawayRate(SimulationRunService.java:186) 只算
 *      basePack × WeatherFactorPolicy.effective(weather, userFactor),
 *      忽略 5 条独立加成路径中的 4 条:
 *        - 打包窗口路由强制(takeawayWindowCount/windowCount 几何分量)
 *        - 学生 packPreference 抽样均值(StudentProfileFactory)
 *        - 座位/等待/队列压力翻转(TakeawayDecisionPolicy)
 *        - peak 期间 weather delta 二阶项
 *
 *      这导致除了纯 sunny 平峰场景外,理论值都系统性偏低。
 *
 * 用户期望:基准与实际有偏差但能"较好描述应有情况"。
 *
 * 该测试用每个预设的固定 seed 跑确定性仿真,断言 theoretical 与 observed 之间
 * 相对偏离 ≤ 0.30(30%)。这是用户接受的"较好描述"区间——超出意味着基准不再
 * 是参考线而是误导。当前公式应在多数预设上被打红。
 */
class TheoreticalTakeawayBaselineAccuracyTest {

    /** 用户视角"较好描述"的相对偏离上限。30% 已经相当宽容。 */
    private static final double MAX_RELATIVE_DEVIATION = 0.30;

    /** 防止 0 附近相对偏离爆炸的下限,值取 5%——比任何典型场景都低,等价于绝对 0.015 的容忍。 */
    private static final double DENOM_FLOOR = 0.05;

    @Test
    void theoreticalBaselineMustReasonablyDescribeObservedAcrossPresets() {
        ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();
        SimulationRunService runService = new SimulationRunService();

        List<String> violations = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (ScenarioPreset preset : catalog.list()) {
            SimulationSummary summary = runService.run(preset.config()).getSummary();
            double theoretical = summary.getTheoreticalTakeawayRate();
            double observed = summary.getTakeawayRate();

            double denom = Math.max(DENOM_FLOOR, Math.max(theoretical, observed));
            double relativeDeviation = Math.abs(observed - theoretical) / denom;

            String row = String.format(
                    "preset=%s theoretical=%.4f observed=%.4f deviation=%.2f%%",
                    preset.id(), theoretical, observed, relativeDeviation * 100.0);
            diagnostics.add(row);

            if (relativeDeviation > MAX_RELATIVE_DEVIATION) {
                violations.add(row + " (theoretical "
                        + (theoretical < observed ? "TOO LOW" : "TOO HIGH") + ")");
            }
        }

        assertTrue(violations.isEmpty(),
                "Bug-03 reproduced: theoretical baseline systematically diverges from observed "
                        + "(threshold=" + (int) (MAX_RELATIVE_DEVIATION * 100) + "%).\n"
                        + "All presets:\n  - " + String.join("\n  - ", diagnostics)
                        + "\nViolations:\n  - " + String.join("\n  - ", violations));
    }
}
