package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.StudentState;

public class StudentLeaveEvent extends BaseEvent {
    private final String studentId;

    public StudentLeaveEvent(long eventTime, String studentId) {
        super(eventTime);
        this.studentId = studentId;
    }

    @Override
    public void process(SimulationEngine engine) {
        int partySize = 1;
        if (engine.getStudent(studentId) != null) {
            partySize = engine.getStudent(studentId).getPartySize();
        }
        engine.releaseStudentSeat(engine.getStudent(studentId));
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " left canteen after dining with partySize=" + partySize);
    }
}
