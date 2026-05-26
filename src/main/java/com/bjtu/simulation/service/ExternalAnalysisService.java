package com.bjtu.simulation.service;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 高级统计分析入口。委托给纯 Java 的 {@link InternalStatisticsAnalyzer},
 * 输出 {@code confidence_intervals} / {@code bottleneck} / {@code headline_metrics}
 * 三组字段。报告不存在 → unavailable;否则 → available。
 */
@Service
public class ExternalAnalysisService {

    private final SimulationReportRepository reportRepository;
    private final InternalStatisticsAnalyzer analyzer;

    @Autowired
    public ExternalAnalysisService(SimulationReportRepository reportRepository,
                                   InternalStatisticsAnalyzer analyzer) {
        this.reportRepository = Objects.requireNonNull(reportRepository);
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    public AnalysisResult runForReport(String reportId) {
        if (!reportRepository.isSafeReportId(reportId)) {
            return AnalysisResult.unavailable("invalid report id");
        }
        var maybeReport = reportRepository.readById(reportId);
        if (maybeReport.isEmpty()) {
            return AnalysisResult.unavailable("report not found: " + reportId);
        }
        return AnalysisResult.available(analyzer.analyze(maybeReport.get()));
    }

    public static final class AnalysisResult {
        private final boolean available;
        private final ObjectNode payload;
        private final String reason;

        private AnalysisResult(boolean available, ObjectNode payload, String reason) {
            this.available = available;
            this.payload = payload;
            this.reason = reason;
        }
        public static AnalysisResult available(ObjectNode payload) { return new AnalysisResult(true, payload, null); }
        public static AnalysisResult unavailable(String reason) { return new AnalysisResult(false, null, reason); }
        public boolean isAvailable() { return available; }
        public ObjectNode getPayload() { return payload; }
        public String getReason() { return reason; }

        public Map<String, Object> toEnvelope() {
            return available
                    ? Map.of("available", true, "data", payload)
                    : Map.of("available", false, "reason", reason == null ? "unknown" : reason);
        }
    }
}
