package com.bjtu.simulation.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * RFC-012:瓶颈严重度 3 段固定枚举。
 *
 * <ul>
 *   <li>{@code LOW}: observed value &isin; [0.85, 0.90)</li>
 *   <li>{@code MEDIUM}: observed value &isin; [0.90, 0.95)</li>
 *   <li>{@code HIGH}: observed value &isin; [0.95, &infin;)</li>
 * </ul>
 *
 * <p>触发阈值 0.85 在本 RFC 内 4 类瓶颈统一使用(见 {@code BottleneckAnalyzer}
 * 中的 {@code THRESHOLD_TRIGGER} 常量)。</p>
 *
 * <p>序列化策略:{@link JsonValue} 输出 lower_snake_case
 * ({@code "low" / "medium" / "high"})。</p>
 */
public enum BottleneckSeverity {
    LOW,
    MEDIUM,
    HIGH;

    @JsonValue
    public String toJsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
