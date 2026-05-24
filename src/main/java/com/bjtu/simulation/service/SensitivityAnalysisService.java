package com.bjtu.simulation.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.AggregateMetrics;
import com.bjtu.simulation.dto.AxisResult;
import com.bjtu.simulation.dto.BatchRunReport;
import com.bjtu.simulation.dto.BatchRunRequest;
import com.bjtu.simulation.dto.MetricSensitivityCurve;
import com.bjtu.simulation.dto.MetricStat;
import com.bjtu.simulation.dto.ScanAxis;
import com.bjtu.simulation.dto.SensitivityReport;
import com.bjtu.simulation.dto.SensitivityRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WhitelistedParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RFC-010C:离线参数敏感度扫描。
 *
 * <p>K 条扫描轴 × M 点 × N seed,每点调一次 {@link BatchRunService}。从 batch 的
 * {@link AggregateMetrics} 取 11 个 metric 的 mean 作为 Y 值,组成 11 条 curve;同时为每条 curve 算
 * 标准化敏感系数 {@code (max - min) / max(|centerY|, 1e-9)}。</p>
 *
 * <p>纯叠加层:不修改 010A / 010B 的 {@link BatchRunService} / {@link BatchRunReport}。</p>
 *
 * <p>Determinism:同 baseConfig + 同 seeds + 同 axes + 同 runId 两次调用,SensitivityReport 字节级
 * 一致(每个 (axis, point) 的派生 runId 是 {@code parentRunId + "/" + paramName + "/" + i});
 * 缺省 runId 退化为 UUID,该路径不在字节级一致测试覆盖中。</p>
 */
@Service
public class SensitivityAnalysisService {

    private final BatchRunService batchRunService;
    private final WhitelistedParameterMutator mutator;
    private final ObjectMapper reportMapper;

    @Autowired
    public SensitivityAnalysisService(BatchRunService batchRunService,
                                      WhitelistedParameterMutator mutator) {
        this(batchRunService, mutator, AppBeansConfig.createReportObjectMapper());
    }

    public SensitivityAnalysisService(BatchRunService batchRunService,
                                      WhitelistedParameterMutator mutator,
                                      ObjectMapper reportMapper) {
        this.batchRunService = batchRunService;
        this.mutator = mutator;
        this.reportMapper = reportMapper;
    }

    public SensitivityReport run(SensitivityRequest request) {
        validate(request);

        String runId = request.getRunId() != null ? request.getRunId()
                : UUID.randomUUID().toString();
        String baseConfigDigest = computeBaseConfigDigest(request.getBaseConfig());

        List<AxisResult> axisResults = new ArrayList<>(request.getAxes().size());
        for (ScanAxis axis : request.getAxes()) {
            axisResults.add(runAxis(request, runId, axis));
        }

        return new SensitivityReport(runId, baseConfigDigest,
                request.getSeeds().length, axisResults);
    }

