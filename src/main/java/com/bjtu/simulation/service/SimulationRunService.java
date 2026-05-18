package com.bjtu.simulation.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bjtu.simulation.dto.QueueTheoryMetrics;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.bjtu.simulation.dto.TakeawayRateBreakdown;
import com.bjtu.simulation.dto.WaitTimeMetrics;
import com.bjtu.simulation.engine.SimulationEngine;
import com.bjtu.simulation.model.ArrivalSample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SimulationRunService {
    private static final String REPORT_VERSION = "1.9.0";

    private final SimulationConfigNormalizer configNormalizer;
    private final SimulationArrivalScheduler arrivalScheduler;
    private final SimulationTimelineBuilder timelineBuilder;
    private final QueueTheoryMetricsCalculator queueTheoryMetricsCalculator;
    private final WaitTimeMetricsCalculator waitTimeMetricsCalculator;

    public SimulationRunService() {
        this(new SimulationConfigNormalizer(),
                new SimulationArrivalScheduler(),
                new SimulationTimelineBuilder(),
                new QueueTheoryMetricsCalculator(),
                new WaitTimeMetricsCalculator());
    }

    @Autowired
    public SimulationRunService(SimulationConfigNormalizer configNormalizer,
                                SimulationArrivalScheduler arrivalScheduler,
                                SimulationTimelineBuilder timelineBuilder,
                                QueueTheoryMetricsCalculator queueTheoryMetricsCalculator,
                                WaitTimeMetricsCalculator waitTimeMetricsCalculator) {
        this.configNormalizer = configNormalizer;
        this.arrivalScheduler = arrivalScheduler;
        this.timelineBuilder = timelineBuilder;
        this.queueTheoryMetricsCalculator = queueTheoryMetricsCalculator;
        this.waitTimeMetricsCalculator = waitTimeMetricsCalculator;
    }

    public SimulationReport run(SimConfig inputConfig) {
        return run(inputConfig, UUID.randomUUID().toString());
    }

    public SimulationReport run(SimConfig inputConfig, String reportId) {
        SimConfig config = configNormalizer.normalize(inputConfig);
        SimulationEngine engine = new SimulationEngine(config);
        long durationSeconds = Math.max(1L, Math.round(config.getDuration() * 3600.0));

        arrivalScheduler.schedule(engine, config, durationSeconds);
        engine.runAll();

        SimulationSummary summary = buildSummary(config, engine);
        return new SimulationReport(
                REPORT_VERSION,
                reportId,
                engine.getEffectiveSeed(),
                config,
                summary,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                System.currentTimeMillis());
    }

    private SimulationSummary buildSummary(SimConfig config, SimulationEngine engine) {
        int totalSeats = engine.getCanteenState().getTotalSeats();
        int occupiedSeats = engine.getCanteenState().getOccupiedSeats();
        int emptySeats = Math.max(0, totalSeats - occupiedSeats);
        long endTimeSeconds = engine.getCurrentTime();
        long endTimeMinutes = endTimeSeconds / 60;
        long utilizationWindowSeconds = Math.max(1L, Math.round(config.getDuration() * 3600.0));
        double integratedAvgOccupiedSeats = endTimeSeconds <= 0
                ? engine.getAvgOccupiedSeats()
                : engine.getOccupiedSeatSeconds() / (double) utilizationWindowSeconds;
        integratedAvgOccupiedSeats = Math.min(totalSeats, Math.max(0.0, integratedAvgOccupiedSeats));
        double seatUtilizationRate = totalSeats == 0 ? 0 : Math.min(1.0, integratedAvgOccupiedSeats / totalSeats);
        int windowCount = config.getBaseConfig().getWindowCount();
        int normalWindowCount = engine.getNormalWindowCount();
        int takeawayWindowCount = engine.getTakeawayWindowCount();
        int normalWindowServedCount = engine.getNormalWindowServedCount();
        int takeawayWindowServedCount = engine.getTakeawayWindowServedCount();
        QueueTheoryMetrics queueTheoryMetrics = queueTheoryMetricsCalculator.build(config, windowCount);
        WaitTimeMetrics waitTimeMetrics = waitTimeMetricsCalculator.build(
                engine.getWaitTimeSamples(),
                engine.getMaxTotalQueueSize(),
                seatUtilizationRate);
        var arrivalSamples = new ArrayList<>(engine.getArrivalSamples());
        var takeawayDecisionRecords = new ArrayList<>(engine.getTakeawayDecisionRecords());

        List<SimulationTimePoint> timeline = timelineBuilder.build(
                engine.getHistory(),
                windowCount,
                totalSeats,
                engine.getWindowTypes(),
                normalWindowCount,
                takeawayWindowCount,
                engine.getWaitTimeSamples());

        double theoreticalTakeawayRate = computeTheoreticalTakeawayRate(config);
        TakeawayRateBreakdown takeawayRateBreakdown = buildTakeawayRateBreakdown(
                engine, theoreticalTakeawayRate);

        return new SimulationSummary(
                engine.getHistory(),
                timeline,
                engine.getArrivedCount(),
                engine.getNormalArrivalCount(),
                engine.getClassPeakArrivalCount(),
                engine.getRainPeakArrivalCount(),
                engine.getAbandonedCount(),
                engine.getAbandonedByQueueCount(),
                engine.getServedCount(),
                engine.getDineInCount(),
                engine.getTakeawayCount(),
                engine.getPendingSeatDecisionCount(),
                engine.getNoSeatSwitchToTakeawayCount(),
                engine.getWeatherDrivenTakeawayCount(),
                engine.getLeaveCount(),
                SimulationMath.round3(engine.getAvgWaitTimeMinutes()),
                SimulationMath.round3(engine.getTotalWaitTimeMinutes()),
                waitTimeMetrics,
                SimulationMath.round3(engine.getAvgMovementTimeMinutes()),
                SimulationMath.round3(engine.getTotalMovementTimeMinutes()),
                engine.getMovementSampleCount(),
                engine.getPeakTime() / 60,
                engine.getTotalPeakTime() / 60,
                engine.getPeakWindowId(),
                engine.getMaxQueueSizeEver(),
                engine.getMaxTotalQueueSize(),
                SimulationMath.round3(engine.getAvgTotalQueueSize()),
                engine.getMaxOccupiedSeats(),
                SimulationMath.round3(integratedAvgOccupiedSeats),
                SimulationMath.round3(seatUtilizationRate),
                new ArrayList<>(engine.getWindowServedCounts()),
                new ArrayList<>(engine.getWindowTypes()),
                normalWindowCount,
                takeawayWindowCount,
                normalWindowServedCount,
                takeawayWindowServedCount,
                SimulationMath.rate(engine.getTakeawayCount(), engine.getServedCount()),
                SimulationMath.rate(engine.getDineInCount(), engine.getServedCount()),
                SimulationMath.rate(takeawayWindowCount, windowCount),
                SimulationMath.rate(normalWindowServedCount, engine.getServedCount()),
                SimulationMath.rate(takeawayWindowServedCount, engine.getServedCount()),
                endTimeSeconds,
                endTimeMinutes,
                totalSeats,
                occupiedSeats,
                emptySeats,
                engine.getTableSnapshots(),
                engine.getSeatCells(),
                arrivalSamples,
                takeawayDecisionRecords,
                buildProbabilityModel(config, arrivalSamples, takeawayDecisionRecords.size()),
                queueTheoryMetrics,
                engine.getGroupCount(),
                engine.getGroupedStudentCount(),
                SimulationMath.rate(engine.getGroupedStudentCount(), engine.getGroupCount()),
                SimulationMath.rate(engine.getSameTableGroupCount(), engine.getGroupCount()),
                SimulationMath.rate(engine.getSplitGroupCount(), engine.getGroupCount()),
                engine.getNoSeatAbandonedCount(),
                SimulationMath.rate(engine.getNoSeatAbandonedCount(),
                        Math.max(1, engine.getArrivedCount())),
                engine.getSeatWaitQueueMax(),
                SimulationMath.round3(engine.getSeatWaitAvgSeconds()),
                SimulationMath.round3(engine.getReservedSeatsAvg()),
                computeTimeWeightedUtilization(engine.getTableSnapshots(), totalSeats, endTimeSeconds),
                SimulationMath.rate(engine.getDineInCount(), totalSeats),
                SimulationMath.rate(engine.getMaxOccupiedSeats(), totalSeats),
                computeSteadyStateUtilization(timeline, totalSeats),
                theoreticalTakeawayRate,
                takeawayRateBreakdown);
    }

    private double computeTheoreticalTakeawayRate(SimConfig config) {
        if (config == null) {
            return 0.0;
        }
        double basePack = SimulationMath.clamp(config.getPackProbability(), 0.0, 1.0);
        SimConfig.WeatherConfig weatherConfig = config.getWeatherConfig();
        String weatherType = weatherConfig == null ? null : weatherConfig.getCurrentWeather();
        double userFactor = weatherConfig == null ? 1.0 : weatherConfig.getWeatherImpactFactor();
        double effective = WeatherFactorPolicy.resolveEffectiveFactor(weatherType, userFactor);
        return SimulationMath.clamp(basePack * effective, 0.0, 0.95);
    }

    private TakeawayRateBreakdown buildTakeawayRateBreakdown(SimulationEngine engine,
                                                             double theoreticalTakeawayRate) {
        int arrived = Math.max(0, engine.getArrivedCount());
        int served = Math.max(0, engine.getServedCount());
        double initialIntentRate = arrived == 0
                ? 0.0
                : SimulationMath.clamp((double) engine.getInitialTakeawayIntentCount() / arrived, 0.0, 1.0);
        double dynamicFlipRate = arrived == 0
                ? 0.0
                : SimulationMath.clamp((double) engine.getWeatherDrivenTakeawayCount() / arrived, 0.0, 1.0);
        double noSeatForcedRate = arrived == 0
                ? 0.0
                : SimulationMath.clamp((double) engine.getNoSeatSwitchToTakeawayCount() / arrived, 0.0, 1.0);
        double observedRate = served == 0
                ? 0.0
                : SimulationMath.clamp((double) engine.getTakeawayCount() / served, 0.0, 1.0);
        return new TakeawayRateBreakdown(initialIntentRate, dynamicFlipRate, noSeatForcedRate,
                observedRate, theoreticalTakeawayRate);
    }

    private double computeTimeWeightedUtilization(List<com.bjtu.simulation.model.TableSnapshot> snapshots,
                                                  int totalSeats,
                                                  long endTimeSeconds) {
        if (snapshots == null || snapshots.isEmpty() || totalSeats <= 0 || endTimeSeconds <= 0) {
            return 0.0;
        }
        long occupiedSeatSeconds = 0L;
        for (var snapshot : snapshots) {
            occupiedSeatSeconds += Math.max(0L, snapshot.getOccupiedSeatSeconds());
        }
        double denominator = (double) totalSeats * (double) endTimeSeconds;
        return denominator <= 0 ? 0.0 : occupiedSeatSeconds / denominator;
    }

    private double computeSteadyStateUtilization(List<SimulationTimePoint> timeline, int totalSeats) {
        if (timeline == null || timeline.isEmpty() || totalSeats <= 0) {
            return 0.0;
        }
        int n = timeline.size();
        int from = (int) Math.floor(n * 0.1);
        int to = (int) Math.ceil(n * 0.9);
        if (to <= from) {
            from = 0;
            to = n;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < to; i++) {
            sum += timeline.get(i).getSeatUtilizationRate();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private com.bjtu.simulation.dto.ProbabilityModelSummary buildProbabilityModel(SimConfig config,
                                                                                  List<ArrivalSample> arrivalSamples,
                                                                                  int takeawayDecisionSampleCount) {
        double lambdaPerHour = Math.max(0.0, config.getArrivalRate());
        double expectedMeanInterval = lambdaPerHour <= 0 ? 0.0 : 3600.0 / lambdaPerHour;
        double observedMeanInterval = averageInterval(arrivalSamples);
        double durationHours = Math.max(1.0e-6, config.getDuration());
        double observedRatePerHour = arrivalSamples == null || arrivalSamples.isEmpty()
                ? 0.0
                : arrivalSamples.size() / durationHours;
        double accuracy;
        if (lambdaPerHour <= 0 || observedRatePerHour <= 0) {
            accuracy = 0.0;
        } else {
            double rateAccuracy = SimulationMath.clamp(
                    1.0 - Math.abs(observedRatePerHour - lambdaPerHour) / lambdaPerHour,
                    0.0, 1.0);
            double intervalAccuracy = expectedMeanInterval > 0 && observedMeanInterval > 0
                    ? SimulationMath.clamp(
                            1.0 - Math.abs(observedMeanInterval - expectedMeanInterval) / expectedMeanInterval,
                            0.0, 1.0)
                    : rateAccuracy;
            accuracy = (rateAccuracy + intervalAccuracy) / 2.0;
        }
        return new com.bjtu.simulation.dto.ProbabilityModelSummary(
                normalizeDistributionName(config.getArrivalDist(), "POISSON"),
                interarrivalDistributionName(config),
                normalizeDistributionName(config.getNormalServiceDist(), "EXPONENTIAL"),
                normalizeDistributionName(config.getDiningTimeDist(), "UNIFORM"),
                lambdaPerHour,
                expectedMeanInterval,
                observedMeanInterval,
                accuracy,
                minuteVarianceMeanRatio(arrivalSamples),
                arrivalSamples.size(),
                takeawayDecisionSampleCount);
    }

    private String interarrivalDistributionName(SimConfig config) {
        if (config != null
                && config.getRandomBounds() != null
                && config.getRandomBounds().getArrivalInterval() > 0) {
            return "FIXED_INTERVAL";
        }
        return "NEGATIVE_EXPONENTIAL";
    }

    private double averageInterval(List<ArrivalSample> arrivalSamples) {
        if (arrivalSamples == null || arrivalSamples.size() < 2) {
            return 0.0;
        }
        long sum = 0L;
        int counted = 0;
        for (int i = 1; i < arrivalSamples.size(); i++) {
            long interval = Math.max(0L, arrivalSamples.get(i).getIntervalSeconds());
            if (interval == 0L) {
                continue;
            }
            sum += interval;
            counted++;
        }
        return counted == 0 ? 0.0 : (double) sum / counted;
    }

    private double minuteVarianceMeanRatio(List<ArrivalSample> arrivalSamples) {
        if (arrivalSamples == null || arrivalSamples.isEmpty()) {
            return 0.0;
        }
        java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        long maxMinute = 0L;
        for (ArrivalSample sample : arrivalSamples) {
            counts.merge(sample.getMinute(), sample.getPartySize(), Integer::sum);
            maxMinute = Math.max(maxMinute, sample.getMinute());
        }
        int buckets = (int) Math.max(1L, maxMinute + 1L);
        double mean = 0.0;
        for (int minute = 0; minute < buckets; minute++) {
            mean += counts.getOrDefault((long) minute, 0);
        }
        mean /= buckets;
        if (mean <= 0.0) {
            return 0.0;
        }
        double variance = 0.0;
        for (int minute = 0; minute < buckets; minute++) {
            double diff = counts.getOrDefault((long) minute, 0) - mean;
            variance += diff * diff;
        }
        variance /= buckets;
        return variance / mean;
    }

    private String normalizeDistributionName(SimConfig.DistributionSpec spec, String fallback) {
        if (spec == null || spec.getType() == null || spec.getType().isBlank()) {
            return fallback;
        }
        return spec.getType().trim().toUpperCase();
    }

}
