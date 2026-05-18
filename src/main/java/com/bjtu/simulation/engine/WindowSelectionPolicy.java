package com.bjtu.simulation.engine;

import java.util.List;

import com.bjtu.simulation.model.Student;

class WindowSelectionPolicy {

    int choose(Student student,
               CanteenState canteenState,
               List<Long> windowAvailableAtSeconds,
               List<String> windowTypes,
               long currentTime,
               double queuePressure,
               double seatPressure,
               int takeawayWindowCount,
               boolean willTakeaway) {
        List<Integer> queues = canteenState.getWindowQueues();
        if (queues.isEmpty()) {
            return -1;
        }
        int shortestWindow = canteenState.findShortestQueueIndex();
        if (student == null) {
            return shortestWindow;
        }

        int preferred = Math.floorMod(student.getWindowPreference(), queues.size());
        int patienceLimit = Math.max(0, student.getPatienceLimit());
        int partySize = Math.max(1, student.getPartySize());

        // 意图打包学生优先选打包窗口(StudentArriveEvent 已跳过座位预定),
        // 走 chooseBestWindow(takeawayOnly=true) 复用现有评分,
        // 所有打包窗口超耐心时 fallthrough 到统一评分。
        if (willTakeaway && takeawayWindowCount > 0) {
            int takeawayWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, true,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime);
            if (takeawayWindow >= 0) {
                return takeawayWindow;
            }
        }

        // 统一评分路径:score = queueSize + preferencePenalty + delayPenalty + windowTypePenalty。
        // DINE_IN_BIASED 先试普通窗口软分支,避免在普通窗口轻度排队时被路由到打包窗口
        // 后被 ServiceFinishEvent.recordForcedTakeaway 强制打包。

        if (student.getPackPreferenceLevel() == Student.PackPreferenceLevel.DINE_IN_BIASED) {
            int normalWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, false,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime);
            if (normalWindow >= 0) {
                return normalWindow;
            }
        }

        if (student.getPackPreferenceLevel() == Student.PackPreferenceLevel.BALANCED) {
            int normalWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, false,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime);
            int takeawayWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, true,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime);
            if (normalWindow >= 0
                    && takeawayWindow >= 0
                    && !shouldBalancedStudentUseTakeawayWindow(normalWindow, takeawayWindow,
                    queues, windowAvailableAtSeconds, currentTime, queuePressure, seatPressure)) {
                return normalWindow;
            }
        }

        return chooseBestWindow(student, preferred, patienceLimit, partySize, null,
                queues, windowAvailableAtSeconds, windowTypes, currentTime);
    }

    private int chooseBestWindow(Student student,
                                 int preferred,
                                 int patienceLimit,
                                 int partySize,
                                 Boolean takeawayOnly,
                                 List<Integer> queues,
                                 List<Long> windowAvailableAtSeconds,
                                 List<String> windowTypes,
                                 long currentTime) {
        double nonPreferredPenalty = switch (student.getPatienceLevel()) {
            case LOW -> 0.15;
            case MEDIUM -> 0.45;
            case HIGH -> 0.90;
        };
        int bestWindow = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < queues.size(); i++) {
            if (takeawayOnly != null && isTakeawayWindow(i, windowTypes) != takeawayOnly) {
                continue;
            }
            int queueSize = queues.get(i);
            if (queueSize + partySize > patienceLimit) {
                continue;
            }

            double preferencePenalty = (i == preferred) ? 0.0 : nonPreferredPenalty;
            double delayPenalty = projectedWindowDelaySeconds(i, windowAvailableAtSeconds, currentTime) / 60.0 * 0.25;
            double score = queueSize + preferencePenalty + delayPenalty + windowTypePenalty(student, i, windowTypes);
            if (score < bestScore) {
                bestScore = score;
                bestWindow = i;
            }
        }
        return bestWindow;
    }

    private boolean shouldBalancedStudentUseTakeawayWindow(int normalWindow,
                                                           int takeawayWindow,
                                                           List<Integer> queues,
                                                           List<Long> windowAvailableAtSeconds,
                                                           long currentTime,
                                                           double queuePressure,
                                                           double seatPressure) {
        int normalQueue = queues.get(normalWindow);
        int takeawayQueue = queues.get(takeawayWindow);
        long normalDelay = projectedWindowDelaySeconds(normalWindow, windowAvailableAtSeconds, currentTime);
        long takeawayDelay = projectedWindowDelaySeconds(takeawayWindow, windowAvailableAtSeconds, currentTime);
        boolean systemPressureHigh = queuePressure >= 0.65 || seatPressure >= 0.88;
        boolean takeawayClearlyBetter = normalQueue - takeawayQueue >= 4 || normalDelay - takeawayDelay >= 180L;
        return systemPressureHigh && takeawayClearlyBetter;
    }

    private double windowTypePenalty(Student student, int windowId, List<String> windowTypes) {
        boolean takeawayWindow = isTakeawayWindow(windowId, windowTypes);
        // DINE_IN_BIASED 在打包窗口 +1.50:提供软偏好,队列差 ≥ 2 人时仍能自然切换。
        return switch (student.getPackPreferenceLevel()) {
            case TAKEAWAY_BIASED -> takeawayWindow ? -0.40 : 0.55;
            case BALANCED -> takeawayWindow ? 0.30 : 0.00;
            case DINE_IN_BIASED -> takeawayWindow ? 1.50 : -0.20;
        };
    }

    private boolean isTakeawayWindow(int windowId, List<String> windowTypes) {
        return windowId >= 0
                && windowId < windowTypes.size()
                && "TAKEAWAY".equalsIgnoreCase(windowTypes.get(windowId));
    }

    private long projectedWindowDelaySeconds(int windowId, List<Long> windowAvailableAtSeconds, long currentTime) {
        if (windowId < 0 || windowId >= windowAvailableAtSeconds.size()) {
            return 0L;
        }
        return Math.max(0L, windowAvailableAtSeconds.get(windowId) - currentTime);
    }
}
