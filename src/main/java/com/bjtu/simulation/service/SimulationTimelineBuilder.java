package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.SimulationResult;
import com.bjtu.simulation.dto.SimulationTimePoint;
import com.bjtu.simulation.model.WaitTimeSample;

import org.springframework.stereotype.Service;

@Service
public class SimulationTimelineBuilder {
    // 16h 仿真上限 = 960 分钟，加上末尾 dining 长尾后 endMinute 可达 ~1100；
    // 上限保持为 2000 以确保 stepMinutes 始终为 1（保证 timeline 分钟级语义）。
    private static final int MAX_TIMELINE_POINTS = 2000;
    private static final long WAIT_WINDOW_SECONDS = 300L;

    public List<SimulationTimePoint> build(List<SimulationResult> history,
                                           int windowCount,
                                           int totalSeats,
                                           List<String> windowTypes,
                                           int normalWindowCount,
                                           int takeawayWindowCount) {
        return build(history, windowCount, totalSeats, windowTypes, normalWindowCount, takeawayWindowCount, List.of());
    }

    public List<SimulationTimePoint> build(List<SimulationResult> history,
                                           int windowCount,
                                           int totalSeats,
                                           List<String> windowTypes,
                                           int normalWindowCount,
                                           int takeawayWindowCount,
                                           List<WaitTimeSample> waitTimeSamples) {
        List<SimulationTimePoint> timeline = new ArrayList<>();
        List<String> normalizedWindowTypes = normalizeWindowTypes(windowTypes, windowCount, normalWindowCount);
        List<WaitTimeSample> safeSamples = waitTimeSamples == null ? List.of() : waitTimeSamples;
        if (history == null || history.isEmpty()) {
            timeline.add(emptyTimePoint(0, windowCount, totalSeats, normalizedWindowTypes, normalWindowCount, takeawayWindowCount));
            return timeline;
        }

        long endMinute = history.get(history.size() - 1).getTime() / 60;
        int cursor = 0;
        SimulationResult last = null;

        long stepMinutes = Math.max(1L, (long) Math.ceil((endMinute + 1) / (double) MAX_TIMELINE_POINTS));
        for (long minute = 0; minute <= endMinute; minute += stepMinutes) {
            long minuteEndExclusive = (minute * 60) + 60;
            while (cursor < history.size() && history.get(cursor).getTime() < minuteEndExclusive) {
                last = history.get(cursor);
                cursor++;
            }

            if (last == null) {
                timeline.add(emptyTimePoint(minute, windowCount, totalSeats, normalizedWindowTypes, normalWindowCount, takeawayWindowCount));
            } else {
                timeline.add(toTimePoint(minute, last, windowCount, totalSeats, normalizedWindowTypes,
                        normalWindowCount, takeawayWindowCount, safeSamples));
            }
        }
        return timeline;
    }

    private WaitWindowStats computeWaitWindow(long currentTimeSeconds, List<WaitTimeSample> samples) {
        if (samples == null || samples.isEmpty() || currentTimeSeconds < 0) {
            return new WaitWindowStats(0.0, 0);
        }
        long lowerInclusive = Math.max(0L, currentTimeSeconds - WAIT_WINDOW_SECONDS);
        double weightedSum = 0.0;
        int weightedCount = 0;
        for (WaitTimeSample sample : samples) {
            long serviceStart = sample.getServiceStartTimeSeconds();
            if (serviceStart > currentTimeSeconds || serviceStart < lowerInclusive) {
                continue;
            }
            int weight = Math.max(1, sample.getPartySize());
            weightedSum += sample.getWaitMinutes() * weight;
            weightedCount += weight;
        }
        if (weightedCount == 0) {
            return new WaitWindowStats(0.0, 0);
        }
        return new WaitWindowStats(round3(weightedSum / weightedCount), weightedCount);
    }

    private record WaitWindowStats(double avgWaitMinutes, int sampleCount) {
    }

    private SimulationTimePoint emptyTimePoint(long minute,
                                               int windowCount,
                                               int totalSeats,
                                               List<String> windowTypes,
                                               int normalWindowCount,
                                               int takeawayWindowCount) {
        int safeTotalSeats = Math.max(0, totalSeats);
        double freeRate = safeTotalSeats > 0 ? 1.0 : 0.0;
        return new SimulationTimePoint(
                minute * 60,
                minute,
                zeroQueues(windowCount),
                windowTypes,
                Math.max(0, windowCount),
                Math.max(0, normalWindowCount),
                Math.max(0, takeawayWindowCount),
                0,
                0,
                0,
                0,
                -1,
                0,
                safeTotalSeats,
                0,
                0,
                safeTotalSeats,
                0,
                "",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0.0,
                0.0,
                0.0,
                0,
                List.of(),
                0,
                0.0,
                0.0,
                freeRate);
    }

