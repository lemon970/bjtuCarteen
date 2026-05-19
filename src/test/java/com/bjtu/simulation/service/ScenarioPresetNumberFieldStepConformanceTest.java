package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimConfig;

import org.junit.jupiter.api.Test;

/**
 * Bug-02 全面复现:加载预设后前端 InputPage 中任意 NumberField 触发 stepMismatch
 * 都会让浏览器原生 form validity 阻塞 "运行当前配置" 按钮提交。
 *
 * 上一次只镜像 weatherImpactFactor 一个字段(RainEmergencyWeatherFactorStepTest),
 * 漏掉了 rain_emergency 的 peak multiplier=2.6×1.25=3.25 和 1.8×1.25=2.25 与
 * NumberField step=0.1 的冲突——前端"运行当前配置"按钮加载预设后仍点不动的真实原因。
 *
 * 该测试镜像 sun/src/pages/InputPage.jsx 中所有 NumberField 的 (字段→step) 表,
 * 对每个预设、每个数值字段做 stepMismatch 检查。任一违规都会失败,
 * 防止预设值 vs 前端 step 不匹配回归。
 */
class ScenarioPresetNumberFieldStepConformanceTest {

    /** 浮点容差,远宽于 IEEE-754 浮点除法误差(~1e-15),又远紧于最小 step 粒度(0.01)。 */
    private static final double EPS = 1e-6;

    @Test
    void everyPresetMustConformToInputPageNumberFieldSteps() {
        ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();
        List<String> violations = new ArrayList<>();
        for (ScenarioPreset preset : catalog.list()) {
            audit(preset, violations);
        }
        assertTrue(violations.isEmpty(),
                "Bug-02 reproduced: preset values violate InputPage NumberField step constraint. "
                        + "Browser HTML5 stepMismatch will block form submit (\"运行当前配置\" silent). "
                        + "Violations:\n  - "
                        + String.join("\n  - ", violations));
    }

    private void audit(ScenarioPreset preset, List<String> violations) {
        String id = preset.id();
        SimConfig c = preset.config();

        // —— 到达模型 / 服务时间 / 座位策略 / 干预规则(InputPage.jsx:161-189)——
        check(violations, id, "duration", c.getDuration(), 0.1);
        check(violations, id, "arrivalRate", c.getArrivalRate(), 1.0);
        check(violations, id, "queueLimit", c.getQueueLimit(), 1.0);
        check(violations, id, "packProbability", c.getPackProbability(), 0.01);
        check(violations, id, "groupArrivalProb", c.getGroupArrivalProb(), 0.01);
        check(violations, id, "partySize", c.getPartySize(), 1.0);
        check(violations, id, "walkTimeMean", c.getWalkTimeMean(), 1.0);
        check(violations, id, "congestionPenalty", c.getCongestionPenalty(), 0.05);

        SimConfig.BaseConfig base = c.getBaseConfig();
        check(violations, id, "windowCount", base.getWindowCount(), 1.0);
        check(violations, id, "takeawayWindowCount", base.getTakeawayWindowCount(), 1.0);
        check(violations, id, "takeawayServiceTimeMultiplier",
                base.getTakeawayServiceTimeMultiplier(), 0.01);
        check(violations, id, "totalSeats", base.getTotalSeats(), 1.0);
        check(violations, id, "totalStudents", base.getTotalStudents(), 1.0);

        SimConfig.WeatherConfig weather = c.getWeatherConfig();
        check(violations, id, "weatherImpactFactor", weather.getWeatherImpactFactor(), 0.05);

        // —— 高级参数(InputPage.jsx:209-217)——
        SimConfig.RandomBounds random = c.getRandomBounds();
        check(violations, id, "arrivalInterval", random.getArrivalInterval(), 1.0);
        if (random.getServiceRange() != null && random.getServiceRange().size() == 2) {
            check(violations, id, "serviceMin", random.getServiceRange().get(0), 1.0);
            check(violations, id, "serviceMax", random.getServiceRange().get(1), 1.0);
        }
        if (random.getDiningRange() != null && random.getDiningRange().size() == 2) {
            check(violations, id, "diningMin", random.getDiningRange().get(0), 1.0);
            check(violations, id, "diningMax", random.getDiningRange().get(1), 1.0);
        }
        if (random.getPreferenceRange() != null && random.getPreferenceRange().size() == 2) {
            check(violations, id, "preferenceMin", random.getPreferenceRange().get(0), 0.01);
            check(violations, id, "preferenceMax", random.getPreferenceRange().get(1), 0.01);
        }

        // serviceMean / diningMean 走 distribution.mean(InputPage.jsx:169, 176)
        if (c.getNormalServiceDist() != null) {
            check(violations, id, "serviceMean(normal)", c.getNormalServiceDist().getMean(), 1.0);
        }
        if (c.getDiningTimeDist() != null) {
            check(violations, id, "diningMean", c.getDiningTimeDist().getMean(), 1.0);
        }

        // —— 成组学生(InputPage.jsx:197-202)——
        SimConfig.GroupConfig group = c.getGroupConfig();
        check(violations, id, "groupCount", group.getGroupCount(), 1.0);
        check(violations, id, "groupSizeMin", group.getSizeMin(), 1.0);
        check(violations, id, "groupSizeMax", group.getSizeMax(), 1.0);
        check(violations, id, "groupArrivalSpreadSeconds", group.getArrivalSpreadSeconds(), 1.0);
        check(violations, id, "groupBehaviorCorrelation", group.getBehaviorCorrelation(), 0.05);

        // —— 峰值(InputPage.jsx:218-223)——
        // applyPayloadToForm 把 classPeakWindows[0/1].multiplier 映射到
        // form.lunchPeakMultiplier / dinnerPeakMultiplier,各自由 NumberField step="0.1" 渲染。
        SimConfig.PeakConfig peak = c.getPeakConfig();
        if (peak.getClassPeakWindows() != null) {
            int idx = 0;
            for (SimConfig.PeakConfig.PeakWindow pw : peak.getClassPeakWindows()) {
                String multiplierLabel = idx == 0
                        ? "lunchPeakMultiplier"
                        : (idx == 1 ? "dinnerPeakMultiplier" : "peakMultiplier[" + idx + "]");
                String windowLabel = "(window " + pw.getStartMinute() + "-" + pw.getEndMinute() + ")";
                check(violations, id, multiplierLabel + windowLabel, pw.getMultiplier(), 0.05);
                check(violations, id, "peakStart[" + idx + "]", pw.getStartMinute(), 1.0);
                check(violations, id, "peakEnd[" + idx + "]", pw.getEndMinute(), 1.0);
                idx++;
            }
        }
    }

    /**
     * 镜像浏览器 HTML5 number input stepMismatch:
     * value 合法当且仅当 value/step 接近某个整数(min=0)。
     */
    private void check(List<String> violations, String presetId, String fieldName,
                       double value, double step) {
        if (step <= 0) {
            return;
        }
        double quotient = value / step;
        double remainder = Math.abs(quotient - Math.round(quotient));
        if (remainder >= EPS) {
            double lower = Math.floor(quotient) * step;
            double upper = Math.ceil(quotient) * step;
            violations.add(String.format(
                    "preset=%s field=%s value=%.4f step=%.2f (closest valid: %.2f / %.2f)",
                    presetId, fieldName, value, step, lower, upper));
        }
    }
}
