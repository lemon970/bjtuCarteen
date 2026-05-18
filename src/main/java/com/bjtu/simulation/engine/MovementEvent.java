package com.bjtu.simulation.engine;

import java.util.List;

import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;

public class MovementEvent extends BaseEvent {
    public enum Purpose {
        TO_WINDOW,
        TO_SEAT
    }

    private static final int DEFAULT_QUEUE_LIMIT = 10;

    private final String studentId;
    private final Purpose purpose;
    private final long movementStartTime;

    public MovementEvent(long eventTime, String studentId, Purpose purpose, long movementStartTime) {
        super(eventTime);
        this.studentId = studentId;
        this.purpose = purpose == null ? Purpose.TO_WINDOW : purpose;
        this.movementStartTime = Math.max(0L, movementStartTime);
    }

    @Override
    public void process(SimulationEngine engine) {
        engine.recordMovementTime(engine.getCurrentTime() - movementStartTime);
        if (purpose == Purpose.TO_SEAT) {
            processWalkToSeat(engine);
            return;
        }
        processWalkToWindow(engine);
    }

    private void processWalkToWindow(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        engine.setStudentState(studentId, StudentState.CHOOSE_WINDOW);

        List<Integer> queues = engine.getCanteenState().getWindowQueues();
        if (queues.isEmpty()) {
            cancelReservationOnAbandon(engine, student);
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: no window available");
            return;
        }

        int globalQueueLimit = engine.getConfig() == null ? DEFAULT_QUEUE_LIMIT : Math.max(0, engine.getConfig().getQueueLimit());
        int patienceLimit = student == null ? globalQueueLimit : student.getPatienceLimit();
        int partySize = student == null ? 1 : student.getPartySize();
        boolean allBlockedByGlobalLimit = queues.stream().allMatch(q -> q + partySize > globalQueueLimit);
        if (allBlockedByGlobalLimit) {
            cancelReservationOnAbandon(engine, student);
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: global queue limit reached");
            return;
        }

        boolean allBlockedByPersonalLimit = queues.stream().allMatch(q -> q + partySize > patienceLimit);
        if (allBlockedByPersonalLimit) {
            cancelReservationOnAbandon(engine, student);
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: personal patience limit reached");
            return;
        }

        int windowId = engine.chooseWindowForStudent(student);
        if (windowId < 0) {
            cancelReservationOnAbandon(engine, student);
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: failed to choose window");
            return;
        }
        int chosenQueueSize = queues.get(windowId);
        if (chosenQueueSize + partySize > globalQueueLimit || chosenQueueSize + partySize > patienceLimit) {
            cancelReservationOnAbandon(engine, student);
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: chosen queue exceeds tolerance");
            return;
        }

        // [重构] 队列长度按"人数"而非"事件数"累计,原因是组团到达时前端排队人数和后端统计口径必须一致。
        engine.getCanteenState().joinQueue(windowId, partySize);
        engine.setStudentState(studentId, StudentState.QUEUING);

        boolean willTakeaway = engine.isTakeawayWindow(windowId)
                || (student != null && student.wantsTakeaway());
        long serviceTime = engine.resolveServiceTimeSeconds(windowId, willTakeaway);
        long queueEnterTime = engine.getCurrentTime();
        long serviceStartTime = engine.reserveWindowService(windowId, queueEnterTime, serviceTime);
        long finishTime = serviceStartTime + serviceTime;
        engine.scheduleEvent(new ServiceFinishEvent(
                finishTime,
                studentId,
                windowId,
                queueEnterTime,
                serviceStartTime,
                chosenQueueSize));

        String groupTag = student == null || student.getArrivalGroup() == null
                ? ArrivalGroup.NORMAL.name()
                : student.getArrivalGroup().name();
        String windowType = engine.isTakeawayWindow(windowId) ? "TAKEAWAY" : "NORMAL";
        if (student == null) {
            engine.recordState(studentId + " reached window area and queued at " + windowType + " window " + windowId);
            return;
        }
        engine.recordState(studentId
                + " reached window area("
                + groupTag
                + ", partySize=" + partySize
                + ", patience=" + student.getPatienceLevel()
                + ", pack=" + student.getPackPreferenceLevel()
                + ", seatTolerance=" + student.getSeatToleranceLevel()
                + ") and queued at " + windowType + " window " + windowId);
    }

    /**
     * 走向预定座位:reservation 必然存在(到达时已 reserve)。
     * 仅做 RESERVED → OCCUPIED 转换,然后进入 DINING。
     * 极端兜底:reservation 为 null 时记 no_seat_abandoned 离开,不计 takeaway。
     */
    private void processWalkToSeat(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();

        DiningArea.SeatAllocation reservation = student == null ? null : student.getSeatAllocation();
        if (reservation != null) {
            engine.confirmReservation(student);
            engine.resolveSeatDecisionPending(partySize);
            engine.recordDineIn(partySize);
            long diningTime = engine.resolveDiningTimeSeconds();
            long leaveTime = engine.getCurrentTime() + diningTime;
            engine.setStudentState(studentId, StudentState.DINING);
            engine.scheduleEvent(new StudentLeaveEvent(leaveTime, studentId));
            engine.recordState(studentId
                    + " seated for dine-in at table " + reservation.tableId()
                    + (reservation.splitGroup() ? " (split-group)" : "")
                    + " with partySize=" + partySize);
            return;
        }

        // 兜底:reservation 丢失(理论上不应发生)— post-service 路径
        engine.resolveSeatDecisionPending(partySize);
        engine.recordPostServiceNoSeat(partySize);
        engine.recordLeave(partySize);
        engine.setStudentState(studentId, StudentState.LEAVE);
        engine.recordState(studentId + " walked to seat but had no reservation, left without dining");
    }

    /**
     * 学生在窗口阶段弃排队时,如果还持有座位预定,把它归还。
     */
    private void cancelReservationOnAbandon(SimulationEngine engine, Student student) {
        if (student == null || student.getSeatAllocation() == null) {
            return;
        }
        engine.getCanteenState().cancelReservation(student.getSeatAllocation());
        student.setSeatAllocation(null);
    }
}
