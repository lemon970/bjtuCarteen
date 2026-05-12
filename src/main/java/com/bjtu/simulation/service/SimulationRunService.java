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
import com.bjtu.simulation.engine.SimulationEngine;

public class SimulationRunService {
    private static final String REPORT_VERSION = "1.8.0";

    private final SimulationConfigNormalizer configNormalizer;
    private final SimulationArrivalScheduler arrivalScheduler;
    private final SimulationTimelineBuilder timelineBuilder;
    private final QueueTheoryMetricsCalculator queueTheoryMetricsCalculator;

    public SimulationRunService() {
        this(new SimulationConfigNormalizer(),
                new SimulationArrivalScheduler(),
                new SimulationTimelineBuilder(),
                new QueueTheoryMetricsCalculator());
    }

    public SimulationRunService(SimulationConfigNormalizer configNormalizer,
                                SimulationArrivalScheduler arrivalScheduler,
                                SimulationTimelineBuilder timelineBuilder,
                                QueueTheoryMetricsCalculator queueTheoryMetricsCalculator) {
        this.configNormalizer = configNormalizer;
        this.arrivalScheduler = arrivalScheduler;
        this.timelineBuilder = timelineBuilder;
        this.queueTheoryMetricsCalculator = queueTheoryMetricsCalculator;
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
        double seatUtilizationRate = totalSeats == 0 ? 0 : engine.getAvgOccupiedSeats() / totalSeats;
        int windowCount = config.getBaseConfig().getWindowCount();
        int normalWindowCount = engine.getNormalWindowCount();
        int takeawayWindowCount = engine.getTakeawayWindowCount();
        int normalWindowServedCount = engine.getNormalWindowServedCount();
        int takeawayWindowServedCount = engine.getTakeawayWindowServedCount();
        QueueTheoryMetrics queueTheoryMetrics = queueTheoryMetricsCalculator.build(config, windowCount);

        List<SimulationTimePoint> timeline = timelineBuilder.build(
                engine.getHistory(),
                windowCount,
                totalSeats,
                engine.getWindowTypes(),
                normalWindowCount,
                takeawayWindowCount);

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
                round3(engine.getAvgWaitTimeMinutes()),
                round3(engine.getTotalWaitTimeMinutes()),
                round3(engine.getAvgMovementTimeMinutes()),
                round3(engine.getTotalMovementTimeMinutes()),
                engine.getMovementSampleCount(),
                engine.getPeakTime() / 60,
                engine.getPeakWindowId(),
                engine.getMaxQueueSizeEver(),
                engine.getMaxTotalQueueSize(),
                round3(engine.getAvgTotalQueueSize()),
                engine.getMaxOccupiedSeats(),
                round3(engine.getAvgOccupiedSeats()),
                round3(seatUtilizationRate),
                new ArrayList<>(engine.getWindowServedCounts()),
                new ArrayList<>(engine.getWindowTypes()),
                normalWindowCount,
                takeawayWindowCount,
                normalWindowServedCount,
                takeawayWindowServedCount,
                rate(engine.getTakeawayCount(), engine.getServedCount()),
                rate(engine.getDineInCount(), engine.getServedCount()),
                rate(takeawayWindowCount, windowCount),
                rate(normalWindowServedCount, engine.getServedCount()),
                rate(takeawayWindowServedCount, engine.getServedCount()),
                endTimeSeconds,
                endTimeMinutes,
                totalSeats,
                occupiedSeats,
                emptySeats,
                engine.getTableSnapshots(),
                queueTheoryMetrics);
    }

    private double rate(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : round3((double) numerator / denominator);
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
