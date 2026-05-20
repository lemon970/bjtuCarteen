package com.bjtu.simulation.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationResult;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.ArrivalSample;
import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.SeatCellSnapshot;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;
import com.bjtu.simulation.model.TableSnapshot;
import com.bjtu.simulation.model.TakeawayDecisionRecord;
import com.bjtu.simulation.model.WaitTimeSample;
import com.bjtu.simulation.service.SimulationMath;

public class SimulationEngine {
    private final Map<String, Student> studentRoster = new ConcurrentHashMap<>();
    private final SimConfig config;
    private final SimulationRandomSampler randomSampler;
    private final SimulationDurationPolicy durationPolicy;
    private final StudentProfileFactory studentProfileFactory = new StudentProfileFactory();
    private final WindowSelectionPolicy windowSelectionPolicy = new WindowSelectionPolicy();
    private final SimulationInvariantChecker invariantChecker = new SimulationInvariantChecker();
    private final SimulationSnapshotRecorder snapshotRecorder = new SimulationSnapshotRecorder();
    private final long effectiveSeed;

    private long currentTime = 0L;
    private final PriorityQueue<BaseEvent> eventQueue = new PriorityQueue<>();
    private final CanteenState canteenState;
    private final List<SimulationResult> history = new ArrayList<>();
    private final List<ArrivalSample> arrivalSamples = new ArrayList<>();
    private final List<WaitTimeSample> waitTimeSamples = new ArrayList<>();
    private final List<TakeawayDecisionRecord> takeawayDecisionRecords = new ArrayList<>();
    private final List<Integer> windowServedCounts;
    private final List<String> windowTypes;
    private final List<Long> windowAvailableAtSeconds;
    private int takeawayWindowCount;
    private int normalWindowCount;

    private int arrivedCount = 0;
    private int normalArrivalCount = 0;
    private int classPeakArrivalCount = 0;
    private int rainPeakArrivalCount = 0;

    private int abandonedCount = 0;
    private int abandonedByQueueCount = 0;

    private long totalWaitTime = 0L;
    private int servedCount = 0;
    private long totalMovementTime = 0L;
    private int movementSampleCount = 0;
    private int dineInCount = 0;
    private int takeawayCount = 0;
    private int pendingSeatDecisionCount = 0;
    private int noSeatSwitchToTakeawayCount = 0;
    private int weatherDrivenTakeawayCount = 0;
    private int initialTakeawayIntentCount = 0;
    private int leaveCount = 0;
    private int groupCount = 0;
    private int groupedStudentCount = 0;
    private int sameTableGroupCount = 0;
    private int splitGroupCount = 0;
    private int forcedLeaveCount = 0;

    // 座位等位队列(reserve-then-queue 失败后的兜底)
    private final Deque<String> seatWaitQueue = new ArrayDeque<>();
    private final Map<String, Long> seatWaitEnqueuedAt = new HashMap<>();
    // pre-service 路径(到达即失败、等位耐心耗尽):未进入 servedCount 分桶
    private int preServiceNoSeatCount = 0;
    // post-service 路径(已服务但 reservation 丢失):进入 servedCount 分桶
    private int postServiceNoSeatCount = 0;
    private int seatWaitQueueMax = 0;
    private long seatWaitTotalSeconds = 0L;
    private int seatWaitSampleCount = 0;
    private long reservedSeatsAccum = 0L;
    private int reservedSeatsSamples = 0;

