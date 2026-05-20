package com.bjtu.simulation.dto;

import java.util.List;

import com.bjtu.simulation.model.ArrivalSample;
import com.bjtu.simulation.model.SeatCellSnapshot;
import com.bjtu.simulation.model.TableSnapshot;
import com.bjtu.simulation.model.TakeawayDecisionRecord;

public class SimulationSummary {
    private final List<SimulationResult> history;
    private final List<SimulationTimePoint> timeline;

    private final int arrivedCount;
    private final int normalArrivalCount;
    private final int classPeakArrivalCount;
    private final int rainPeakArrivalCount;

    private final int abandonedCount;
    private final int abandonedByQueueCount;

    private final int servedCount;
    private final int dineInCount;
    private final int takeawayCount;
    private final int pendingSeatDecisionCount;
    private final int noSeatSwitchToTakeawayCount;
    private final int weatherDrivenTakeawayCount;
    private final int leaveCount;

    private final double avgWaitTimeMinutes;
    private final double totalWaitTimeMinutes;
    private final WaitTimeMetrics waitTimeMetrics;
    private final double avgMovementTimeMinutes;
    private final double totalMovementTimeMinutes;
    private final int movementSampleCount;

    private final long peakTimeMinutes;
    private final long totalPeakTimeMinutes;
    private final int peakWindowId;
    private final int maxQueueSize;

    private final int maxTotalQueueSize;
    private final double avgTotalQueueSize;

    private final int maxOccupiedSeats;
    private final double avgOccupiedSeats;
    private final double seatUtilizationRate;

    private final List<Integer> windowServedCounts;
    private final List<String> windowTypes;
    private final int normalWindowCount;
    private final int takeawayWindowCount;
    private final int normalWindowServedCount;
    private final int takeawayWindowServedCount;
    private final double takeawayRate;
    private final double dineInRate;
    private final double takeawayWindowRatio;
    private final double normalWindowServedRate;
    private final double takeawayWindowServedRate;

    private final long simulationEndTimeSeconds;
    private final long simulationEndTimeMinutes;

    private final int totalSeats;
    private final int occupiedSeats;
    private final int emptySeats;
    private final List<TableSnapshot> tableSnapshots;
    private final List<SeatCellSnapshot> seatCells;
    private final List<ArrivalSample> arrivalSamples;
    private final List<TakeawayDecisionRecord> takeawayDecisionRecords;
    private final ProbabilityModelSummary probabilityModel;
    private final QueueTheoryMetrics queueTheoryMetrics;
    private final int groupCount;
    private final int groupedStudentCount;
    private final double avgGroupSize;
    private final double sameTableGroupRate;
    private final double splitGroupRate;
    private final int noSeatAbandonedCount;
    private final double noSeatAbandonedRate;
    private final int seatWaitQueueMax;
    private final double seatWaitAvgSeconds;
    private final double reservedSeatsAvg;

    /** 运营视角:时间加权座位占用率 = 座位×秒 / (totalSeats × 仿真总秒数)。 */
    private final double seatTimeWeightedUtilization;
    /** 吞吐视角:翻台率 = dineInCount / totalSeats。 */
    private final double seatTurnoverRate;
    /** 峰值座位占用率 = maxOccupiedSeats / totalSeats。 */
    private final double peakSeatUtilizationRate;
    /** 稳态座位占用率:排除前 10% 与后 10% 帧后的均值,避免被冷启动/排空拉偏。 */
    private final double steadyStateSeatUtilization;

    /** 基础理论打包率 = clamp(packProbability × effectiveWeatherFactor, 0, 0.95)。
     *  来源 StudentProfileFactory.intentProbability;不含动态翻转和无座强制。 */
    private final double theoreticalTakeawayRate;
    /** 打包率三段分解,辅助理解实际 vs 理论的差距来源。 */
    private final TakeawayRateBreakdown takeawayRateBreakdown;

