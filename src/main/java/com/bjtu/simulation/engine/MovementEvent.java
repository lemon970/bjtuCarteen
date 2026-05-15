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
    private static final long SEAT_SEARCH_RETRY_SECONDS = 30L;

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
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: global queue limit reached");
            return;
        }

        boolean allBlockedByPersonalLimit = queues.stream().allMatch(q -> q + partySize > patienceLimit);
        if (allBlockedByPersonalLimit) {
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: personal patience limit reached");
            return;
        }

        int windowId = engine.chooseWindowForStudent(student);
        if (windowId < 0) {
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: failed to choose window");
            return;
        }
        int chosenQueueSize = queues.get(windowId);
        if (chosenQueueSize + partySize > globalQueueLimit || chosenQueueSize + partySize > patienceLimit) {
            engine.recordAbandonByQueue();
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " abandoned after walking: chosen queue exceeds tolerance");
            return;
        }

        // [重构] 队列长度按“人数”而非“事件数”累计，原因是组团到达时前端排队人数和后端统计口径必须一致。
        engine.getCanteenState().joinQueue(windowId, partySize);
        engine.setStudentState(studentId, StudentState.QUEUING);

        long serviceTime = engine.resolveServiceTimeSeconds(windowId);
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

    private void processWalkToSeat(SimulationEngine engine) {
        Student student = engine.getStudent(studentId);
        int partySize = student == null ? 1 : student.getPartySize();
        DiningArea.SeatAllocation allocation = engine.trySeatStudent(student);
        if (allocation != null) {
            engine.resolveSeatDecisionPending(partySize);
            engine.recordDineIn(partySize);
            long diningTime = engine.resolveDiningTimeSeconds();
            long leaveTime = engine.getCurrentTime() + diningTime;
            engine.setStudentState(studentId, StudentState.DINING);
            engine.scheduleEvent(new StudentLeaveEvent(leaveTime, studentId));
            engine.recordState(studentId + " seated for dine-in at table " + allocation.tableId() + " with partySize=" + partySize);
            return;
        }

        int seatSearchPatience = student == null ? 0 : student.getSeatSearchPatience();
        if (seatSearchPatience <= 0) {
            engine.resolveSeatDecisionPending(partySize);
            engine.recordTakeawayDecision(
                    studentId,
                    "NO_SEAT_SWITCH",
                    1.0,
                    0.0,
                    0.0,
                    student == null ? 0.5 : student.getPackPreference(),
                    true,
                    partySize,
                    engine.getConfig() == null ? 0.0 : engine.getConfig().getPackProbability(),
                    0.0,
                    1.0,
                    0.0,
                    engine.currentQueuePressure(),
                    0.0,
                    "普通窗口后尝试就座",
                    "无可用座位，转为打包离开");
            engine.recordTakeaway(partySize);
            engine.recordNoSeatSwitchToTakeaway(partySize);
            engine.recordLeave(partySize);
            engine.setStudentState(studentId, StudentState.PACK_LEAVE);
            engine.setStudentState(studentId, StudentState.LEAVE);
            engine.recordState(studentId + " switched to takeaway after walking to full dining area");
            return;
        }

        engine.setStudentState(studentId, StudentState.FIND_SEAT);
        engine.scheduleEvent(new SeatSearchEvent(
                engine.getCurrentTime() + SEAT_SEARCH_RETRY_SECONDS,
                studentId,
                seatSearchPatience));
        engine.recordState(studentId + " searching table after walking to dining area");
    }
}
