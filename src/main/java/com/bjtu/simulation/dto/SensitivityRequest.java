package com.bjtu.simulation.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * RFC-010C:SensitivityAnalysis 请求体。
 *
 * <ul>
 *   <li>{@code baseConfig}:基准业务配置(seed 字段会被内部覆盖,调用方原引用不会被 mutate)</li>
 *   <li>{@code axes}:K 条扫描轴,K >= 1;不允许重复 parameter</li>
 *   <li>{@code seeds}:必填,长度 N >= 1;每个 (axis, point) 都用同一份 seeds(CRN 公共随机数)</li>
 *   <li>{@code runId}:可选;缺失退化为 UUID,不在字节级一致测试覆盖中</li>
 * </ul>
 */
public class SensitivityRequest {

    @JsonAlias("base_config")
    private SimConfig baseConfig;

    private List<ScanAxis> axes;

    private long[] seeds;

    @JsonAlias("run_id")
    private String runId;

    public SensitivityRequest() {
    }

    public SensitivityRequest(SimConfig baseConfig, List<ScanAxis> axes, long[] seeds) {
        this.baseConfig = baseConfig;
        this.axes = axes;
        this.seeds = seeds;
    }

    public SimConfig getBaseConfig() {
        return baseConfig;
    }

    public void setBaseConfig(SimConfig baseConfig) {
        this.baseConfig = baseConfig;
    }

    public List<ScanAxis> getAxes() {
        return axes;
    }

    public void setAxes(List<ScanAxis> axes) {
        this.axes = axes;
    }

    public long[] getSeeds() {
        return seeds;
    }

    public void setSeeds(long[] seeds) {
        this.seeds = seeds;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
