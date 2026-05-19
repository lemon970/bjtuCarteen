package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimConfig;

import org.junit.jupiter.api.Test;

/**
 * Bug-02 复现:加载"雨天紧急预案"模型后,前端"天气影响系数"输入框报
 * "请输入一个有效的值,最接近的两个有效值为 1.2 和 1.3",阻止运行当前配置。
 *
 * 根因(只读分析所得):
 *   - 前端 InputPage.jsx:189 NumberField step="0.1"、min="0"
 *   - 后端 ScenarioPresetCatalog rain_emergency 给的 weatherFactor=1.25
 *   - 浏览器原生 HTML5 number input stepMismatch 校验:
 *     (value - min) 必须是 step 的整数倍。1.25 不是 0.1 的整数倍 → 拒绝
 *
 * 复现路径(完全对应人工操作):
 *   1. 前端调 /api/simulation/scenarios 读取预设
 *   2. 加载 rain_emergency 的 SimConfig
 *   3. weatherImpactFactor 提交到 step=0.1 的 NumberField → stepMismatch=true
 *
 * 该测试在后端镜像 NumberField step=0.1 的校验,验证后端预设值不违反这个跨层契约。
 * 修复方向:或者把预设值改成 0.1 整数倍(1.2 或 1.3),或者把 NumberField step
 * 改成更细的精度。两边任一都能让此测试通过。
 */
class RainEmergencyWeatherFactorStepTest {

    /** InputPage.jsx:189 的 step="0.05"(原 0.1 因预设 1.25 触发 stepMismatch,Bug-02 修复后改细)。 */
    private static final double FRONTEND_STEP = 0.05;

    /** 浮点容差,远宽于 IEEE-754 浮点除法误差(~1e-15),又远紧于 0.1 步长粒度。 */
    private static final double EPS = 1e-6;

    @Test
    void rainEmergencyWeatherImpactFactorMustConformToFrontendStep() {
        ScenarioPresetCatalog catalog = new ScenarioPresetCatalog();
        ScenarioPreset preset = catalog.find("rain_emergency")
                .orElseThrow(() -> new AssertionError("rain_emergency preset must exist"));
        SimConfig config = preset.config();
        assertNotNull(config.getWeatherConfig(),
                "weather config must be present in preset");
        double factor = config.getWeatherConfig().getWeatherImpactFactor();

        // 镜像浏览器 HTML5 number input stepMismatch:
        // 合法当且仅当 value/step 接近一个整数(min=0)。
        double quotient = factor / FRONTEND_STEP;
        double remainder = Math.abs(quotient - Math.round(quotient));

        double lowerValid = Math.floor(quotient) * FRONTEND_STEP;
        double upperValid = Math.ceil(quotient) * FRONTEND_STEP;

        assertTrue(remainder < EPS,
                String.format(
                        "Bug-02 reproduced: rain_emergency preset weatherImpactFactor=%.4f does not "
                                + "conform to InputPage NumberField step=%.2f (min=0). "
                                + "Browser native stepMismatch will reject the loaded value with "
                                + "'最接近的两个有效值为 %.1f 和 %.1f', blocking '运行当前配置'.",
                        factor, FRONTEND_STEP, lowerValid, upperValid));
    }
}
