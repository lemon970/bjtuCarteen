package com.bjtu.simulation.dto;

import java.util.List;

/**
 * RFC-010C:SensitivityAnalysis 离线扫描报告。
 *
 * <p><strong>顶层 4 字段</strong>:runId / baseConfigDigest / seedsPerPoint / axes。
 * 不含 perSeedMetrics(数据量太大,留给 010D 时再决议)、不含 fullBatchReports
 * (留给后续 sub-RFC)。新增字段必须经独立 RFC。</p>
 */
public class SensitivityReport {

    private final String runId;
    private final String baseConfigDigest;
    private final int seedsPerPoint;
    private final List<AxisResult> axes;

    public SensitivityReport(String runId,
                             String baseConfigDigest,
                             int seedsPerPoint,
                             List<AxisResult> axes) {
        this.runId = runId;
        this.baseConfigDigest = baseConfigDigest;
        this.seedsPerPoint = seedsPerPoint;
        this.axes = axes;
    }

    public String getRunId() {
        return runId;
    }

    public String getBaseConfigDigest() {
        return baseConfigDigest;
    }

    public int getSeedsPerPoint() {
        return seedsPerPoint;
    }

    public List<AxisResult> getAxes() {
        return axes;
    }
}
