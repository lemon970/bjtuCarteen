package com.bjtu.simulation.engine;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class ServiceFinishEvent extends BaseEvent {
    private static final double MAX_MODEL_PACK_PROBABILITY = 0.95;

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

        double queuePressure = engine.currentQueuePressure();
        double seatUtilization = engine.currentSeatUtilizationRate();
        double waitMinutes = Math.max(0.0, (engine.getCurrentTime() - arriveTime) / 60.0);
        double finalPackProbability = resolveDecisionPackProbability(
                basePackProbability,
                studentPackPreference,
                queuePressure,
                seatUtilization,
                waitMinutes,
                weatherFactor);

        engine.setStudentState(studentId, StudentState.DECIDE_DINE_IN_OR_PACK);

        if (engine.isTakeawayWindow(this.windowId)) {
            engine.recordTakeawayDecision(studentId, "TAKEAWAY_WINDOW", 1.0, 0.0, waitMinutes, studentPackPreference, true, partySize);
            engine.recordTakeaway(partySize);
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway via dedicated takeaway window " + windowId + " with partySize=" + partySize);
            return;
        }

        double roll = engine.nextDouble();
        if (roll < finalPackProbability) {
            engine.recordTakeawayDecision(studentId, "MODEL_TRIGGER", finalPackProbability, roll, waitMinutes, studentPackPreference, true, partySize);
            engine.recordTakeaway(partySize);
            if (weatherFactor > 1.0 && roll >= finalPackProbability / weatherFactor) {
                engine.recordWeatherDrivenTakeaway(partySize);
            }
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " took takeaway with partySize=" + partySize);
            return;
        }

        engine.recordTakeawayDecision(studentId, "DINE_IN_MODEL", finalPackProbability, roll, waitMinutes, studentPackPreference, false, partySize);
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

    private double resolveDecisionPackProbability(double basePackProbability,
                                                  double studentPackPreference,
                                                  double queuePressure,
                                                  double seatUtilization,
                                                  double waitMinutes,
                                                  double weatherFactor) {
        double waitPressure = clamp(waitMinutes / 12.0, 0.0, 1.0);
        double seatPressureBonus = seatUtilization >= 0.90 ? 0.18 : 0.08 * seatUtilization;
        double modelProbability =
                0.18 * clamp(basePackProbability, 0.0, 1.0)
                        + 0.34 * clamp(studentPackPreference, 0.0, 1.0)
                        + 0.32 * clamp(queuePressure, 0.0, 1.0)
                        + 0.16 * waitPressure
                        + seatPressureBonus;
        return clamp(modelProbability * clamp(weatherFactor, 0.0, 5.0), 0.0, MAX_MODEL_PACK_PROBABILITY);
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