    private SimulationTimePoint toTimePoint(long minute,
                                            SimulationResult last,
                                            int windowCount,
                                            int totalSeats,
                                            List<String> windowTypes,
                                            int normalWindowCount,
                                            int takeawayWindowCount,
                                            List<WaitTimeSample> waitTimeSamples) {
        List<Integer> queues = normalizeQueues(last.getQueueSizes(), windowCount);
        int totalQueueSize = sum(queues);
        int normalWindowQueueSize = sumRange(queues, 0, normalWindowCount);
        int takeawayWindowQueueSize = sumRange(queues, normalWindowCount, normalWindowCount + takeawayWindowCount);
        int occupiedSeats = Math.max(0, last.getOccupiedSeats());
        int safeTotalSeats = Math.max(0, totalSeats);
        int emptySeats = Math.max(0, safeTotalSeats - occupiedSeats);
        double seatUtilizationRate = safeTotalSeats == 0 ? 0 : round3((double) occupiedSeats / safeTotalSeats);
        WaitWindowStats waitWindow = computeWaitWindow(last.getTime(), waitTimeSamples);

        int reservedSeats = 0;
        if (last.getTableSnapshots() != null) {
            for (var snapshot : last.getTableSnapshots()) {
                reservedSeats += Math.max(0, snapshot.getReservedSeats());
            }
        }
        reservedSeats = Math.min(reservedSeats, Math.max(0, safeTotalSeats - occupiedSeats));
        double seatUnavailableRate = safeTotalSeats == 0
                ? 0.0
                : (double) (occupiedSeats + reservedSeats) / safeTotalSeats;
        double seatReservedShare = safeTotalSeats == 0
                ? 0.0
                : (double) reservedSeats / safeTotalSeats;
        double seatFreeRate = safeTotalSeats == 0
                ? 0.0
                : Math.max(0.0, 1.0 - seatUnavailableRate);

        return new SimulationTimePoint(
                last.getTime(),
                minute,
                queues,
                windowTypes,
                queues.size(),
                Math.max(0, normalWindowCount),
                Math.max(0, takeawayWindowCount),
                totalQueueSize,
                normalWindowQueueSize,
                takeawayWindowQueueSize,
                totalQueueSize,
                busiestWindowId(queues),
                busiestWindowQueueSize(queues),
                safeTotalSeats,
                occupiedSeats,
                occupiedSeats,
                emptySeats,
                seatUtilizationRate,
                last.getEventMessage(),
                last.getArrivedCount(),
                last.getNormalArrivalCount(),
                last.getClassPeakArrivalCount(),
                last.getRainPeakArrivalCount(),
                last.getAbandonedCount(),
                last.getAbandonedByQueueCount(),
                last.getServedCount(),
                last.getDineInCount(),
                last.getTakeawayCount(),
                last.getPendingSeatDecisionCount(),
                last.getNoSeatSwitchToTakeawayCount(),
                last.getWeatherDrivenTakeawayCount(),
                last.getLeaveCount(),
                last.getMovementSampleCount(),
                round3(last.getTotalMovementTimeMinutes()),
                round3(last.getAvgMovementTimeMinutes()),
                waitWindow.avgWaitMinutes(),
                waitWindow.sampleCount(),
                last.getTableSnapshots() == null ? List.of() : last.getTableSnapshots(),
                reservedSeats,
                seatUnavailableRate,
                seatReservedShare,
                seatFreeRate);
    }

    private List<Integer> normalizeQueues(List<Integer> source, int windowCount) {
        List<Integer> queues = new ArrayList<>();
        int safeWindowCount = Math.max(0, windowCount);
        if (source != null) {
            for (int i = 0; i < Math.min(source.size(), safeWindowCount); i++) {
                queues.add(Math.max(0, source.get(i)));
            }
        }
        while (queues.size() < safeWindowCount) {
            queues.add(0);
        }
        return queues;
    }

    private List<String> normalizeWindowTypes(List<String> source, int windowCount, int normalWindowCount) {
        List<String> types = new ArrayList<>();
        int safeWindowCount = Math.max(0, windowCount);
        if (source != null) {
            for (int i = 0; i < Math.min(source.size(), safeWindowCount); i++) {
                String type = source.get(i);
                types.add(type == null || type.isBlank() ? fallbackWindowType(i, normalWindowCount) : type);
            }
        }
        while (types.size() < safeWindowCount) {
            types.add(fallbackWindowType(types.size(), normalWindowCount));
        }
        return types;
    }

    private String fallbackWindowType(int windowId, int normalWindowCount) {
        return windowId >= Math.max(0, normalWindowCount) ? "TAKEAWAY" : "NORMAL";
    }

    private int sum(List<Integer> values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private int sumRange(List<Integer> values, int fromInclusive, int toExclusive) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int total = 0;
        int from = Math.max(0, fromInclusive);
        int to = Math.min(values.size(), Math.max(from, toExclusive));
        for (int i = from; i < to; i++) {
            total += Math.max(0, values.get(i));
        }
        return total;
    }

    private int busiestWindowId(List<Integer> queues) {
        if (queues == null || queues.isEmpty()) {
            return -1;
        }
        int busiestId = 0;
        int busiestSize = queues.get(0);
        for (int i = 1; i < queues.size(); i++) {
            if (queues.get(i) > busiestSize) {
                busiestSize = queues.get(i);
                busiestId = i;
            }
        }
        return busiestSize == 0 ? -1 : busiestId;
    }

    private int busiestWindowQueueSize(List<Integer> queues) {
        int busiestSize = 0;
        if (queues != null) {
            for (int queueSize : queues) {
                busiestSize = Math.max(busiestSize, queueSize);
            }
        }
        return busiestSize;
    }

    private List<Integer> zeroQueues(int windowCount) {
        List<Integer> queues = new ArrayList<>();
        for (int i = 0; i < Math.max(0, windowCount); i++) {
            queues.add(0);
        }
        return queues;
    }

    private double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
