package com.bjtu.simulation.dto;

/**
 * RFC-012:单条已检测瓶颈记录(immutable POJO)。
 *
 * <p>{@code type} 永不为 {@link BottleneckType#BALANCED} — BALANCED 仅作为
 * {@link BottleneckDiagnosis#getPrimary()} 的兜底返回值,不会进入 bottlenecks 列表。</p>
 *
 * <p>{@code severity} 由 observedValue 落点决定:[0.85,0.90)→LOW、[0.90,0.95)→MEDIUM、
 * [0.95,&infin;)→HIGH。</p>
 *
 * <p>{@code evidence} 必非 null。</p>
 */
public class DetectedBottleneck {

    private final BottleneckType type;
    private final BottleneckSeverity severity;
    private final BottleneckEvidence evidence;

    public DetectedBottleneck(BottleneckType type,
                              BottleneckSeverity severity,
                              BottleneckEvidence evidence) {
        this.type = type;
        this.severity = severity;
        this.evidence = evidence;
    }

    public BottleneckType getType() {
        return type;
    }

    public BottleneckSeverity getSeverity() {
        return severity;
    }

    public BottleneckEvidence getEvidence() {
        return evidence;
    }
}
