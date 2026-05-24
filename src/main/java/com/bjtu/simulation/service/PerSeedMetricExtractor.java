package com.bjtu.simulation.service;

import java.util.Objects;

import com.bjtu.simulation.dto.PerSeedMetric;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.WindowChoiceMetrics;

import org.springframework.stereotype.Service;

/**
 * RFC-010A:从 {@link SimulationReport} 提取 11 个核心 metric 字段成 {@link PerSeedMetric}。
 *
 * <p>STATIC_SPLIT 路径下 {@code summary.windowChoiceMetrics == null},
 * 3 个 PR-9D 字段(popularServedShare / coldServedShare / windowServedCountCv)填 null,
 * 由 {@code @JsonInclude(NON_NULL)} 在 JSON 中省略。</p>
 */
@Service
public class PerSeedMetricExtractor {

    public PerSeedMetric extract(long seed, SimulationReport report) {
        Objects.requireNonNull(report, "report must not be null");
        SimulationSummary summary = Objects.requireNonNull(report.getSummary(),
                "report.summary must not be null");

        WindowChoiceMetrics wcm = summary.getWindowChoiceMetrics();
        Double popularServedShare = wcm == null ? null : wcm.getPopularServedShare();
        Double coldServedShare = wcm == null ? null : wcm.getColdServedShare();
        Double windowServedCountCv = wcm == null ? null : wcm.getWindowServedCountCv();

        return new PerSeedMetric(
                seed,
                report.getReportId(),
                summary.getArrivedCount(),
                summary.getServedCount(),
                summary.getTypicalWaitTimeMinutes(),
                summary.getMedianWaitTimeMinutes(),
                summary.getP90WaitTimeMinutes(),
                summary.getSeatUtilizationRate(),
                summary.getTakeawayRate(),
                summary.getMaxTotalQueueSize(),
                popularServedShare,
                coldServedShare,
                windowServedCountCv);
    }
}
