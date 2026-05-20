package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

/**
 * 座位等位事件:学生到达时预定失败 → 入等位队列 → 周期重试 reserve。
 * 重试成功 → 进入 SEAT_RESERVED 状态并启动走向窗口流程。
 * 耐心耗尽 → 单独计 no_seat_abandoned(不计入 takeaway)。
 */
public class SeatWaitEvent extends BaseEvent {
    static final long RETRY_INTERVAL_SECONDS = 60L;

    private final String studentId;
    private final int remainingPatience;

    public SeatWaitEvent(long eventTime, String studentId, int remainingPatience) {
        super(eventTime);
        this.studentId = studentId;
        this.remainingPatience = Math.max(0, remainingPatience);
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        if (student == null || student.getState() == StudentState.LEAVE) {
            return;
        }
        // 已经被其他路径领走(防御性)
        if (!engine.isInSeatWaitQueue(studentId)) {
            return;
        }

        DiningArea.SeatAllocation reservation = engine.tryReserveSeats(student);
        if (reservation != null) {
            engine.removeFromSeatWaitQueue(studentId);
            engine.setStudentState(studentId, StudentState.SEAT_RESERVED);
            long movementTime = engine.resolveMovementTimeSeconds();
            engine.scheduleEvent(new MovementEvent(
                    engine.getCurrentTime() + movementTime,
                    studentId,
                    MovementEvent.Purpose.TO_WINDOW,
                    engine.getCurrentTime()));
            engine.recordState(studentId
                    + " woken from seat wait queue with reservation tableId="
                    + reservation.tableId()
                    + " split=" + reservation.splitGroup());
            return;
        }

        if (remainingPatience > 0) {
            engine.scheduleEvent(new SeatWaitEvent(
                    engine.getCurrentTime() + RETRY_INTERVAL_SECONDS,
                    studentId,
                    remainingPatience - 1));
            return;
        }

        // 耐心耗尽 → 离开,不计 takeaway
        int partySize = student.getPartySize();
        engine.removeFromSeatWaitQueue(studentId);
        engine.recordNoSeatAbandoned(partySize);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId
                + " left after exhausting seat-wait patience with partySize=" + partySize);
    }
}
