package com.bjtu.simulation.engine;

import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

/**
 * @deprecated 第六轮重构后,座位预定在到达时一次性完成(reserve-then-queue),
 * 学生不再需要在用餐区"找座"。该事件类保留仅为向后兼容旧测试或异常调度路径,
 * 内部走 no_seat_abandoned 兜底,不再调用 recordTakeaway,避免污染打包率。
 */
@Deprecated
public class SeatSearchEvent extends BaseEvent {
    private final String studentId;

    public SeatSearchEvent(long eventTime, String studentId, int remainingAttempts) {
        super(eventTime);
        this.studentId = studentId;
    }

    @Override
    public void process(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();

        // 兼容路径:若旧调用进来,先尝试 reserve(可能已经从 walkToSeat 到这里)
        if (student != null && student.getSeatAllocation() != null) {
            engine.confirmReservation(student);
            engine.resolveSeatDecisionPending(partySize);
            engine.recordDineIn(partySize);
            long leaveTime = engine.getCurrentTime() + engine.resolveDiningTimeSeconds();
            engine.setStudentState(studentId, StudentState.DINING);
            engine.scheduleEvent(new StudentLeaveEvent(leaveTime, studentId));
            engine.recordState(studentId + " (deprecated SeatSearchEvent) confirmed reservation");
            return;
        }

        // 没有预定 → 走 post-service no_seat 路径,而不再走 recordTakeaway
        engine.resolveSeatDecisionPending(partySize);
        engine.recordPostServiceNoSeat(partySize);
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " (deprecated SeatSearchEvent) no reservation, abandoned without takeaway");
    }
}