    public SimulationSummary(List<SimulationResult> history,
                             List<SimulationTimePoint> timeline,
                             int arrivedCount,
                             int normalArrivalCount,
                             int classPeakArrivalCount,
                             int rainPeakArrivalCount,
                             int abandonedCount,
                             int abandonedByQueueCount,
                             int servedCount,
                             int dineInCount,
                             int takeawayCount,
                             int pendingSeatDecisionCount,
                             int noSeatSwitchToTakeawayCount,
                             int weatherDrivenTakeawayCount,
                             int leaveCount,
                             double avgWaitTimeMinutes,
                             double totalWaitTimeMinutes,
                             WaitTimeMetrics waitTimeMetrics,
                             double avgMovementTimeMinutes,
                             double totalMovementTimeMinutes,
                             int movementSampleCount,
                             long peakTimeMinutes,
                             long totalPeakTimeMinutes,
                             int peakWindowId,
                             int maxQueueSize,
                             int maxTotalQueueSize,
                             double avgTotalQueueSize,
                             int maxOccupiedSeats,
                             double avgOccupiedSeats,
                             double seatUtilizationRate,
                             List<Integer> windowServedCounts,
                             List<String> windowTypes,
                             int normalWindowCount,
                             int takeawayWindowCount,
                             int normalWindowServedCount,
                             int takeawayWindowServedCount,
                             double takeawayRate,
                             double dineInRate,
                             double takeawayWindowRatio,
                             double normalWindowServedRate,
                             double takeawayWindowServedRate,
                             long simulationEndTimeSeconds,
                             long simulationEndTimeMinutes,
                             int totalSeats,
                             int occupiedSeats,
                             int emptySeats,
                             List<TableSnapshot> tableSnapshots,
                             List<SeatCellSnapshot> seatCells,
                             List<ArrivalSample> arrivalSamples,
                             List<TakeawayDecisionRecord> takeawayDecisionRecords,
                             ProbabilityModelSummary probabilityModel,
                             QueueTheoryMetrics queueTheoryMetrics,
                             int groupCount,
                             int groupedStudentCount,
                             double avgGroupSize,
                             double sameTableGroupRate,
                             double splitGroupRate,
                             int noSeatAbandonedCount,
                             double noSeatAbandonedRate,
                             int seatWaitQueueMax,
                             double seatWaitAvgSeconds,
                             double reservedSeatsAvg,
                             double seatTimeWeightedUtilization,
                             double seatTurnoverRate,
                             double peakSeatUtilizationRate,
                             double steadyStateSeatUtilization) {
        this(history, timeline, arrivedCount, normalArrivalCount, classPeakArrivalCount,
                rainPeakArrivalCount, abandonedCount, abandonedByQueueCount, servedCount,
                dineInCount, takeawayCount, pendingSeatDecisionCount,
                noSeatSwitchToTakeawayCount, weatherDrivenTakeawayCount, leaveCount,
                avgWaitTimeMinutes, totalWaitTimeMinutes, waitTimeMetrics,
                avgMovementTimeMinutes, totalMovementTimeMinutes, movementSampleCount,
                peakTimeMinutes, totalPeakTimeMinutes, peakWindowId, maxQueueSize,
                maxTotalQueueSize, avgTotalQueueSize, maxOccupiedSeats, avgOccupiedSeats,
                seatUtilizationRate, windowServedCounts, windowTypes, normalWindowCount,
                takeawayWindowCount, normalWindowServedCount, takeawayWindowServedCount,
                takeawayRate, dineInRate, takeawayWindowRatio, normalWindowServedRate,
                takeawayWindowServedRate, simulationEndTimeSeconds, simulationEndTimeMinutes,
                totalSeats, occupiedSeats, emptySeats, tableSnapshots, seatCells,
                arrivalSamples, takeawayDecisionRecords, probabilityModel, queueTheoryMetrics,
                groupCount, groupedStudentCount, avgGroupSize, sameTableGroupRate,
                splitGroupRate, noSeatAbandonedCount, noSeatAbandonedRate, seatWaitQueueMax,
                seatWaitAvgSeconds, reservedSeatsAvg, seatTimeWeightedUtilization,
                seatTurnoverRate, peakSeatUtilizationRate, steadyStateSeatUtilization,
                0.0, null);
    }

