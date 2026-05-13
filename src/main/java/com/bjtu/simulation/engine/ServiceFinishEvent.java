package com.bjtu.simulation.engine;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class ServiceFinishEvent extends BaseEvent {
    private static final double MAX_MODEL_PACK_PROBABILITY = 0.75;
    private static final double TAKEAWAY_RATE_SOFT_CAP = 0.45;

    private final String studentId;
    private final int windowId;
    private final long arriveTime;
    private final long serviceStartTime;

    public ServiceFinishEvent(long time, String studentId, int windowId, long arriveTime) {
        this(time, studentId, windowId, arriveTime, arriveTime);
    }

    public ServiceFinishEvent(long time, String studentId, int windowId, long arriveTime, long serviceStartTime) {
        super(time);
        this.studentId = studentId;
        this.windowId = windowId;
        this.arriveTime = arriveTime;
        this.serviceStartTime = Math.max(arriveTime, serviceStartTime);
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();
        engine.setStudentState(studentId, StudentState.SERVING);

        engine.recordWaitTime(this.arriveTime, this.serviceStartTime, partySize);
        engine.recordWindowServed(this.windowId, partySize);
        engine.getCanteenState().leaveQueue(this.windowId, partySize);

        SimConfig config = engine.getConfig();
        double basePackProbability = config == null ? 0.2 : config.getPackProbability();
        double studentPackPreference = student == null ? basePackProbability : student.getPackPreference();
        double weatherFactor = 1.0;
        if (config != null && config.getWeatherConfig() != null) {
            weatherFactor = clamp(config.getWeatherConfig().getWeatherImpactFactor(), 0.0, 5.0);
        }

        double queuePressure = engine.currentQueuePressure();
        double seatUtilization = engine.currentSeatUtilizationRate();
        double waitMinutes = Math.max(0.0, (serviceStartTime - arriveTime) / 60.0);
        DecisionProbability decisionProbability = resolveDecisionPackProbability(
                basePackProbability,
                studentPackPreference,
                queuePressure,
                seatUtilization,
                waitMinutes,
                weatherFactor,
                engine.getTakeawayCount(),
                engine.getServedCount());

        engine.setStudentState(studentId, StudentState.DECIDE_DINE_IN_OR_PACK);

        if (engine.isTakeawayWindow(this.windowId)) {
            engine.recordTakeawayDecision(
                    studentId,
                    "TAKEAWAY_WINDOW",
                    1.0,
                    0.0,
                    waitMinutes,
                    studentPackPreference,
                    true,
                    partySize,
                    basePackProbability,
                    decisionProbability.preferenceFactor(),
                    decisionProbability.seatPressureFactor(),
                    decisionProbability.waitPressureFactor(),
                    decisionProbability.queuePressureFactor(),
                    decisionProbability.weatherFactor(),
                    "已选择打包窗口",
                    "窗口类型决定打包离开");
            engine.recordTakeaway(partySize);
            engine.recordLeave(partySize);
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway via dedicated takeaway window " + windowId + " with partySize=" + partySize);
            return;
        }

        double roll = engine.nextDouble();
        if (roll < decisionProbability.finalProbability()) {
            engine.recordTakeawayDecision(
                    studentId,
                    "MODEL_TRIGGER",
                    decisionProbability.finalProbability(),
                    roll,
                    waitMinutes,
                    studentPackPreference,
                    true,
                    partySize,
                    basePackProbability,
                    decisionProbability.preferenceFactor(),
                    decisionProbability.seatPressureFactor(),
                    decisionProbability.waitPressureFactor(),
                    decisionProbability.queuePressureFactor(),
                    decisionProbability.weatherFactor(),
                    "普通窗口完成服务",
                    decisionProbability.decisionReason());
            engine.recordTakeaway(partySize);
            if (decisionProbability.weatherFactor() > 0.001) {
                engine.recordWeatherDrivenTakeaway(partySize);
            }
            engine.recordLeave(partySize);
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway with partySize=" + partySize);
            return;
        }

        engine.recordTakeawayDecision(
                studentId,
                "DINE_IN_MODEL",
                decisionProbability.finalProbability(),
                roll,
                waitMinutes,
                studentPackPreference,
                false,
                partySize,
                basePackProbability,
                decisionProbability.preferenceFactor(),
                decisionProbability.seatPressureFactor(),
                decisionProbability.waitPressureFactor(),
                decisionProbability.queuePressureFactor(),
                decisionProbability.weatherFactor(),
                "普通窗口完成服务",
                "随机数高于最终打包概率，继续堂食");
        engine.setStudentState(studentId, StudentState.WALKING_TO_SEAT);
        engine.recordSeatDecisionPending(partySize);
        long movementTime = engine.resolveMovementTimeSeconds();
        engine.scheduleEvent(new MovementEvent(
                engine.getCurrentTime() + movementTime,
                studentId,
                MovementEvent.Purpose.TO_SEAT,
                engine.getCurrentTime()));
        engine.recordState(studentId + " finished service and started walking to dining area with partySize=" + partySize);
    }

    private DecisionProbability resolveDecisionPackProbability(double basePackProbability,
                                                               double studentPackPreference,
                                                               double queuePressure,
                                                               double seatUtilization,
                                                               double waitMinutes,
                                                               double weatherFactor,
                                                               int currentTakeawayCount,
                                                               int currentServedCount) {
        double base = clamp(basePackProbability, 0.0, 1.0);
        double preference = clamp(studentPackPreference, 0.0, 1.0);
        double queue = clamp(queuePressure, 0.0, 1.0);
        double seat = clamp(seatUtilization, 0.0, 1.0);
        double preferenceFactor = clamp((preference - base) * 0.35, -0.06, 0.08);
        double seatPressureFactor = seat <= 0.85 ? 0.0 : clamp((seat - 0.85) / 0.15 * 0.12, 0.0, 0.12);
        double waitPressureFactor = waitMinutes <= 10.0 ? 0.0 : clamp((waitMinutes - 10.0) / 10.0 * 0.05, 0.0, 0.05);
        double queuePressureFactor = queue <= 0.75 ? 0.0 : clamp((queue - 0.75) / 0.25 * 0.04, 0.0, 0.04);
        double weatherDelta = clamp((weatherFactor - 1.0) * 0.08, -0.04, 0.08);

        double localCap = base + 0.05;
        if (seat >= 0.98 || (seat >= 0.92 && waitMinutes >= 12.0)) {
            localCap = 0.65;
        } else if (seat >= 0.90) {
            localCap = 0.45;
        } else if (waitMinutes >= 18.0 && queue >= 0.90) {
            localCap = 0.35;
        }
        localCap = clamp(localCap, 0.02, MAX_MODEL_PACK_PROBABILITY);

        // [重构] 打包概率使用有界增量模型，原因是基础概率 0.15 的晴天低压场景不能被多个因素叠加到 30% 以上。
        double modelProbability = clamp(
                base
                        + preferenceFactor
                        + seatPressureFactor
                        + waitPressureFactor
                        + queuePressureFactor
                        + weatherDelta,
                0.02,
                localCap);

        if (currentServedCount >= 20) {
            double runningRate = currentServedCount == 0 ? 0.0 : (double) currentTakeawayCount / currentServedCount;
            if (runningRate > TAKEAWAY_RATE_SOFT_CAP) {
                double overflow = clamp((runningRate - TAKEAWAY_RATE_SOFT_CAP) / 0.20, 0.0, 1.0);
                modelProbability *= (1.0 - 0.45 * overflow);
            }
        }

        String reason = decisionReason(seat, waitMinutes, queue);
        return new DecisionProbability(
                clamp(modelProbability, 0.0, MAX_MODEL_PACK_PROBABILITY),
                preferenceFactor,
                seatPressureFactor,
                waitPressureFactor,
                queuePressureFactor,
                weatherDelta,
                reason);
    }

    private String decisionReason(double seatUtilization, double waitMinutes, double queuePressure) {
        if (seatUtilization >= 0.98) {
            return "座位接近满载，打包概率进入高压上限";
        }
        if (seatUtilization >= 0.90) {
            return "座位占用率较高，增加打包倾向";
        }
        if (waitMinutes >= 12.0) {
            return "等待时间超过阈值，增加打包倾向";
        }
        if (queuePressure >= 0.85) {
            return "窗口排队压力较高，增加打包倾向";
        }
        return "低压常规场景，打包概率围绕基础概率小幅波动";
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private record DecisionProbability(double finalProbability,
                                       double preferenceFactor,
                                       double seatPressureFactor,
                                       double waitPressureFactor,
                                       double queuePressureFactor,
                                       double weatherFactor,
                                       String decisionReason) {
    }
}
