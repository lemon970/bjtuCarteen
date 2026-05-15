package com.bjtu.simulation.engine;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;
import com.bjtu.simulation.service.SimulationMath;

public class ServiceFinishEvent extends BaseEvent {
    private final TakeawayDecisionPolicy decisionPolicy = new TakeawayDecisionPolicy();
    private final String studentId;
    private final int windowId;
    private final long arriveTime;
    private final long serviceStartTime;
    private final int queueLengthAtJoin;

    public ServiceFinishEvent(long time,
                              String studentId,
                              int windowId,
                              long arriveTime,
                              long serviceStartTime,
                              int queueLengthAtJoin) {
        super(time);
        this.studentId = studentId;
        this.windowId = windowId;
        this.arriveTime = arriveTime;
        this.serviceStartTime = Math.max(arriveTime, serviceStartTime);
        this.queueLengthAtJoin = Math.max(0, queueLengthAtJoin);
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();
        String windowType = engine.isTakeawayWindow(windowId) ? "TAKEAWAY" : "NORMAL";

        engine.setStudentState(studentId, StudentState.SERVING);
        engine.recordWaitTime(arriveTime, serviceStartTime, partySize, windowId, windowType, queueLengthAtJoin);
        engine.recordWindowServed(windowId, partySize);
        engine.getCanteenState().leaveQueue(windowId, partySize);

        if (engine.isTakeawayWindow(windowId)) {
            recordForcedTakeaway(engine, student, partySize);
            return;
        }

        TakeawayDecisionPolicy.DecisionProbability probability = resolveProbability(engine, student);
        double waitMinutes = Math.max(0.0, (serviceStartTime - arriveTime) / 60.0);
        double preference = student == null ? probability.finalProbability() : student.getPackPreference();
        boolean wantsTakeaway = student != null && student.wantsTakeaway();

        engine.setStudentState(studentId, StudentState.DECIDE_DINE_IN_OR_PACK);
        if (wantsTakeaway) {
            recordModelTakeaway(engine, partySize, waitMinutes, preference, 0.0, probability);
            return;
        }

        recordDineInDecision(engine, partySize, waitMinutes, preference, 0.0, probability);
    }

    private TakeawayDecisionPolicy.DecisionProbability resolveProbability(SimulationEngine engine, Student student) {
        SimConfig config = engine.getConfig();
        double basePackProbability = config == null ? 0.2 : config.getPackProbability();
        double studentPackPreference = student == null ? basePackProbability : student.getPackPreference();
        double weatherFactor = 1.0;
        if (config != null && config.getWeatherConfig() != null) {
            weatherFactor = SimulationMath.clamp(config.getWeatherConfig().getWeatherImpactFactor(), 0.0, 5.0);
        }
        double waitMinutes = Math.max(0.0, (serviceStartTime - arriveTime) / 60.0);
        return decisionPolicy.resolve(
                basePackProbability,
                studentPackPreference,
                engine.currentQueuePressure(),
                engine.currentSeatUtilizationRate(),
                waitMinutes,
                weatherFactor,
                engine.getTakeawayCount(),
                engine.getServedCount());
    }

    private void recordForcedTakeaway(SimulationEngine engine, Student student, int partySize) {
        // 防御:dine-in 学生意外被路由到打包窗口时,归还预定座位
        cancelReservationIfAny(engine, student);
        double preference = student == null ? 1.0 : student.getPackPreference();
        double waitMinutes = Math.max(0.0, (serviceStartTime - arriveTime) / 60.0);
        engine.recordTakeawayDecision(
                studentId,
                "TAKEAWAY_WINDOW",
                1.0,
                0.0,
                waitMinutes,
                preference,
                true,
                partySize,
                engine.getConfig() == null ? 0.2 : engine.getConfig().getPackProbability(),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                "已选择打包窗口",
                "窗口类型决定打包离开");
        engine.recordTakeaway(partySize);
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.PACK_LEAVE);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " took takeaway via dedicated takeaway window " + windowId + " with partySize=" + partySize);
    }

    private void recordModelTakeaway(SimulationEngine engine,
                                     int partySize,
                                     double waitMinutes,
                                     double preference,
                                     double roll,
                                     TakeawayDecisionPolicy.DecisionProbability probability) {
        Student student = engine.getStudent(studentId);
        cancelReservationIfAny(engine, student);
        engine.recordTakeawayDecision(
                studentId,
                "MODEL_TRIGGER",
                probability.finalProbability(),
                roll,
                waitMinutes,
                preference,
                true,
                partySize,
                engine.getConfig() == null ? 0.2 : engine.getConfig().getPackProbability(),
                probability.preferenceFactor(),
                probability.seatPressureFactor(),
                probability.waitPressureFactor(),
                probability.queuePressureFactor(),
                probability.weatherFactor(),
                "普通窗口完成服务",
                "到达时已决定打包(意图前置),普通窗口兼做打包");
        engine.recordTakeaway(partySize);
        if (probability.weatherFactor() > 0.001) {
            engine.recordWeatherDrivenTakeaway(partySize);
        }
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.PACK_LEAVE);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " took takeaway with partySize=" + partySize);
    }

    private void recordDineInDecision(SimulationEngine engine,
                                      int partySize,
                                      double waitMinutes,
                                      double preference,
                                      double roll,
                                      TakeawayDecisionPolicy.DecisionProbability probability) {
        engine.recordTakeawayDecision(
                studentId,
                "DINE_IN_MODEL",
                probability.finalProbability(),
                roll,
                waitMinutes,
                preference,
                false,
                partySize,
                engine.getConfig() == null ? 0.2 : engine.getConfig().getPackProbability(),
                probability.preferenceFactor(),
                probability.seatPressureFactor(),
                probability.waitPressureFactor(),
                probability.queuePressureFactor(),
                probability.weatherFactor(),
                "普通窗口完成服务",
                "到达时已决定堂食(意图前置)");
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

    private void cancelReservationIfAny(SimulationEngine engine, Student student) {
        if (student == null || student.getSeatAllocation() == null) {
            return;
        }
        engine.getCanteenState().cancelReservation(student.getSeatAllocation());
        student.setSeatAllocation(null);
    }
}
