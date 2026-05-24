package com.bjtu.simulation.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.AggregateMetrics;
import com.bjtu.simulation.dto.BatchRunReport;
import com.bjtu.simulation.dto.BatchRunRequest;
import com.bjtu.simulation.dto.PerSeedMetric;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RFC-010A:离线 multi-seed runner。RFC-010B:在末尾追加 {@code aggregate}(11 个 metric 的
 * mean / stddev / median / p10 / p90 / 95% t-interval CI)。
 *
 * <p>串行复用 {@link SimulationRunService#run(SimConfig, String)} × N,聚合成 {@link BatchRunReport}。
 * 本轮 ciMethod 恒为 "t",Bootstrap 移到 Future Work。</p>
 *
 * <p>Determinism 承诺:相同 baseConfig + seeds + 显式 runId 两次调用,perSeedMetrics + aggregate
 * 字节级一致;缺省 runId 退化为 UUID,该路径不在字节级一致测试覆盖中。</p>
 */
@Service
public class BatchRunService {

    private final SimulationRunService simulationRunService;
    private final PerSeedMetricExtractor extractor;
    private final AggregateMetricsCalculator aggregateCalculator;
    private final ObjectMapper reportMapper;

    @Autowired
    public BatchRunService(SimulationRunService simulationRunService,
                           PerSeedMetricExtractor extractor,
                           AggregateMetricsCalculator aggregateCalculator) {
        this(simulationRunService, extractor, aggregateCalculator,
                AppBeansConfig.createReportObjectMapper());
    }

    public BatchRunService(SimulationRunService simulationRunService,
                           PerSeedMetricExtractor extractor,
                           AggregateMetricsCalculator aggregateCalculator,
                           ObjectMapper reportMapper) {
        this.simulationRunService = simulationRunService;
        this.extractor = extractor;
        this.aggregateCalculator = aggregateCalculator;
        this.reportMapper = reportMapper;
    }

    public BatchRunReport run(BatchRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        long[] seeds = request.getSeeds();
        if (seeds == null || seeds.length == 0) {
            throw new IllegalArgumentException("seeds must be non-empty");
        }

        String runId = request.getRunId() != null ? request.getRunId()
                : UUID.randomUUID().toString();
        String baseConfigDigest = computeBaseConfigDigest(request.getBaseConfig());

        List<PerSeedMetric> perSeedMetrics = new ArrayList<>(seeds.length);
        for (int i = 0; i < seeds.length; i++) {
            SimConfig clonedConfig = cloneConfig(request.getBaseConfig());
            clonedConfig.setSeed(seeds[i]);
            String perSeedReportId = runId + "-" + i;
            SimulationReport report = simulationRunService.run(clonedConfig, perSeedReportId);
            perSeedMetrics.add(extractor.extract(seeds[i], report));
        }

        long[] seedsCopy = new long[seeds.length];
        System.arraycopy(seeds, 0, seedsCopy, 0, seeds.length);
        AggregateMetrics aggregate = aggregateCalculator.aggregate(perSeedMetrics);
        return new BatchRunReport(runId, baseConfigDigest, seedsCopy, perSeedMetrics, aggregate);
    }

    private SimConfig cloneConfig(SimConfig source) {
        SimConfig safe = source == null ? new SimConfig() : source;
        return reportMapper.convertValue(safe, SimConfig.class);
    }

    /**
     * digest 表示**业务配置**,清空 seed 字段后再哈希。语义上"仅 seed 不同的两份 config" digest 必相等。
     */
    private String computeBaseConfigDigest(SimConfig baseConfig) {
        SimConfig digestConfig = cloneConfig(baseConfig);
        digestConfig.setSeed(null);
        byte[] bytes;
        try {
            bytes = reportMapper.writeValueAsBytes(digestConfig);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize baseConfig for digest", e);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
