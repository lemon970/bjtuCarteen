package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class SeatSearchEvent extends BaseEvent {
    private static final long SEAT_SEARCH_RETRY_SECONDS = 30L;

    private final String studentId;
    private final int remainingAttempts;

    public SeatSearchEvent(long eventTime, String studentId, int remainingAttempts) {
        super(eventTime);
        this.studentId = studentId;
        this.remainingAttempts = Math.max(0, remainingAttempts);
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();

        if (engine.trySeatStudent(student) != null) {
            engine.resolveSeatDecisionPending(partySize);
            engine.recordDineIn(partySize);
            long leaveTime = engine.getCurrentTime() + engine.resolveDiningTimeSeconds();
            engine.setStudentState(studentId, StudentState.DINING);
            engine.scheduleEvent(new StudentLeaveEvent(leaveTime, studentId));
            engine.recordState(studentId + " found table after searching with partySize=" + partySize);
            return;
        }

        if (remainingAttempts > 0) {
            engine.setStudentState(studentId, StudentState.FIND_SEAT);
            engine.scheduleEvent(new SeatSearchEvent(engine.getCurrentTime() + SEAT_SEARCH_RETRY_SECONDS, studentId, remainingAttempts - 1));
            engine.recordState(studentId + " still searching table with partySize=" + partySize);
            return;
        }

        double studentPackPreference = student == null ? 0.5 : student.getPackPreference();
        double roll = engine.nextDouble();

        engine.resolveSeatDecisionPending(partySize);
        engine.recordTakeawayDecision(
                studentId,
                "NO_SEAT_SWITCH",
                Math.max(0.50, studentPackPreference),
                roll,
                0.0,
                studentPackPreference,
                true,
                partySize);
        engine.recordTakeaway(partySize);
        engine.recordNoSeatSwitchToTakeaway(partySize);
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.PACK_LEAVE);
        engine.setStudentState(studentId, StudentState.LEAVE);

        if (roll < studentPackPreference) {
            engine.recordState(studentId + " switched to takeaway after table search with partySize=" + partySize);
        } else {
            engine.recordState(studentId + " gave up table search and left with food with partySize=" + partySize);
        }
    }
}
