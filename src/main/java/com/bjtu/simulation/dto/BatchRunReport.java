package com.bjtu.simulation.dto;

import java.util.List;

/**
 * RFC-010A:多 seed 批量运行报告。RFC-010B 在末尾追加 {@code aggregate} 字段。
 *
 * <p><strong>顶层 6 字段</strong>:runId / baseConfigDigest / seeds / perSeedMetrics / mode /
 * aggregate。**不含 {@code runs}**(FULL_REPORTS_DEBUG 仍未实现)。新增字段必须经独立 RFC。</p>
 */
public class BatchRunReport {

    private final String runId;
    private final String baseConfigDigest;
    private final long[] seeds;
    private final List<PerSeedMetric> perSeedMetrics;
    private final PerSeedMode mode;
    private final AggregateMetrics aggregate;

    public BatchRunReport(String runId,
                          String baseConfigDigest,
                          long[] seeds,
                          List<PerSeedMetric> perSeedMetrics,
                          PerSeedMode mode,
                          AggregateMetrics aggregate) {
        this.runId = runId;
        this.baseConfigDigest = baseConfigDigest;
        this.seeds = seeds;
        this.perSeedMetrics = perSeedMetrics;
        this.mode = mode;
        this.aggregate = aggregate;
    }

    public String getRunId() {
        return runId;
    }

    public String getBaseConfigDigest() {
        return baseConfigDigest;
    }

    public long[] getSeeds() {
        return seeds;
    }

    public List<PerSeedMetric> getPerSeedMetrics() {
        return perSeedMetrics;
    }

    public PerSeedMode getMode() {
        return mode;
    }

    public AggregateMetrics getAggregate() {
        return aggregate;
    }
}
