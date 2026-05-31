package com.bjtu.simulation.dto;

/**
 * RFC-009 队列选择模型派发开关。
 *
 * <ul>
 *   <li>{@link #STATIC_SPLIT} 默认值,保留现行 windowPreference 均匀抽样 + 现行 score 选择。</li>
 *   <li>{@link #PREFERENCE_AWARE} weighted windowPreference generation(PR-9C 起启用)。</li>
 * </ul>
 */
public enum QueueChoiceModel {
    STATIC_SPLIT,
    PREFERENCE_AWARE
}
