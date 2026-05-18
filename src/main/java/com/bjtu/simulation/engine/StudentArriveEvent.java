package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class StudentArriveEvent extends BaseEvent {
    private static final int DEFAULT_SEAT_WAIT_PATIENCE = 6;  // 默认等位 6 次,每次 60s

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

        String groupTag = arrivalGroup == null ? ArrivalGroup.NORMAL.name() : arrivalGroup.name();

        // 打包意图学生:跳过座位预定,直接走向窗口
        if (student != null && student.wantsTakeaway()) {
            engine.recordInitialTakeawayIntent(student.getPartySize());
            scheduleWalkToWindow(engine);
            engine.recordState(studentId
                    + " arrived(" + groupTag
                    + ", partySize=" + student.getPartySize()
                    + ", groupId=" + (student.getGroupId() == null ? "-" : student.getGroupId())
                    + ", wantsTakeaway=true) and started walking to window area");
            return;
        }

        // 堂食意图学生:到达即预定座位
        if (student == null) {
            scheduleWalkToWindow(engine);
            engine.recordState(studentId + " arrived(" + groupTag + ") and started walking to window area");
            return;
        }

        DiningArea.SeatAllocation reservation = engine.tryReserveSeats(student);
        if (reservation != null) {
            engine.setStudentState(studentId, StudentState.SEAT_RESERVED);
            scheduleWalkToWindow(engine);
            engine.recordState(studentId
                    + " arrived(" + groupTag
                    + ", partySize=" + student.getPartySize()
                    + ", groupId=" + (student.getGroupId() == null ? "-" : student.getGroupId())
                    + ", reservedTable=" + reservation.tableId()
                    + ", split=" + reservation.splitGroup()
                    + ") and started walking to window area");
            return;
        }

        // 预定失败 → 进入等位队列。耐心 = seatSearchPatience 的 2 倍(更宽容,因为锁早抢)
        int waitPatience = Math.max(1, student.getSeatSearchPatience() * 2 + DEFAULT_SEAT_WAIT_PATIENCE / 2);
        engine.enqueueSeatWait(studentId, waitPatience);
        engine.setStudentState(studentId, StudentState.WAITING_FOR_SEAT);
        engine.recordState(studentId
                + " arrived(" + groupTag
                + ", partySize=" + student.getPartySize()
                + ") and entered seat-wait queue (no available reservation)");
    }

    private void scheduleWalkToWindow(SimulationEngine engine) {
        engine.setStudentState(studentId, StudentState.WALKING_TO_WINDOW);
        long movementTime = engine.resolveMovementTimeSeconds();
        engine.scheduleEvent(new MovementEvent(
                engine.getCurrentTime() + movementTime,
                studentId,
                MovementEvent.Purpose.TO_WINDOW,
                engine.getCurrentTime()));
    }
}