    public SimulationSummary(List<SimulationResult> history,
                             List<SimulationTimePoint> timeline,
                             int arrivedCount,
                             int normalArrivalCount,
                             int classPeakArrivalCount,
                             int rainPeakArrivalCount,
                             int abandonedCount,
                             int abandonedByQueueCount,
                             int servedCount,
                             int dineInCount,
                             int takeawayCount,
                             int pendingSeatDecisionCount,
                             int noSeatSwitchToTakeawayCount,
                             int weatherDrivenTakeawayCount,
                             int leaveCount,
                             double avgWaitTimeMinutes,
                             double totalWaitTimeMinutes,
                             WaitTimeMetrics waitTimeMetrics,
                             double avgMovementTimeMinutes,
                             double totalMovementTimeMinutes,
                             int movementSampleCount,
                             long peakTimeMinutes,
                             long totalPeakTimeMinutes,
                             int peakWindowId,
                             int maxQueueSize,
                             int maxTotalQueueSize,
                             double avgTotalQueueSize,
                             int maxOccupiedSeats,
                             double avgOccupiedSeats,
                             double seatUtilizationRate,
                             List<Integer> windowServedCounts,
                             List<String> windowTypes,
                             int normalWindowCount,
                             int takeawayWindowCount,
                             int normalWindowServedCount,
                             int takeawayWindowServedCount,
                             double takeawayRate,
                             double dineInRate,
                             double takeawayWindowRatio,
                             double normalWindowServedRate,
                             double takeawayWindowServedRate,
                             long simulationEndTimeSeconds,
                             long simulationEndTimeMinutes,
                             int totalSeats,
                             int occupiedSeats,
                             int emptySeats,
                             List<TableSnapshot> tableSnapshots,
                             List<SeatCellSnapshot> seatCells,
                             List<ArrivalSample> arrivalSamples,
                             List<TakeawayDecisionRecord> takeawayDecisionRecords,
                             ProbabilityModelSummary probabilityModel,
                             QueueTheoryMetrics queueTheoryMetrics,
                             int groupCount,
                             int groupedStudentCount,
                             double avgGroupSize,
                             double sameTableGroupRate,
                             double splitGroupRate,
                             int noSeatAbandonedCount,
                             double noSeatAbandonedRate,
                             int seatWaitQueueMax,
                             double seatWaitAvgSeconds,
                             double reservedSeatsAvg,
                             double seatTimeWeightedUtilization,
                             double seatTurnoverRate,
                             double peakSeatUtilizationRate,
                             double steadyStateSeatUtilization,
                             double theoreticalTakeawayRate,
                             TakeawayRateBreakdown takeawayRateBreakdown) {
        this.history = history;
        this.timeline = timeline;
        this.arrivedCount = arrivedCount;
        this.normalArrivalCount = normalArrivalCount;
        this.classPeakArrivalCount = classPeakArrivalCount;
        this.rainPeakArrivalCount = rainPeakArrivalCount;
        this.abandonedCount = abandonedCount;
        this.abandonedByQueueCount = abandonedByQueueCount;
        this.servedCount = servedCount;
        this.dineInCount = dineInCount;
        this.takeawayCount = takeawayCount;
        this.pendingSeatDecisionCount = pendingSeatDecisionCount;
        this.noSeatSwitchToTakeawayCount = noSeatSwitchToTakeawayCount;
        this.weatherDrivenTakeawayCount = weatherDrivenTakeawayCount;
        this.leaveCount = leaveCount;
        this.avgWaitTimeMinutes = avgWaitTimeMinutes;
        this.totalWaitTimeMinutes = totalWaitTimeMinutes;
        this.waitTimeMetrics = waitTimeMetrics == null ? WaitTimeMetrics.empty() : waitTimeMetrics;
        this.avgMovementTimeMinutes = avgMovementTimeMinutes;
        this.totalMovementTimeMinutes = totalMovementTimeMinutes;
        this.movementSampleCount = movementSampleCount;
        this.peakTimeMinutes = peakTimeMinutes;
        this.totalPeakTimeMinutes = totalPeakTimeMinutes;
        this.peakWindowId = peakWindowId;
        this.maxQueueSize = maxQueueSize;
        this.maxTotalQueueSize = maxTotalQueueSize;
        this.avgTotalQueueSize = avgTotalQueueSize;
        this.maxOccupiedSeats = maxOccupiedSeats;
        this.avgOccupiedSeats = avgOccupiedSeats;
        this.seatUtilizationRate = seatUtilizationRate;
        this.windowServedCounts = windowServedCounts;
        this.windowTypes = windowTypes;
        this.normalWindowCount = normalWindowCount;
        this.takeawayWindowCount = takeawayWindowCount;
        this.normalWindowServedCount = normalWindowServedCount;
        this.takeawayWindowServedCount = takeawayWindowServedCount;
        this.takeawayRate = takeawayRate;
        this.dineInRate = dineInRate;
        this.takeawayWindowRatio = takeawayWindowRatio;
        this.normalWindowServedRate = normalWindowServedRate;
        this.takeawayWindowServedRate = takeawayWindowServedRate;
        this.simulationEndTimeSeconds = simulationEndTimeSeconds;
        this.simulationEndTimeMinutes = simulationEndTimeMinutes;
        this.totalSeats = totalSeats;
        this.occupiedSeats = occupiedSeats;
        this.emptySeats = emptySeats;
        this.tableSnapshots = tableSnapshots;
        this.seatCells = seatCells;
        this.arrivalSamples = arrivalSamples;
        this.takeawayDecisionRecords = takeawayDecisionRecords;
        this.probabilityModel = probabilityModel;
        this.queueTheoryMetrics = queueTheoryMetrics;
        this.groupCount = groupCount;
        this.groupedStudentCount = groupedStudentCount;
        this.avgGroupSize = avgGroupSize;
        this.sameTableGroupRate = sameTableGroupRate;
        this.splitGroupRate = splitGroupRate;
        this.noSeatAbandonedCount = noSeatAbandonedCount;
        this.noSeatAbandonedRate = noSeatAbandonedRate;
        this.seatWaitQueueMax = seatWaitQueueMax;
        this.seatWaitAvgSeconds = seatWaitAvgSeconds;
        this.reservedSeatsAvg = reservedSeatsAvg;
        this.seatTimeWeightedUtilization = round3(seatTimeWeightedUtilization);
        this.seatTurnoverRate = round3(seatTurnoverRate);
        this.peakSeatUtilizationRate = round3(peakSeatUtilizationRate);
        this.steadyStateSeatUtilization = round3(steadyStateSeatUtilization);
        this.theoreticalTakeawayRate = round3(theoreticalTakeawayRate);
        this.takeawayRateBreakdown = takeawayRateBreakdown;
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    public List<SimulationResult> getHistory() {
        return history;
    }

    public List<SimulationTimePoint> getTimeline() {
        return timeline;
    }

    public int getArrivedCount() {
        return arrivedCount;
    }

    public int getNormalArrivalCount() {
        return normalArrivalCount;
    }

    public int getClassPeakArrivalCount() {
        return classPeakArrivalCount;
    }

    public int getRainPeakArrivalCount() {
        return rainPeakArrivalCount;
    }

    public int getAbandonedCount() {
        return abandonedCount;
    }

    public int getAbandonedByQueueCount() {
        return abandonedByQueueCount;
    }

    public int getServedCount() {
        return servedCount;
    }

    public int getDineInCount() {
        return dineInCount;
    }

    public int getTakeawayCount() {
        return takeawayCount;
    }

    public int getPendingSeatDecisionCount() {
        return pendingSeatDecisionCount;
    }

    public int getNoSeatSwitchToTakeawayCount() {
        return noSeatSwitchToTakeawayCount;
    }

    public int getWeatherDrivenTakeawayCount() {
        return weatherDrivenTakeawayCount;
    }

    public int getLeaveCount() {
        return leaveCount;
    }

    public double getAvgWaitTimeMinutes() {
        return avgWaitTimeMinutes;
    }

    public double getTotalWaitTimeMinutes() {
        return totalWaitTimeMinutes;
    }

    public double getRawAvgWaitTimeMinutes() {
        return waitTimeMetrics.getRawAvgWaitTimeMinutes();
    }

    public double getSteadyAvgWaitTimeMinutes() {
        return waitTimeMetrics.getSteadyAvgWaitTimeMinutes();
    }

    public double getTypicalWaitTimeMinutes() {
        return waitTimeMetrics.getTypicalWaitTimeMinutes();
    }

    public double getMedianWaitTimeMinutes() {
        return waitTimeMetrics.getMedianWaitTimeMinutes();
    }

    public double getP75WaitTimeMinutes() {
        return waitTimeMetrics.getP75WaitTimeMinutes();
    }

    public double getP90WaitTimeMinutes() {
        return waitTimeMetrics.getP90WaitTimeMinutes();
    }

    public double getLongWaitRate() {
        return waitTimeMetrics.getLongWaitRate();
    }

    public double getZeroWaitRate() {
        return waitTimeMetrics.getZeroWaitRate();
    }

    public double getEdgeWaitSampleRate() {
        return waitTimeMetrics.getEdgeWaitSampleRate();
    }

    public List<WaitTimeBucket> getWaitTimeDistribution() {
        return waitTimeMetrics.getWaitTimeDistribution();
    }

    public WaitTimeInsight getWaitTimeInsight() {
        return waitTimeMetrics.getWaitTimeInsight();
    }

    public double getAvgMovementTimeMinutes() {
        return avgMovementTimeMinutes;
    }

    public double getTotalMovementTimeMinutes() {
        return totalMovementTimeMinutes;
    }

    public int getMovementSampleCount() {
        return movementSampleCount;
    }

    public long getPeakTimeMinutes() {
        return peakTimeMinutes;
    }

    public long getTotalPeakTimeMinutes() {
        return totalPeakTimeMinutes;
    }

    public int getPeakWindowId() {
        return peakWindowId;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public int getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    public double getAvgTotalQueueSize() {
        return avgTotalQueueSize;
    }

    public int getMaxOccupiedSeats() {
        return maxOccupiedSeats;
    }

    public double getAvgOccupiedSeats() {
        return avgOccupiedSeats;
    }

    public double getSeatUtilizationRate() {
        return seatUtilizationRate;
    }

    public List<Integer> getWindowServedCounts() {
        return windowServedCounts;
    }

    public List<String> getWindowTypes() {
        return windowTypes;
    }

    public int getNormalWindowCount() {
        return normalWindowCount;
    }

    public int getTakeawayWindowCount() {
        return takeawayWindowCount;
    }

    public int getNormalWindowServedCount() {
        return normalWindowServedCount;
    }

    public int getTakeawayWindowServedCount() {
        return takeawayWindowServedCount;
    }

    public double getTakeawayRate() {
        return takeawayRate;
    }

    public double getDineInRate() {
        return dineInRate;
    }

    public double getTakeawayWindowRatio() {
        return takeawayWindowRatio;
    }

    public double getNormalWindowServedRate() {
        return normalWindowServedRate;
    }

    public double getTakeawayWindowServedRate() {
        return takeawayWindowServedRate;
    }

    public long getSimulationEndTimeSeconds() {
        return simulationEndTimeSeconds;
    }

    public long getSimulationEndTimeMinutes() {
        return simulationEndTimeMinutes;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public int getOccupiedSeats() {
        return occupiedSeats;
    }

    public int getEmptySeats() {
        return emptySeats;
    }

    public List<TableSnapshot> getTableSnapshots() {
        return tableSnapshots;
    }

    public List<SeatCellSnapshot> getSeatCells() {
        return seatCells;
    }

    public List<ArrivalSample> getArrivalSamples() {
        return arrivalSamples;
    }

    public List<TakeawayDecisionRecord> getTakeawayDecisionRecords() {
        return takeawayDecisionRecords;
    }

    public ProbabilityModelSummary getProbabilityModel() {
        return probabilityModel;
    }

    public QueueTheoryMetrics getQueueTheoryMetrics() {
        return queueTheoryMetrics;
    }

    public int getGroupCount() {
        return groupCount;
    }

    public int getGroupedStudentCount() {
        return groupedStudentCount;
    }

    public double getAvgGroupSize() {
        return avgGroupSize;
    }

    public double getSameTableGroupRate() {
        return sameTableGroupRate;
    }

    public double getSplitGroupRate() {
        return splitGroupRate;
    }

    public int getNoSeatAbandonedCount() {
        return noSeatAbandonedCount;
    }

    public double getNoSeatAbandonedRate() {
        return noSeatAbandonedRate;
    }

    public int getSeatWaitQueueMax() {
        return seatWaitQueueMax;
    }

    public double getSeatWaitAvgSeconds() {
        return seatWaitAvgSeconds;
    }

    public double getReservedSeatsAvg() {
        return reservedSeatsAvg;
    }

    public double getSeatTimeWeightedUtilization() {
        return seatTimeWeightedUtilization;
    }

    public double getSeatTurnoverRate() {
        return seatTurnoverRate;
    }

    public double getPeakSeatUtilizationRate() {
        return peakSeatUtilizationRate;
    }

    public double getSteadyStateSeatUtilization() {
        return steadyStateSeatUtilization;
    }

    public double getTheoreticalTakeawayRate() {
        return theoreticalTakeawayRate;
    }

    public TakeawayRateBreakdown getTakeawayRateBreakdown() {
        return takeawayRateBreakdown;
    }
}
