package com.bjtu.simulation.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC-012:派生瓶颈诊断顶层 DTO(immutable POJO,严格 3 字段)。
 *
 * <p>由 {@code service/BottleneckAnalyzer} 在 {@code SimulationRunService.run()}
 * 完成 buildSummary 后,基于 {@link SimulationSummary} 已有 utilization / queue /
 * served 字段派生计算,通过 {@code summary.setBottleneckDiagnosis(...)} 注入。</p>
 *
 * <p>纯 if/else 闭式分发,无反射、无 LLM、无随机;同 seed 字节级稳定。</p>
 *
 * <ul>
 *   <li>{@code primary}:首选瓶颈类型。4 类瓶颈中 severity 最高的;均未触发时为
 *       {@link BottleneckType#BALANCED}。</li>
 *   <li>{@code secondary}:次选瓶颈类型;{@code bottlenecks.size() < 2} 或
 *       BALANCED 路径下为 null,通过 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略。</li>
 *   <li>{@code bottlenecks}:已触发的瓶颈列表,按 severity 降序、severity 相同时按
 *       BottleneckType 声明顺序排序;BALANCED 路径下为空 list。</li>
 * </ul>
 */
public class BottleneckDiagnosis {

    private final BottleneckType primary;
    private final BottleneckType secondary;
    private final List<DetectedBottleneck> bottlenecks;

    public BottleneckDiagnosis(BottleneckType primary,
                               BottleneckType secondary,
                               List<DetectedBottleneck> bottlenecks) {
        this.primary = primary;
        this.secondary = secondary;
        this.bottlenecks = bottlenecks == null ? List.of() : List.copyOf(bottlenecks);
    }

    public BottleneckType getPrimary() {
        return primary;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public BottleneckType getSecondary() {
        return secondary;
    }

    public List<DetectedBottleneck> getBottlenecks() {
        return bottlenecks;
    }
}
