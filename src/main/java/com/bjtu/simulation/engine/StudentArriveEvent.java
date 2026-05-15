package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class StudentArriveEvent extends BaseEvent {
    private final String studentId;
    private final ArrivalGroup arrivalGroup;
    private final int partySize;
    private final String groupId;
    private final int groupSize;
    private final int groupMemberIndex;

    public StudentArriveEvent(long time, String studentId, ArrivalGroup arrivalGroup) {
        this(time, studentId, arrivalGroup, 1);
    }

    public StudentArriveEvent(long time, String studentId, ArrivalGroup arrivalGroup, int partySize) {
        this(time, studentId, arrivalGroup, partySize, null, partySize, 0);
    }

    public StudentArriveEvent(long time,
                              String studentId,
                              ArrivalGroup arrivalGroup,
                              int partySize,
                              String groupId,
                              int groupSize,
                              int groupMemberIndex) {
        super(time);
        this.studentId = studentId;
        this.arrivalGroup = arrivalGroup;
        this.partySize = Math.max(1, partySize);
        this.groupId = groupId == null || groupId.isBlank() ? null : groupId;
        this.groupSize = Math.max(this.partySize, groupSize);
        this.groupMemberIndex = Math.max(0, groupMemberIndex);
    }

    @Override
    public void process(SimulationEngine engine) {
        engine.recordArrival(arrivalGroup, partySize);
        Student student = engine.registerStudent(studentId, arrivalGroup, partySize, groupId, groupSize, groupMemberIndex);
        engine.setStudentState(studentId, StudentState.ARRIVED);
        engine.setStudentState(studentId, StudentState.WALKING_TO_WINDOW);
        long movementTime = engine.resolveMovementTimeSeconds();
        engine.scheduleEvent(new MovementEvent(
                engine.getCurrentTime() + movementTime,
                studentId,
                MovementEvent.Purpose.TO_WINDOW,
                engine.getCurrentTime()));

        String groupTag = arrivalGroup == null ? ArrivalGroup.NORMAL.name() : arrivalGroup.name();
        if (student == null) {
            engine.recordState(studentId + " arrived(" + groupTag + ") and started walking to window area");
            return;
        }
        engine.recordState(studentId
                + " arrived(" + groupTag
                + ", partySize=" + student.getPartySize()
                + ", groupId=" + (student.getGroupId() == null ? "-" : student.getGroupId())
                + ", patience=" + student.getPatienceLevel()
                + ", pack=" + student.getPackPreferenceLevel()
                + ", seatTolerance=" + student.getSeatToleranceLevel()
                + ") and started walking to window area");
    }
}
