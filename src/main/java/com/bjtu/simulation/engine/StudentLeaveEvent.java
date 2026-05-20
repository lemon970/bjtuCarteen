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
        var student = engine.getStudent(studentId);
        if (student == null) {
            // phantom leave: 未知 id 的离开事件不释放任何座位、也不计入 leaveCount,
            // 避免破坏 leaveCount <= servedCount 的不变量。
            engine.recordState(studentId + " phantom leave ignored");
            return;
        }
        int partySize = student.getPartySize();
        engine.releaseStudentSeat(student);
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " left canteen after dining with partySize=" + partySize);
    }
}
