package com.bjtu.simulation.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * RFC-012:派生瓶颈诊断分类枚举(严格 5 值,扩展需独立 RFC)。
 *
 * <p>序列化策略:用 {@link JsonValue} 显式输出 lower_snake_case
 * (例如 {@code "window_service_capacity"}),不依赖 mapper 全局
 * {@code PropertyNamingStrategies.SNAKE_CASE} — 后者只转字段名,不转 enum 值。</p>
 *
 * <p>{@code BALANCED} 仅作为 {@code BottleneckDiagnosis.primary} 在 4 类均未触发时使用,
 * <strong>不会</strong>出现在 {@code bottlenecks[]} 列表里。</p>
 */
public enum BottleneckType {
    WINDOW_SERVICE_CAPACITY,
    SEAT_CAPACITY,
    TAKEAWAY_CAPACITY,
    ARRIVAL_SURGE,
    BALANCED;

    @JsonValue
    public String toJsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
