package com.bjtu.simulation.dto;

/**
 * RFC-010A:批量运行的 per-seed 输出粒度。
 *
 * <p>{@code METRICS_ONLY} 是默认且唯一在 RFC-010A 中实现的模式;
 * {@code FULL_REPORTS_DEBUG} enum 占位,本轮抛 {@code UnsupportedOperationException}。</p>
 */
public enum PerSeedMode {
    METRICS_ONLY,
    FULL_REPORTS_DEBUG
}
