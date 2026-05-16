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

        // 第七轮:删除 willTakeaway 硬路由和 dineInOnly 反向硬路由。让所有学生进入统一
        // chooseBestWindow,score = queueSize + preferencePenalty + delayPenalty + windowTypePenalty。
        // dine-in 学生在打包窗口的 windowTypePenalty 提到 +1.50,提供倾向但不强制,
        // 队列长度差(每多 1 人 score+1)能在打包窗口爆 ≥ 4 人时压倒偏好,实现自然分流。
        // DINE_IN_BIASED 仍保留"先试普通窗口"的软分支:如果普通窗口可达,直接用;
        // 否则才 fallback 到统一评分。这避免了 DINE_IN_BIASED 在普通窗口轻度排队时
        // 跑去打包窗口、被 ServiceFinishEvent.recordForcedTakeaway 强制打包。

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
        // 第七轮:DINE_IN_BIASED 在打包窗口从 +3.00 改为 +1.50。+3.00 在实践中过于绝对,
        // 即使打包窗口空闲也基本不会用;+1.50 能让队列差 ≥ 2 人时切换,保留分流可能。
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
