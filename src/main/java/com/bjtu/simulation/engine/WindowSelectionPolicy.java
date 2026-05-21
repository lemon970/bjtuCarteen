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
               boolean willTakeaway,
               double preferenceWeight) {
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
        // 走 chooseBestWindow(takeawayOnly=true) 复用现有评分。
        // 当打包窗口当前队列显著长于全局最优时(典型为单一打包窗口被峰值压垮),
        // 让出到全局评分,避免单一打包窗口成为瓶颈打破窗口间排队均衡(Bug-01)。
        if (willTakeaway && takeawayWindowCount > 0) {
            int takeawayWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, true,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
            if (takeawayWindow >= 0) {
                int unifiedWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, null,
                        queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
                if (unifiedWindow < 0 || queues.get(takeawayWindow) - queues.get(unifiedWindow) <= 2) {
                    return takeawayWindow;
                }
                return unifiedWindow;
            }
        }

        // 统一评分路径:score = queueSize + preferencePenalty + delayPenalty + windowTypePenalty。
        // DINE_IN_BIASED 先试普通窗口软分支,避免在普通窗口轻度排队时被路由到打包窗口
        // 后被 ServiceFinishEvent.recordForcedTakeaway 强制打包。

        if (student.getPackPreferenceLevel() == Student.PackPreferenceLevel.DINE_IN_BIASED) {
            int normalWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, false,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
            if (normalWindow >= 0) {
                return normalWindow;
            }
        }

        if (student.getPackPreferenceLevel() == Student.PackPreferenceLevel.BALANCED) {
            int normalWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, false,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
            int takeawayWindow = chooseBestWindow(student, preferred, patienceLimit, partySize, true,
                    queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
            if (normalWindow >= 0
                    && takeawayWindow >= 0
                    && !shouldBalancedStudentUseTakeawayWindow(normalWindow, takeawayWindow,
                    queues, windowAvailableAtSeconds, currentTime, queuePressure, seatPressure)) {
                return normalWindow;
            }
        }

        return chooseBestWindow(student, preferred, patienceLimit, partySize, null,
                queues, windowAvailableAtSeconds, windowTypes, currentTime, preferenceWeight);
    }

    /**
     * RFC-009 PR-9E:{@code preferenceWeight} 放大的是"非偏好窗口惩罚",不是直接给热门窗口加分。
     * 热门窗口服务份额提升来自:weighted windowPreference generation(PR-9C)+ stronger
     * preference stickiness(本 PR)。STATIC_SPLIT 下 weight=1.0,代码路径与 PR-9D 等价。
     */
    private int chooseBestWindow(Student student,
                                 int preferred,
                                 int patienceLimit,
                                 int partySize,
                                 Boolean takeawayOnly,
                                 List<Integer> queues,
                                 List<Long> windowAvailableAtSeconds,
                                 List<String> windowTypes,
                                 long currentTime,
                                 double preferenceWeight) {
        double basePenalty = switch (student.getPatienceLevel()) {
            case LOW -> 0.15;
            case MEDIUM -> 0.45;
            case HIGH -> 0.90;
        };
        double nonPreferredPenalty = basePenalty * preferenceWeight;
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
