package com.bjtu.simulation.dto;

/**
 * RFC-010C:SensitivityAnalysis 第一批白名单参数。**6 项闭合**,扩展需独立 RFC + 独立测试。
 *
 * <p>名字与 v2 §RFC-010C 决议对齐;特别地,第 6 项 {@link #SERVICE_RANGE_SCALE} 是合成参数
 * (default=1.0,扫描点 [0.7, 0.85, 1.0, 1.15, 1.3]),而不是 SimConfig 中的实际字段:实际服务时长由
 * {@code RandomBounds.serviceRange=[45,180]} 秒 + {@code NormalServiceDist}/{@code WindowServiceDist}
 * 共同采样,SERVICE_RANGE_SCALE 把 baseline 区间按 scale 倍率乘后取整。</p>
 */
public enum WhitelistedParam {

    ARRIVAL_RATE,
    WINDOW_COUNT,
    TAKEAWAY_WINDOW_COUNT,
    TOTAL_SEATS,
    SERVICE_RANGE_SCALE,
    PACK_PROBABILITY;

    /**
     * 大小写不敏感命中。任何不在 enum 中的 name → {@link IllegalArgumentException}。
     * 严禁通过 reflection / Class.forName 解析,白名单**只通过此函数**对外暴露。
     */
    public static WhitelistedParam fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("parameter name must not be null");
        }
        for (WhitelistedParam p : values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("parameter not whitelisted: " + name);
    }
}