    private void validate(SensitivityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getAxes() == null || request.getAxes().isEmpty()) {
            throw new IllegalArgumentException("axes must be non-empty");
        }
        if (request.getSeeds() == null || request.getSeeds().length == 0) {
            throw new IllegalArgumentException("seeds must be non-empty");
        }
        Set<WhitelistedParam> seen = EnumSet.noneOf(WhitelistedParam.class);
        for (ScanAxis axis : request.getAxes()) {
            if (axis == null || axis.getParameter() == null) {
                throw new IllegalArgumentException("axis or its parameter must not be null");
            }
            if (axis.getPoints() == null || axis.getPoints().length == 0) {
                throw new IllegalArgumentException(
                        "scan points must be non-empty for parameter " + axis.getParameter());
            }
            if (!seen.add(axis.getParameter())) {
                throw new IllegalArgumentException(
                        "duplicate parameter in axes: " + axis.getParameter());
            }
        }
    }

    private AxisResult runAxis(SensitivityRequest request, String runId, ScanAxis axis) {
        double[] points = axis.getPoints();
        int m = points.length;

        double[] arrived = new double[m];
        double[] served = new double[m];
        double[] typicalWait = new double[m];
        double[] medianWait = new double[m];
        double[] p90Wait = new double[m];
        double[] seatUtil = new double[m];
        double[] takeaway = new double[m];
        double[] maxQueue = new double[m];

        boolean pr9dPresent = false;
        boolean pr9dDetermined = false;
        double[] popularShare = new double[m];
        double[] coldShare = new double[m];
        double[] cv = new double[m];

        for (int i = 0; i < m; i++) {
            SimConfig clonedConfig = cloneConfig(request.getBaseConfig());
            mutator.apply(clonedConfig, axis.getParameter(), points[i]);

            BatchRunRequest batchReq = new BatchRunRequest(clonedConfig, request.getSeeds().clone());
            batchReq.setRunId(runId + "/" + axis.getParameter().name() + "/" + i);
            BatchRunReport batch = batchRunService.run(batchReq);
            AggregateMetrics agg = batch.getAggregate();

            arrived[i] = agg.getArrivedCount().getMean();
            served[i] = agg.getServedCount().getMean();
            typicalWait[i] = agg.getTypicalWaitTimeMinutes().getMean();
            medianWait[i] = agg.getMedianWaitTimeMinutes().getMean();
            p90Wait[i] = agg.getP90WaitTimeMinutes().getMean();
            seatUtil[i] = agg.getSeatUtilizationRate().getMean();
            takeaway[i] = agg.getTakeawayRate().getMean();
            maxQueue[i] = agg.getMaxTotalQueueSize().getMean();

            boolean currentPr9dPresent = agg.getPopularServedShare() != null;
            if (!pr9dDetermined) {
                pr9dPresent = currentPr9dPresent;
                pr9dDetermined = true;
            } else if (currentPr9dPresent != pr9dPresent) {
                throw new IllegalStateException(
                        "PR-9D nullness flipped across scan points for axis "
                                + axis.getParameter() + " at point " + i
                                + ":首点 pr9dPresent=" + pr9dPresent
                                + ", 当前 pr9dPresent=" + currentPr9dPresent);
            }
            if (currentPr9dPresent) {
                popularShare[i] = agg.getPopularServedShare().getMean();
                coldShare[i] = agg.getColdServedShare().getMean();
                cv[i] = agg.getWindowServedCountCv().getMean();
            }
        }

        MetricSensitivityCurve popular = pr9dPresent
                ? curve("popularServedShare", popularShare) : null;
        MetricSensitivityCurve cold = pr9dPresent
                ? curve("coldServedShare", coldShare) : null;
        MetricSensitivityCurve cvCurve = pr9dPresent
                ? curve("windowServedCountCv", cv) : null;

        return new AxisResult(
                axis.getParameter(),
                points.clone(),
                curve("arrivedCount", arrived),
                curve("servedCount", served),
                curve("typicalWaitTimeMinutes", typicalWait),
                curve("medianWaitTimeMinutes", medianWait),
                curve("p90WaitTimeMinutes", p90Wait),
                curve("seatUtilizationRate", seatUtil),
                curve("takeawayRate", takeaway),
                curve("maxTotalQueueSize", maxQueue),
                popular, cold, cvCurve);
    }

    static MetricSensitivityCurve curve(String name, double[] meanAtPoint) {
        double summary = summarySensitivity(meanAtPoint);
        return new MetricSensitivityCurve(name, meanAtPoint.clone(), summary);
    }

    /**
     * {@code (max - min) / max(|centerY|, 1e-9)};{@code centerY = meanAtPoint[length / 2]}。
     * length=1 时 max=min,summary=0。length=2 时 centerIdx=1。
     */
    static double summarySensitivity(double[] meanAtPoint) {
        if (meanAtPoint.length == 0) {
            return 0.0;
        }
        double max = meanAtPoint[0];
        double min = meanAtPoint[0];
        for (double v : meanAtPoint) {
            if (v > max) max = v;
            if (v < min) min = v;
        }
        double center = meanAtPoint[meanAtPoint.length / 2];
        double denom = Math.max(Math.abs(center), 1e-9);
        return (max - min) / denom;
    }

    private SimConfig cloneConfig(SimConfig source) {
        SimConfig safe = source == null ? new SimConfig() : source;
        return reportMapper.convertValue(safe, SimConfig.class);
    }

    /**
     * digest 表示**业务配置**,清空 seed 字段后再哈希。算法与 010A
     * {@code BatchRunService.computeBaseConfigDigest} 完全一致(独立实现一次,不为 010C 重构 010A)。
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
