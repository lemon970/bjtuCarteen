package com.bjtu.simulation.engine;

/**
 * RFC-009 §4.3 普通窗口角色。
 *
 * <p>仅作用于普通窗口;打包窗口不参与角色分配,但在 weighted sampling 中使用
 * {@code normalAttractiveness} 中性权重(详见 RFC-009 §4.4)。</p>
 */
enum WindowRole {
    POPULAR,
    NORMAL,
    COLD,
    /** 打包窗口的占位标记,不参与 metrics 角色统计。 */
    TAKEAWAY
}