    public SimulationEngine(SimConfig config) {
        SimConfig safeConfig = config == null ? new SimConfig() : config;
        if (safeConfig.getBaseConfig() == null) {
            safeConfig.setBaseConfig(new SimConfig.BaseConfig());
        }
        if (safeConfig.getWeatherConfig() == null) {
            safeConfig.setWeatherConfig(new SimConfig.WeatherConfig());
        }
        if (safeConfig.getRandomBounds() == null) {
            safeConfig.setRandomBounds(new SimConfig.RandomBounds());
        }
        if (safeConfig.getArrivalDist() == null) {
            safeConfig.setArrivalDist(SimConfig.DistributionSpec.poisson());
        }
        if (safeConfig.getWindowServiceDist() == null) {
            safeConfig.setWindowServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (safeConfig.getNormalServiceDist() == null) {
            safeConfig.setNormalServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (safeConfig.getDiningTimeDist() == null) {
            safeConfig.setDiningTimeDist(SimConfig.DistributionSpec.uniform());
        }

        this.config = safeConfig;
        this.effectiveSeed = safeConfig.getSeed() == null ? System.currentTimeMillis() : safeConfig.getSeed();
        this.randomSampler = new SimulationRandomSampler(new Random(effectiveSeed));
        this.durationPolicy = new SimulationDurationPolicy(safeConfig, randomSampler);

        int windowCount = Math.max(0, safeConfig.getBaseConfig().getWindowCount());
        this.takeawayWindowCount = Math.min(windowCount, Math.max(0, safeConfig.getBaseConfig().getTakeawayWindowCount()));
        this.normalWindowCount = Math.max(0, windowCount - this.takeawayWindowCount);
        this.canteenState = new CanteenState(
                windowCount,
                Math.max(0, safeConfig.getBaseConfig().getTotalSeats()),
                Math.max(0, safeConfig.getBaseConfig().getNumFourSeatTables()),
                Math.max(0, safeConfig.getBaseConfig().getNumTwoSeatTables()),
                safeConfig.getBaseConfig().getLargeTableRatio());
        this.windowServedCounts = new ArrayList<>();
        this.windowTypes = new ArrayList<>();
        this.windowAvailableAtSeconds = new ArrayList<>();
        for (int i = 0; i < windowCount; i++) {
            windowServedCounts.add(0);
            windowTypes.add(i >= this.normalWindowCount ? "TAKEAWAY" : "NORMAL");
            windowAvailableAtSeconds.add(0L);
        }
        recalculateWindowTypeCounts();
        this.currentTime = 0;
    }

    public long getEffectiveSeed() {
        return effectiveSeed;
    }

    public double nextDouble() {
        return randomSampler.nextDouble();
    }

    public int nextInt(int minInclusive, int maxInclusive) {
        return randomSampler.nextInt(minInclusive, maxInclusive);
    }

    public long nextLong(long minInclusive, long maxExclusive) {
        return randomSampler.nextLong(minInclusive, maxExclusive);
    }

    public int sampleArrivalCountForMinute(double effectiveArrivalRatePerHour) {
        return durationPolicy.sampleArrivalCountForMinute(effectiveArrivalRatePerHour);
    }

    public long sampleExponentialInterarrivalSeconds(double effectiveArrivalRatePerHour) {
        return durationPolicy.sampleExponentialInterarrivalSeconds(effectiveArrivalRatePerHour);
    }

    public int samplePartySize() {
        return durationPolicy.samplePartySize();
    }

    public long resolveServiceTimeSeconds(int windowId) {
        return durationPolicy.resolveServiceTimeSeconds(isTakeawayWindow(windowId));
    }

    public long resolveServiceTimeSeconds(int windowId, boolean willTakeaway) {
        return durationPolicy.resolveServiceTimeSeconds(isTakeawayWindow(windowId), willTakeaway);
    }

    public long reserveWindowService(int windowId, long queueEnterTime, long serviceTimeSeconds) {
        if (windowId < 0 || windowId >= windowAvailableAtSeconds.size()) {
            return Math.max(0L, queueEnterTime);
        }
        long safeQueueEnter = Math.max(0L, queueEnterTime);
        long serviceStartTime = Math.max(safeQueueEnter, windowAvailableAtSeconds.get(windowId));
        long serviceFinishTime = serviceStartTime + Math.max(0L, serviceTimeSeconds);
        windowAvailableAtSeconds.set(windowId, serviceFinishTime);
        return serviceStartTime;
    }

    public long resolveDiningTimeSeconds() {
        return durationPolicy.resolveDiningTimeSeconds();
    }

    public long resolveMovementTimeSeconds() {
        return durationPolicy.resolveMovementTimeSeconds(canteenState, pendingSeatDecisionCount);
    }

    public DiningArea.SeatAllocation trySeatStudent(Student student) {
        if (student == null) {
            return canteenState.tryOccupySeats(1, currentTime);
        }
        DiningArea.SeatAllocation allocation = canteenState.tryOccupySeats(
                student.getPartySize(),
                currentTime,
                student.getGroupId());
        student.setSeatAllocation(allocation);
        if (allocation != null && student.isGrouped()) {
            if (allocation.splitGroup()) {
                splitGroupCount++;
            } else {
                sameTableGroupCount++;
            }
        }
        return allocation;
    }

    /**
     * 学生到达即预定座位:写入 reserved 计数,不计 occupied,允许跨 2 桌拆分。
     * 成功后 student.seatAllocation 持有该 reservation,直到 confirmReservation 或 cancelReservation。
     */
    public DiningArea.SeatAllocation tryReserveSeats(Student student) {
        if (student == null) {
            return null;
        }
        DiningArea.SeatAllocation reservation = canteenState.tryReserveSeats(
                student.getPartySize(),
                currentTime,
                student.getGroupId(),
                true);
        if (reservation == null) {
            return null;
        }
        student.setSeatAllocation(reservation);
        if (student.isGrouped()) {
            if (reservation.splitGroup()) {
                splitGroupCount++;
            } else {
                sameTableGroupCount++;
            }
        }
        return reservation;
    }

    /**
     * 学生走到预定座位,把 RESERVED 转 OCCUPIED。
     */
    public void confirmReservation(Student student) {
        if (student == null || student.getSeatAllocation() == null) {
            return;
        }
        canteenState.confirmReservation(student.getSeatAllocation(), currentTime);
    }

    public void enqueueSeatWait(String studentId, int patienceTicks) {
        if (studentId == null || patienceTicks <= 0) {
            return;
        }
        if (seatWaitEnqueuedAt.containsKey(studentId)) {
            return;
        }
        seatWaitQueue.offer(studentId);
        seatWaitEnqueuedAt.put(studentId, currentTime);
        seatWaitQueueMax = Math.max(seatWaitQueueMax, seatWaitQueue.size());
        scheduleEvent(new SeatWaitEvent(currentTime + SeatWaitEvent.RETRY_INTERVAL_SECONDS,
                studentId, patienceTicks - 1));
    }

    public void removeFromSeatWaitQueue(String studentId) {
        if (studentId == null) {
            return;
        }
        Long enqueuedAt = seatWaitEnqueuedAt.remove(studentId);
        if (enqueuedAt != null) {
            seatWaitTotalSeconds += Math.max(0L, currentTime - enqueuedAt);
            seatWaitSampleCount++;
        }
        seatWaitQueue.remove(studentId);
    }

    public boolean isInSeatWaitQueue(String studentId) {
        return studentId != null && seatWaitEnqueuedAt.containsKey(studentId);
    }

    public void releaseStudentSeat(Student student) {
        if (student == null || student.getSeatAllocation() == null) {
            return;
        }
        canteenState.releaseSeats(student.getSeatAllocation(), currentTime);
        student.setSeatAllocation(null);
    }

    public Student registerStudent(String id, ArrivalGroup arrivalGroup, int partySize) {
        return registerStudent(id, arrivalGroup, partySize, null, partySize, 0);
    }

    public Student registerStudent(String id,
                                   ArrivalGroup arrivalGroup,
                                   int partySize,
                                   String groupId,
                                   int groupSize,
                                   int groupMemberIndex) {
        Student existing = studentRoster.get(id);
        if (existing != null) {
            return existing;
        }

        Student student = studentProfileFactory.create(
                id,
                arrivalGroup,
                partySize,
                groupId,
                groupSize,
                groupMemberIndex,
                config,
                canteenState.getWindowCount(),
                randomSampler);
        studentRoster.put(id, student);
        if (student.isGrouped()) {
            groupCount++;
            groupedStudentCount += student.getPartySize();
        }

        return student;
    }

    public Student getStudent(String id) {
        return studentRoster.get(id);
    }

    public void setStudentState(String id, StudentState state) {
        Student student = getStudent(id);
        if (student != null && state != null) {
            student.setState(state);
        }
    }

    public int chooseWindowForStudent(Student student) {
        boolean willTakeaway = predictTakeawayIntent(student);
        return windowSelectionPolicy.choose(
                student,
                canteenState,
                windowAvailableAtSeconds,
                windowTypes,
                currentTime,
                currentQueuePressure(),
                currentSeatUtilizationRate(),
                takeawayWindowCount,
                willTakeaway);
    }

    private boolean predictTakeawayIntent(Student student) {
        if (student == null || takeawayWindowCount <= 0) {
            return false;
        }
        return student.wantsTakeaway();
    }
    public void recordArrival(ArrivalGroup arrivalGroup, int partySize) {
        int count = Math.max(1, partySize);
        this.arrivedCount += count;
        ArrivalGroup group = arrivalGroup == null ? ArrivalGroup.NORMAL : arrivalGroup;
        if (group == ArrivalGroup.CLASS_PEAK) {
            classPeakArrivalCount += count;
        } else if (group == ArrivalGroup.RAIN_PEAK) {
            rainPeakArrivalCount += count;
        } else {
            normalArrivalCount += count;
        }
    }

    public void recordArrivalSample(long timeSeconds,
                                    long intervalSeconds,
                                    double lambdaPerHour,
                                    ArrivalGroup arrivalGroup,
                                    int partySize) {
        arrivalSamples.add(new ArrivalSample(timeSeconds, intervalSeconds, lambdaPerHour, arrivalGroup, partySize));
    }

    public void recordAbandonByQueue() {
        this.abandonedCount++;
        this.abandonedByQueueCount++;
    }

    public void recordWaitTime(long queueEnterTime, long serviceStartTime, int partySize) {
        recordWaitTime(queueEnterTime, serviceStartTime, partySize, -1, "UNKNOWN", 0);
    }

    public void recordWaitTime(long queueEnterTime,
                               long serviceStartTime,
                               int partySize,
                               int windowId,
                               String windowType,
                               int queueLengthAtJoin) {
        int count = Math.max(1, partySize);
        long safeQueueEnterTime = Math.max(0L, queueEnterTime);
        long safeServiceStartTime = Math.max(safeQueueEnterTime, serviceStartTime);
        this.totalWaitTime += (safeServiceStartTime - safeQueueEnterTime) * count;
        this.servedCount += count;
        this.waitTimeSamples.add(new WaitTimeSample(
                safeQueueEnterTime,
                safeServiceStartTime,
                count,
                windowId,
                windowType,
                queueLengthAtJoin,
                durationPolicy.resolveWaitPhase(safeServiceStartTime)));
    }

    public void recordWindowServed(int windowId, int partySize) {
        if (windowId >= 0 && windowId < windowServedCounts.size()) {
            windowServedCounts.set(windowId, windowServedCounts.get(windowId) + Math.max(1, partySize));
        }
    }

    public void recordDineIn(int partySize) {
        this.dineInCount += Math.max(1, partySize);
    }

    public void recordTakeaway(int partySize) {
        this.takeawayCount += Math.max(1, partySize);
    }

    public void recordSeatDecisionPending(int partySize) {
        this.pendingSeatDecisionCount += Math.max(1, partySize);
    }

    public void resolveSeatDecisionPending(int partySize) {
        this.pendingSeatDecisionCount = Math.max(0, this.pendingSeatDecisionCount - Math.max(1, partySize));
    }

    public void recordNoSeatSwitchToTakeaway(int partySize) {
        this.noSeatSwitchToTakeawayCount += Math.max(1, partySize);
    }

    /**
     * 学生明确想堂食但找不到座位、且耐心耗尽 → 离开。
     * 不计入 takeawayCount,单独 KPI,用户可观测"被压力赶走"的人数。
     * pre-service 路径(到达即失败 / 等位耐心耗尽):学生未进入 servedCount 分桶。
     */
    public void recordNoSeatAbandoned(int partySize) {
        this.preServiceNoSeatCount += Math.max(1, partySize);
    }

    /**
     * post-service 路径:学生已 served,但走向座位时 reservation 丢失(理论防御)。
     * 计入 servedCount 分桶,与 dineIn / takeaway 并列,但语义是"被赶走"。
     */
    public void recordPostServiceNoSeat(int partySize) {
        this.postServiceNoSeatCount += Math.max(1, partySize);
    }

    public void recordWeatherDrivenTakeaway(int partySize) {
        this.weatherDrivenTakeawayCount += Math.max(1, partySize);
    }

    public void recordTakeawayDecision(String studentId,
                                       String reason,
                                       double finalProbability,
                                       double randomRoll,
                                       double waitMinutes,
                                       double studentPreference,
                                       boolean takeaway,
                                       int partySize,
                                       double baseProbability,
                                       double preferenceFactor,
                                       double seatPressureFactor,
                                       double waitPressureFactor,
                                       double queuePressureFactor,
                                       double weatherFactor,
                                       String windowChoiceReason,
                                       String decisionReason) {
        takeawayDecisionRecords.add(new TakeawayDecisionRecord(
                currentTime,
                studentId,
                reason,
                finalProbability,
                randomRoll,
                currentSeatUtilizationRate(),
                currentQueuePressure(),
                waitMinutes,
                studentPreference,
                takeaway,
                partySize,
                baseProbability,
                preferenceFactor,
                seatPressureFactor,
                waitPressureFactor,
                queuePressureFactor,
                weatherFactor,
                windowChoiceReason,
                decisionReason));
    }

    public void recordLeave(int partySize) {
        this.leaveCount += Math.max(1, partySize);
    }

    public void recordMovementTime(long movementTimeSeconds) {
        this.totalMovementTime += Math.max(0L, movementTimeSeconds);
        this.movementSampleCount++;
    }

    public int getArrivedCount() {
        return arrivedCount;
    }

    public int getNormalArrivalCount() {
        return normalArrivalCount;
    }

    public int getClassPeakArrivalCount() {
        return classPeakArrivalCount;
    }

    public int getRainPeakArrivalCount() {
        return rainPeakArrivalCount;
    }

    public int getAbandonedCount() {
        return abandonedCount;
    }

    public int getAbandonedByQueueCount() {
        return abandonedByQueueCount;
    }

    public int getServedCount() {
        return servedCount;
    }

    public int getDineInCount() {
        return dineInCount;
    }

    public int getTakeawayCount() {
        return takeawayCount;
    }

    public int getPendingSeatDecisionCount() {
        return pendingSeatDecisionCount;
    }

    public int getNoSeatSwitchToTakeawayCount() {
        return noSeatSwitchToTakeawayCount;
    }

    public int getWeatherDrivenTakeawayCount() {
        return weatherDrivenTakeawayCount;
    }

    public int getInitialTakeawayIntentCount() {
        return initialTakeawayIntentCount;
    }

    public void recordInitialTakeawayIntent(int partySize) {
        this.initialTakeawayIntentCount += Math.max(1, partySize);
    }

    public int getLeaveCount() {
        return leaveCount;
    }

    public int getGroupCount() {
        return groupCount;
    }

    public int getGroupedStudentCount() {
        return groupedStudentCount;
    }

    public int getSameTableGroupCount() {
        return sameTableGroupCount;
    }

    public int getSplitGroupCount() {
        return splitGroupCount;
    }

    public int getNoSeatAbandonedCount() {
        return preServiceNoSeatCount + postServiceNoSeatCount;
    }

    public int getPostServiceNoSeatCount() {
        return postServiceNoSeatCount;
    }

    public int getPreServiceNoSeatCount() {
        return preServiceNoSeatCount;
    }

    public int getSeatWaitQueueMax() {
        return seatWaitQueueMax;
    }

    public int getCurrentSeatWaitQueueSize() {
        return seatWaitQueue.size();
    }

    public double getSeatWaitAvgSeconds() {
        return seatWaitSampleCount == 0 ? 0.0 : (double) seatWaitTotalSeconds / seatWaitSampleCount;
    }

    public double getReservedSeatsAvg() {
        return reservedSeatsSamples == 0 ? 0.0 : (double) reservedSeatsAccum / reservedSeatsSamples;
    }

    public void sampleReservedSeats() {
        reservedSeatsAccum += Math.max(0, canteenState.getReservedSeats());
        reservedSeatsSamples++;
    }

    public int getPeakWindowId() {
        return snapshotRecorder.getPeakWindowId();
    }

    public long getPeakTime() {
        return snapshotRecorder.getPeakTime();
    }

    public long getTotalPeakTime() {
        return snapshotRecorder.getTotalPeakTime();
    }

    public int getMaxQueueSizeEver() {
        return snapshotRecorder.getMaxQueueSizeEver();
    }

    public int getMaxTotalQueueSize() {
        return snapshotRecorder.getMaxTotalQueueSize();
    }

    public double getAvgTotalQueueSize() {
        return snapshotRecorder.getAvgTotalQueueSize();
    }

    public int getMaxOccupiedSeats() {
        return snapshotRecorder.getMaxOccupiedSeats();
    }

    public double getAvgOccupiedSeats() {
        return snapshotRecorder.getAvgOccupiedSeats();
    }

    public double getTotalWaitTimeMinutes() {
        return totalWaitTime / 60.0;
    }

    public double getAvgWaitTimeMinutes() {
        return servedCount == 0 ? 0 : (double) totalWaitTime / servedCount / 60.0;
    }

    public double getTotalMovementTimeMinutes() {
        return totalMovementTime / 60.0;
    }

    public double getAvgMovementTimeMinutes() {
        return movementSampleCount == 0 ? 0 : (double) totalMovementTime / movementSampleCount / 60.0;
    }

    public int getMovementSampleCount() {
        return movementSampleCount;
    }

    public List<Integer> getWindowServedCounts() {
        return Collections.unmodifiableList(windowServedCounts);
    }

    public List<String> getWindowTypes() {
        return Collections.unmodifiableList(windowTypes);
    }

    public int getTakeawayWindowCount() {
        return takeawayWindowCount;
    }

    public int getNormalWindowCount() {
        return normalWindowCount;
    }

    public int getTakeawayWindowServedCount() {
        int served = 0;
        for (int i = 0; i < windowServedCounts.size(); i++) {
            if (isTakeawayWindow(i)) {
                served += windowServedCounts.get(i);
            }
        }
        return served;
    }

    public int getNormalWindowServedCount() {
        int served = 0;
        for (int i = 0; i < windowServedCounts.size(); i++) {
            if (!isTakeawayWindow(i)) {
                served += windowServedCounts.get(i);
            }
        }
        return served;
    }

    public boolean isTakeawayWindow(int windowId) {
        return windowId >= 0 && windowId < canteenState.getWindowCount() && isTakeawayWindowIndex(windowId);
    }

    private boolean isTakeawayWindowIndex(int windowId) {
        return windowId >= 0
                && windowId < windowTypes.size()
                && "TAKEAWAY".equalsIgnoreCase(windowTypes.get(windowId));
    }

    public SimConfig getConfig() {
        return config;
    }

    public CanteenState getCanteenState() {
        return canteenState;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public List<SimulationResult> getHistory() {
        return history;
    }

    public List<ArrivalSample> getArrivalSamples() {
        return Collections.unmodifiableList(arrivalSamples);
    }

    public List<WaitTimeSample> getWaitTimeSamples() {
        return Collections.unmodifiableList(waitTimeSamples);
    }

    public List<TakeawayDecisionRecord> getTakeawayDecisionRecords() {
        return Collections.unmodifiableList(takeawayDecisionRecords);
    }

    public void scheduleEvent(BaseEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventTime() < currentTime) {
            throw new IllegalArgumentException("event time cannot be earlier than current simulation time");
        }
        eventQueue.offer(event);
    }

    public int getCurrentTotalQueueSize() {
        int total = 0;
        for (int size : canteenState.getWindowQueues()) {
            total += Math.max(0, size);
        }
        return total;
    }

    public double currentSeatUtilizationRate() {
        int totalSeats = Math.max(0, canteenState.getTotalSeats());
        if (totalSeats == 0) {
            return 1.0;
        }
        return SimulationMath.clamp((double) canteenState.getOccupiedSeats() / totalSeats, 0.0, 1.0);
    }

    public double currentQueuePressure() {
        int windowCount = Math.max(1, canteenState.getWindowCount());
        int queueLimit = config == null ? 10 : Math.max(1, config.getQueueLimit());
        return SimulationMath.clamp((double) getCurrentTotalQueueSize() / (windowCount * queueLimit), 0.0, 1.0);
    }

    public void runUntil(long targetTime) {
        while (!eventQueue.isEmpty() && eventQueue.peek().getEventTime() <= targetTime) {
            BaseEvent event = eventQueue.poll();
            runScheduledEvent(event);
        }
        currentTime = Math.max(currentTime, targetTime);
    }

    public void runAll() {
        while (!eventQueue.isEmpty()) {
            BaseEvent event = eventQueue.poll();
            runScheduledEvent(event);
        }
        finalizeLingeringDiners();
    }

    /**
     * 仿真结束时,把仍持有座位但未触发 LeaveEvent 的学生强制结束:
     * 释放座位、推进 leaveCount、转入 LEAVE 状态。
     * 这一步避免座位图出现"无人却 OCCUPIED"的残留观感,
     * 同时保留 leaveCount <= servedCount 不变量。
     */
    private void finalizeLingeringDiners() {
        // 仍在等位队列的学生:计 no_seat_abandoned 离开,不算 takeaway
        for (String waitingId : new ArrayList<>(seatWaitQueue)) {
            Student student = studentRoster.get(waitingId);
            if (student == null || student.getState() == StudentState.LEAVE) {
                continue;
            }
            int partySize = student.getPartySize();
            removeFromSeatWaitQueue(waitingId);
            // 取消可能仍持有的预定(理论上等位时无 reservation,防御性处理)
            if (student.getSeatAllocation() != null) {
                canteenState.cancelReservation(student.getSeatAllocation());
                student.setSeatAllocation(null);
            }
            recordNoSeatAbandoned(partySize);
            student.setState(StudentState.LEAVE);
            forcedLeaveCount += partySize;
        }

        for (Student student : studentRoster.values()) {
            if (student == null) {
                continue;
            }
            if (student.getSeatAllocation() == null) {
                continue;
            }
            if (student.getState() == StudentState.LEAVE) {
                continue;
            }
            int partySize = student.getPartySize();
            releaseStudentSeat(student);
            recordLeave(partySize);
            student.setState(StudentState.LEAVE);
            forcedLeaveCount += partySize;
        }
        if (forcedLeaveCount > 0) {
            recordState("simulation finalize: force-released " + forcedLeaveCount + " lingering diners");
        }
    }

    public int getForcedLeaveCount() {
        return forcedLeaveCount;
    }

    private void runScheduledEvent(BaseEvent event) {
        if (event.getEventTime() < currentTime) {
            throw new IllegalStateException("event time ordering violated");
        }
        currentTime = event.getEventTime();
        event.process(this);
        invariantChecker.validate(this);
    }

    private void recalculateWindowTypeCounts() {
        int takeaway = 0;
        int normal = 0;
        for (String type : windowTypes) {
            if ("TAKEAWAY".equalsIgnoreCase(type)) {
                takeaway++;
            } else {
                normal++;
            }
        }
        this.takeawayWindowCount = takeaway;
        this.normalWindowCount = normal;
    }

    public void recordState(String message) {
        this.history.add(snapshotRecorder.record(this, message));
    }

    public List<TableSnapshot> getTableSnapshots() {
        return canteenState.getTableSnapshots(currentTime);
    }

    public List<SeatCellSnapshot> getSeatCells() {
        return canteenState.getSeatCells(currentTime);
    }

    public long getOccupiedSeatSeconds() {
        return canteenState.getOccupiedSeatSeconds(currentTime);
    }

}
