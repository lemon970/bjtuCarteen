package com.bjtu.simulation.engine;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class ServiceFinishEvent extends BaseEvent {
    private static final double MAX_QUEUE_FEEDBACK_PACK_BONUS = 0.30;

    private final String studentId;
    private final int windowId;
    private final long arriveTime;

    public ServiceFinishEvent(long time, String studentId, int windowId, long arriveTime) {
        super(time);
        this.studentId = studentId;
        this.windowId = windowId;
        this.arriveTime = arriveTime;
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();
        engine.setStudentState(studentId, StudentState.SERVING);

        engine.recordWaitTime(this.arriveTime, partySize);
        engine.recordWindowServed(this.windowId, partySize);
        engine.getCanteenState().leaveQueue(this.windowId);

        SimConfig config = engine.getConfig();
        double basePackProbability = config == null ? 0.2 : config.getPackProbability();
        double studentPackPreference = student == null ? basePackProbability : student.getPackPreference();
        double weatherFactor = 1.0;
        if (config != null && config.getWeatherConfig() != null) {
            weatherFactor = clamp(config.getWeatherConfig().getWeatherImpactFactor(), 0.0, 5.0);
        }

        double queueFeedbackProbability = resolveQueueFeedbackProbability(engine);
        double decisionPackProbability = clamp(
                0.35 * basePackProbability + 0.65 * studentPackPreference + queueFeedbackProbability,
                0.0,
                1.0);
        double finalPackProbability = clamp(decisionPackProbability * weatherFactor, 0.0, 1.0);

        engine.setStudentState(studentId, StudentState.DECIDE_DINE_IN_OR_PACK);

        if (engine.isTakeawayWindow(this.windowId)) {
            engine.recordTakeaway(partySize);
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway via dedicated takeaway window " + windowId + " with partySize=" + partySize);
            return;
        }

        double roll = engine.nextDouble();
        if (roll < finalPackProbability) {
            engine.recordTakeaway(partySize);
            if (roll >= decisionPackProbability && weatherFactor > 1.0) {
                engine.recordWeatherDrivenTakeaway(partySize);
            }
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway with partySize=" + partySize);
            return;
        }

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

    private double resolveQueueFeedbackProbability(SimulationEngine engine) {
        if (engine == null || engine.getCanteenState() == null || engine.getCanteenState().getWindowQueues().isEmpty()) {
            return 0.0;
        }

        int totalQueueSize = 0;
        for (int queueSize : engine.getCanteenState().getWindowQueues()) {
            totalQueueSize += Math.max(0, queueSize);
        }

        int windowCount = Math.max(1, engine.getCanteenState().getWindowCount());
        int queueLimit = engine.getConfig() == null ? 10 : Math.max(1, engine.getConfig().getQueueLimit());
        double pressure = (double) totalQueueSize / (windowCount * queueLimit);
        return clamp(pressure, 0.0, 1.0) * MAX_QUEUE_FEEDBACK_PACK_BONUS;
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
}
