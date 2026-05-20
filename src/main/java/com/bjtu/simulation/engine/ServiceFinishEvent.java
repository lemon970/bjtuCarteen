package com.bjtu.simulation.engine;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;
import com.bjtu.simulation.service.WeatherFactorPolicy;

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
        boolean initialTakeawayIntent = student != null && student.wantsTakeaway();
        double roll = initialTakeawayIntent ? 0.0 : engine.nextDouble();

        engine.setStudentState(studentId, StudentState.DECIDE_DINE_IN_OR_PACK);
        if (initialTakeawayIntent || roll < probability.finalProbability()) {
            recordModelTakeaway(engine, partySize, waitMinutes, preference, roll, probability, initialTakeawayIntent);
            return;
        }

        recordDineInDecision(engine, partySize, waitMinutes, preference, roll, probability);
    }

    private TakeawayDecisionPolicy.DecisionProbability resolveProbability(SimulationEngine engine, Student student) {
        SimConfig config = engine.getConfig();
        double basePackProbability = config == null ? 0.2 : config.getPackProbability();
        double studentPackPreference = student == null ? basePackProbability : student.getPackPreference();
        double weatherFactor = 1.0;
        if (config != null && config.getWeatherConfig() != null) {
            SimConfig.WeatherConfig wc = config.getWeatherConfig();
            weatherFactor = WeatherFactorPolicy.resolveEffectiveFactor(wc.getCurrentWeather(), wc.getWeatherImpactFactor());
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
                "dedicated takeaway window",
                "window type forces takeaway");
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
                                     TakeawayDecisionPolicy.DecisionProbability probability,
                                     boolean initialTakeawayIntent) {
        Student student = engine.getStudent(studentId);
        cancelReservationIfAny(engine, student);
        String decisionReason = initialTakeawayIntent
                ? "arrival takeaway intent retained; normal window serves takeaway"
                : "dynamic probability roll selected takeaway";
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
                "normal window service completed",
                decisionReason);
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
                "normal window service completed",
                "dynamic probability roll kept dine-in");
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
