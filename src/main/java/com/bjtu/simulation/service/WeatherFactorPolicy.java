package com.bjtu.simulation.service;

import java.util.Locale;
import java.util.Map;

/**
 * 天气类型 → 打包率基线倍率的映射 + 用户自定义 factor 叠加。
 *
 * effectiveFactor = canonical(weather) × userFactor,clamp 到 [0.5, 3.0]。
 * 把"天气类型"作为打包行为的一阶决定因素,避免 rainy + factor=1.0 与 sunny + factor=1.0 等价。
 */
public final class WeatherFactorPolicy {

    private static final Map<String, Double> CANONICAL = Map.of(
            "sunny", 1.00,
            "cloudy", 1.05,
            "windy", 1.10,
            "rainy", 1.30,
            "snowy", 1.45,
            "stormy", 1.55
    );

    private static final double DEFAULT_CANONICAL = 1.00;
    private static final double MIN_EFFECTIVE = 0.5;
    private static final double MAX_EFFECTIVE = 3.0;

    private WeatherFactorPolicy() {
    }

    public static double getCanonicalFactor(String weather) {
        if (weather == null) {
            return DEFAULT_CANONICAL;
        }
        return CANONICAL.getOrDefault(weather.trim().toLowerCase(Locale.ROOT), DEFAULT_CANONICAL);
    }

    public static double resolveEffectiveFactor(String weather, double userFactor) {
        double canonical = getCanonicalFactor(weather);
        double user = Double.isFinite(userFactor) && userFactor > 0 ? userFactor : 1.0;
        return SimulationMath.clamp(canonical * user, MIN_EFFECTIVE, MAX_EFFECTIVE);
    }
}
