package com.bjtu.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * RFC-010A:多 seed 批量运行请求。
 *
 * <ul>
 *   <li>{@code baseConfig}:业务配置(seed 字段会在内部被覆盖,调用方原引用不会被 mutate)。</li>
 *   <li>{@code seeds}:必填,长度 ≥ 1。</li>
 *   <li>{@code maxParallel}:RFC-010A 仅支持 1;传 >1 抛 {@code UnsupportedOperationException}。</li>
 *   <li>{@code mode}:仅 {@link PerSeedMode#METRICS_ONLY} 实现;{@code FULL_REPORTS_DEBUG} 抛 UOE。</li>
 *   <li>{@code runId}:可选;缺失时 service 退化为 UUID。determinism 测试必须显式提供 runId。</li>
 * </ul>
 */
public class BatchRunRequest {

    @JsonAlias("base_config")
    private SimConfig baseConfig;

    private long[] seeds;

    @JsonAlias("max_parallel")
    private int maxParallel = 1;

    private PerSeedMode mode = PerSeedMode.METRICS_ONLY;

    @JsonAlias("run_id")
    private String runId;

    public BatchRunRequest() {
    }

    public BatchRunRequest(SimConfig baseConfig, long[] seeds) {
        this.baseConfig = baseConfig;
        this.seeds = seeds;
    }

    public SimConfig getBaseConfig() {
        return baseConfig;
    }

    public void setBaseConfig(SimConfig baseConfig) {
        this.baseConfig = baseConfig;
    }

    public long[] getSeeds() {
        return seeds;
    }

    public void setSeeds(long[] seeds) {
        this.seeds = seeds;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public PerSeedMode getMode() {
        return mode;
    }

    public void setMode(PerSeedMode mode) {
        this.mode = mode;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
