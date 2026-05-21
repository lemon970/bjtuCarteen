package com.bjtu.simulation.dto;

/**
 * RFC-009 队列选择模型派发开关。
 *
 * <ul>
 *   <li>{@link #STATIC_SPLIT} 默认值,保留现行 windowPreference 均匀抽样 + 现行 score 选择。</li>
 *   <li>{@link #PREFERENCE_AWARE} V1 目标:weighted windowPreference generation。
 *       PR-9B 阶段未启用,Engine 入口会 fail-fast 抛 UnsupportedOperationException。</li>
 *   <li>{@link #WORKLOAD_ROUTING} V2/V3 占位,Engine 抛 UnsupportedOperationException。</li>
 *   <li>{@link #HYBRID_OVERFLOW} V2/V3 占位,Engine 抛 UnsupportedOperationException。</li>
 * </ul>
 */
public enum QueueChoiceModel {
    STATIC_SPLIT,
    PREFERENCE_AWARE,
    WORKLOAD_ROUTING,
    HYBRID_OVERFLOW
}
