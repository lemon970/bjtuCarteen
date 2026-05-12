package com.bjtu.simulation.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationResult;
import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.Student;
import com.bjtu.simulation.model.StudentState;
import com.bjtu.simulation.model.TableSnapshot;

public class SimulationEngine {
    private final Map<String, Student> studentRoster = new ConcurrentHashMap<>();
    private final SimConfig config;
    private final Random random;
    private final long effectiveSeed;

    private long currentTime = 0L;
    private final PriorityQueue<BaseEvent> eventQueue = new PriorityQueue<>();
    private final CanteenState canteenState;
    private final List<SimulationResult> history = new ArrayList<>();
    private final List<Integer> windowServedCounts;
    private final List<String> windowTypes;
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
    private int leaveCount = 0;

    private long peakTime = 0L;
    private int peakWindowId = -1;
    private int maxQueueSizeEver = 0;

    private int maxTotalQueueSize = 0;
    private long totalQueueSizeSum = 0L;
    private int queueSizeSamples = 0;

    private int maxOccupiedSeats = 0;
    private long occupiedSeatsSum = 0L;
    private int occupiedSeatsSamples = 0;

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
        this.random = new Random(effectiveSeed);

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
        for (int i = 0; i < windowCount; i++) {
            windowServedCounts.add(0);
            windowTypes.add(i >= this.normalWindowCount ? "TAKEAWAY" : "NORMAL");
        }
        recalculateWindowTypeCounts();
        this.currentTime = 0;
    }

    public long getEffectiveSeed() {
        return effectiveSeed;
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public int nextInt(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        int bound = (maxInclusive - minInclusive) + 1;
        return minInclusive + random.nextInt(bound);
    }

    public long nextLong(long minInclusive, long maxExclusive) {
        if (maxExclusive <= minInclusive) {
            return minInclusive;
        }
        return random.nextLong(minInclusive, maxExclusive);
    }

    public int sampleArrivalCountForMinute(double effectiveArrivalRatePerHour) {
        double lambdaPerHour = config.getArrivalDist().getLambda() > 0
                ? config.getArrivalDist().getLambda()
                : Math.max(0.0, effectiveArrivalRatePerHour);
        double lambdaPerMinute = Math.max(0.0, lambdaPerHour / 60.0);
        String type = normalizeDistributionType(config.getArrivalDist(), "POISSON");

        if ("FIXED".equals(type)) {
            return (int) Math.floor(lambdaPerMinute);
        }
        if ("UNIFORM".equals(type)) {
            long min = Math.max(0L, config.getArrivalDist().getMin());
            long max = config.getArrivalDist().getMax() > 0 ? Math.max(min, config.getArrivalDist().getMax()) : Math.max(min, (long) Math.ceil(lambdaPerMinute * 2));
            return (int) nextLong(min, max + 1);
        }
        return samplePoisson(lambdaPerMinute);
    }

    public int samplePartySize() {
        int configuredPartySize = Math.max(1, config.getPartySize());
        if (configuredPartySize <= 1 || nextDouble() >= clamp(config.getGroupArrivalProb(), 0.0, 1.0)) {
            return 1;
        }
        return configuredPartySize;
    }

    public long resolveServiceTimeSeconds(int windowId) {
        SimConfig.DistributionSpec spec = isTakeawayWindow(windowId)
                ? config.getWindowServiceDist()
                : config.getNormalServiceDist();
        long sampled = sampleDurationSeconds(spec, serviceRangeMin(), serviceRangeMax());
        if (!isTakeawayWindow(windowId)) {
            return sampled;
        }

        double multiplier = 1.15;
        if (config.getBaseConfig() != null) {
            multiplier = config.getBaseConfig().getTakeawayServiceTimeMultiplier();
        }
        multiplier = Double.isNaN(multiplier) || Double.isInfinite(multiplier) ? 1.15 : Math.max(1.0, multiplier);
        return Math.max(sampled, Math.round(sampled * multiplier));
    }

    public long resolveDiningTimeSeconds() {
        return sampleDurationSeconds(config.getDiningTimeDist(), diningRangeMin(), diningRangeMax());
    }

    public long resolveMovementTimeSeconds() {
        double walkTimeMean = config == null ? 0.0 : Math.max(0.0, config.getWalkTimeMean());
        if (walkTimeMean <= 0.0) {
            return 0L;
        }
        double penalty = config == null ? 0.0 : Math.max(0.0, config.getCongestionPenalty());
        double pressure = currentPeopleInSystem() / (double) Math.max(1, maxPeopleCapacity());
        double movementTime = walkTimeMean * (1.0 + penalty * pressure);
        return Math.max(0L, Math.round(movementTime));
    }

    public DiningArea.SeatAllocation trySeatStudent(Student student) {
        if (student == null) {
            return canteenState.tryOccupySeats(1, currentTime);
        }
        DiningArea.SeatAllocation allocation = canteenState.tryOccupySeats(student.getPartySize(), currentTime);
        student.setSeatAllocation(allocation);
        return allocation;
    }

    public void releaseStudentSeat(Student student) {
        if (student == null || student.getSeatAllocation() == null) {
            canteenState.releaseSeat();
            return;
        }
        canteenState.releaseSeats(student.getSeatAllocation(), currentTime);
        student.setSeatAllocation(null);
    }

    public Student registerStudent(String id, ArrivalGroup arrivalGroup) {
        return registerStudent(id, arrivalGroup, 1);
    }

    public Student registerStudent(String id, ArrivalGroup arrivalGroup, int partySize) {
        Student existing = studentRoster.get(id);
        if (existing != null) {
            return existing;
        }

        double minPref = 0.1;
        double maxPref = 0.3;
        if (config.getRandomBounds() != null && config.getRandomBounds().getPreferenceRange() != null
                && config.getRandomBounds().getPreferenceRange().size() >= 2) {
            double a = config.getRandomBounds().getPreferenceRange().get(0);
            double b = config.getRandomBounds().getPreferenceRange().get(1);
            minPref = Math.min(a, b);
            maxPref = Math.max(a, b);
        }
        minPref = clamp(minPref, 0.0, 1.0);
        maxPref = clamp(maxPref, minPref, 1.0);
        double rawPackPreference = minPref + (maxPref - minPref) * nextDouble();
        double packPreference = discretizeProbability(rawPackPreference, 0.05);
        Student.PackPreferenceLevel packPreferenceLevel = resolvePackPreferenceLevel(packPreference, minPref, maxPref);

        int queueLimit = Math.max(0, config.getQueueLimit());
        Student.PatienceLevel patienceLevel = samplePatienceLevel();
        int patienceLimit = resolvePatienceLimit(queueLimit, patienceLevel);

        int windowCount = canteenState.getWindowCount();
        int windowPreference = windowCount == 0 ? 0 : nextInt(0, windowCount - 1);

        Student.SeatToleranceLevel seatToleranceLevel = sampleSeatToleranceLevel(packPreferenceLevel);
        int seatSearchPatience = resolveSeatSearchPatience(seatToleranceLevel, patienceLevel);

        Student student = new Student(
                id,
                packPreference,
                patienceLimit,
                windowPreference,
                seatSearchPatience,
                arrivalGroup == null ? ArrivalGroup.NORMAL : arrivalGroup,
                packPreferenceLevel,
                patienceLevel,
                seatToleranceLevel,
                Math.max(1, partySize));
        studentRoster.put(id, student);

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
        List<Integer> queues = canteenState.getWindowQueues();
        if (queues.isEmpty()) {
            return -1;
        }

        int shortestWindow = canteenState.findShortestQueueIndex();
        if (student == null) {
            return shortestWindow;
        }

        int preferred = Math.floorMod(student.getWindowPreference(), queues.size());
        int patienceLimit = Math.max(0, student.getPatienceLimit());
        double nonPreferredPenalty = switch (student.getPatienceLevel()) {
            case LOW -> 0.15;
            case MEDIUM -> 0.45;
            case HIGH -> 0.90;
        };

        int bestWindow = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < queues.size(); i++) {
            int queueSize = queues.get(i);
            if (queueSize >= patienceLimit) {
                continue;
            }

            double preferencePenalty = (i == preferred) ? 0.0 : nonPreferredPenalty;
            double score = queueSize + preferencePenalty + windowTypePenalty(student, i);
            if (score < bestScore) {
                bestScore = score;
                bestWindow = i;
            }
        }
        return bestWindow;
    }

    private double windowTypePenalty(Student student, int windowId) {
        if (student == null || takeawayWindowCount <= 0) {
            return 0.0;
        }

        boolean takeawayWindow = isTakeawayWindow(windowId);
        return switch (student.getPackPreferenceLevel()) {
            case TAKEAWAY_BIASED -> takeawayWindow ? -0.60 : 0.40;
            case BALANCED -> takeawayWindow ? 0.10 : 0.00;
            case DINE_IN_BIASED -> takeawayWindow ? 2.00 : -0.20;
        };
    }

    public void recordArrival(ArrivalGroup arrivalGroup) {
        recordArrival(arrivalGroup, 1);
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

    public void recordAbandonByQueue() {
        this.abandonedCount++;
        this.abandonedByQueueCount++;
    }

    public void recordWaitTime(long arriveTime) {
        recordWaitTime(arriveTime, 1);
    }

    public void recordWaitTime(long arriveTime, int partySize) {
        int count = Math.max(1, partySize);
        this.totalWaitTime += Math.max(0L, this.currentTime - arriveTime) * count;
        this.servedCount += count;
    }

    public void recordWindowServed(int windowId) {
        recordWindowServed(windowId, 1);
    }

    public void recordWindowServed(int windowId, int partySize) {
        if (windowId >= 0 && windowId < windowServedCounts.size()) {
            windowServedCounts.set(windowId, windowServedCounts.get(windowId) + Math.max(1, partySize));
        }
    }

    public void recordDineIn() {
        recordDineIn(1);
    }

    public void recordDineIn(int partySize) {
        this.dineInCount += Math.max(1, partySize);
    }

    public void recordTakeaway() {
        recordTakeaway(1);
    }

    public void recordTakeaway(int partySize) {
        this.takeawayCount += Math.max(1, partySize);
    }

    public void recordSeatDecisionPending() {
        recordSeatDecisionPending(1);
    }

    public void recordSeatDecisionPending(int partySize) {
        this.pendingSeatDecisionCount += Math.max(1, partySize);
    }

    public void resolveSeatDecisionPending() {
        resolveSeatDecisionPending(1);
    }

    public void resolveSeatDecisionPending(int partySize) {
        this.pendingSeatDecisionCount = Math.max(0, this.pendingSeatDecisionCount - Math.max(1, partySize));
    }

    public void recordNoSeatSwitchToTakeaway() {
        recordNoSeatSwitchToTakeaway(1);
    }

    public void recordNoSeatSwitchToTakeaway(int partySize) {
        this.noSeatSwitchToTakeawayCount += Math.max(1, partySize);
    }

    public void recordWeatherDrivenTakeaway() {
        recordWeatherDrivenTakeaway(1);
    }

    public void recordWeatherDrivenTakeaway(int partySize) {
        this.weatherDrivenTakeawayCount += Math.max(1, partySize);
    }

    public void recordLeave() {
        recordLeave(1);
    }

    public void recordLeave(int partySize) {
        this.leaveCount += Math.max(1, partySize);
    }

    public void recordMovementTime(long movementTimeSeconds) {
        this.totalMovementTime += Math.max(0L, movementTimeSeconds);
        this.movementSampleCount++;
    }

    public void checkPeak() {
        List<Integer> queues = canteenState.getWindowQueues();
        for (int i = 0; i < queues.size(); i++) {
            if (queues.get(i) > maxQueueSizeEver) {
                maxQueueSizeEver = queues.get(i);
                peakTime = this.currentTime;
                peakWindowId = i;
            }
        }
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

    public int getLeaveCount() {
        return leaveCount;
    }

    public int getPeakWindowId() {
        return peakWindowId;
    }

    public long getPeakTime() {
        return peakTime;
    }

    public int getMaxQueueSizeEver() {
        return maxQueueSizeEver;
    }

    public int getMaxTotalQueueSize() {
        return maxTotalQueueSize;
    }

    public double getAvgTotalQueueSize() {
        return queueSizeSamples == 0 ? 0 : (double) totalQueueSizeSum / queueSizeSamples;
    }

    public int getMaxOccupiedSeats() {
        return maxOccupiedSeats;
    }

    public double getAvgOccupiedSeats() {
        return occupiedSeatsSamples == 0 ? 0 : (double) occupiedSeatsSum / occupiedSeatsSamples;
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
    }

    private void runScheduledEvent(BaseEvent event) {
        if (event.getEventTime() < currentTime) {
            throw new IllegalStateException("event time ordering violated");
        }
        currentTime = event.getEventTime();
        event.process(this);
        validateInvariants();
    }

    private void validateInvariants() {
        for (Integer queueSize : canteenState.getWindowQueues()) {
            if (queueSize == null || queueSize < 0) {
                throw new IllegalStateException("queue size must be >= 0");
            }
        }
        if (canteenState.getOccupiedSeats() < 0) {
            throw new IllegalStateException("occupiedSeats must be >= 0");
        }
        if (canteenState.getOccupiedSeats() > canteenState.getTotalSeats()) {
            throw new IllegalStateException("occupiedSeats must be <= totalSeats");
        }
        if (servedCount > arrivedCount) {
            throw new IllegalStateException("servedCount cannot exceed arrivedCount");
        }
        if (leaveCount > servedCount) {
            throw new IllegalStateException("leaveCount cannot exceed servedCount");
        }
        if (pendingSeatDecisionCount < 0) {
            throw new IllegalStateException("pendingSeatDecisionCount must be >= 0");
        }
        if (dineInCount + takeawayCount + pendingSeatDecisionCount != servedCount) {
            throw new IllegalStateException("dineInCount + takeawayCount + pendingSeatDecisionCount must equal servedCount");
        }
        if (abandonedByQueueCount > abandonedCount) {
            throw new IllegalStateException("abandonedByQueueCount cannot exceed abandonedCount");
        }
        if (noSeatSwitchToTakeawayCount > takeawayCount) {
            throw new IllegalStateException("noSeatSwitchToTakeawayCount cannot exceed takeawayCount");
        }
        if (normalArrivalCount + classPeakArrivalCount + rainPeakArrivalCount != arrivedCount) {
            throw new IllegalStateException("arrival group counts must equal arrivedCount");
        }

        int servedByWindow = 0;
        for (int count : windowServedCounts) {
            if (count < 0) {
                throw new IllegalStateException("window served count must be >= 0");
            }
            servedByWindow += count;
        }
        if (servedByWindow != servedCount) {
            throw new IllegalStateException("sum(windowServedCounts) must equal servedCount");
        }
        if (getNormalWindowServedCount() + getTakeawayWindowServedCount() != servedCount) {
            throw new IllegalStateException("normalWindowServedCount + takeawayWindowServedCount must equal servedCount");
        }
    }

    private String normalizeWindowType(String type) {
        if (type == null || type.isBlank()) {
            return "NORMAL";
        }
        String normalized = type.trim().toUpperCase();
        return "TAKEAWAY".equals(normalized) ? "TAKEAWAY" : "NORMAL";
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
        List<Integer> currentQueues = new ArrayList<>(this.canteenState.getWindows());
        int totalQueueSize = 0;
        for (int size : currentQueues) {
            totalQueueSize += size;
        }
        totalQueueSizeSum += totalQueueSize;
        queueSizeSamples++;
        if (totalQueueSize > maxTotalQueueSize) {
            maxTotalQueueSize = totalQueueSize;
        }

        int occupiedSeats = this.canteenState.getOccupiedSeats();
        occupiedSeatsSum += occupiedSeats;
        occupiedSeatsSamples++;
        if (occupiedSeats > maxOccupiedSeats) {
            maxOccupiedSeats = occupiedSeats;
        }

        checkPeak();
        int emptySeats = Math.max(0, this.canteenState.getTotalSeats() - occupiedSeats);
        String safeMessage = message == null ? "" : message;
        SimulationResult snapshot = new SimulationResult(
                this.currentTime,
                currentQueues,
                totalQueueSize,
                occupiedSeats,
                emptySeats,
                safeMessage,
                arrivedCount,
                abandonedCount,
                abandonedByQueueCount,
                servedCount,
                dineInCount,
                takeawayCount,
                pendingSeatDecisionCount,
                noSeatSwitchToTakeawayCount,
                weatherDrivenTakeawayCount,
                leaveCount,
                normalArrivalCount,
                classPeakArrivalCount,
                rainPeakArrivalCount,
                movementSampleCount,
                getTotalMovementTimeMinutes(),
                getAvgMovementTimeMinutes(),
                List.of());

        this.history.add(snapshot);
    }

    public List<TableSnapshot> getTableSnapshots() {
        return canteenState.getTableSnapshots(currentTime);
    }

    private long sampleDurationSeconds(SimConfig.DistributionSpec spec, long fallbackMin, long fallbackMax) {
        SimConfig.DistributionSpec safeSpec = spec == null ? SimConfig.DistributionSpec.uniform() : spec;
        String type = normalizeDistributionType(safeSpec, "UNIFORM");
        boolean explicitDistributionParam = safeSpec.getMean() > 0 || safeSpec.getLambda() > 0 || safeSpec.getStd() > 0;
        boolean useFallbackBounds = "UNIFORM".equals(type) || !explicitDistributionParam;
        long min = safeSpec.getMin() > 0 ? safeSpec.getMin() : (useFallbackBounds ? fallbackMin : 1L);
        long max = safeSpec.getMax() > 0 ? safeSpec.getMax() : (useFallbackBounds ? fallbackMax : Long.MAX_VALUE / 4L);
        min = Math.max(0L, min);
        max = Math.max(min + 1L, max);

        double sampled;
        if ("NORMAL".equals(type)) {
            double mean = safeSpec.getMean() > 0 ? safeSpec.getMean() : (min + max) / 2.0;
            double std = safeSpec.getStd() > 0 ? safeSpec.getStd() : Math.max(1.0, (max - min) / 6.0);
            sampled = mean + random.nextGaussian() * std;
        } else if ("EXPONENTIAL".equals(type)) {
            double mean = safeSpec.getMean() > 0 ? safeSpec.getMean() : (min + max) / 2.0;
            if (safeSpec.getLambda() > 0) {
                mean = 1.0 / safeSpec.getLambda();
            }
            double u = Math.max(1.0e-12, 1.0 - nextDouble());
            sampled = -Math.log(u) * Math.max(1.0e-9, mean);
        } else if ("POISSON".equals(type)) {
            double lambda = safeSpec.getLambda() > 0 ? safeSpec.getLambda() : Math.max(0.0, safeSpec.getMean());
            if (lambda <= 0.0) {
                lambda = (min + max) / 2.0;
            }
            sampled = samplePoisson(lambda);
        } else if ("FIXED".equals(type)) {
            sampled = safeSpec.getMean() > 0 ? safeSpec.getMean() : min;
        } else {
            long upperExclusive = max == Long.MAX_VALUE ? max : max + 1L;
            sampled = nextLong(min, upperExclusive);
        }

        long rounded = Math.round(sampled);
        if (rounded < min) {
            return min;
        }
        if (rounded > max) {
            return max;
        }
        return rounded;
    }

    private int samplePoisson(double lambda) {
        if (lambda <= 0.0 || Double.isNaN(lambda) || Double.isInfinite(lambda)) {
            return 0;
        }
        if (lambda < 40.0) {
            double limit = Math.exp(-lambda);
            int k = 0;
            double product = 1.0;
            do {
                k++;
                product *= nextDouble();
            } while (product > limit);
            return Math.max(0, k - 1);
        }

        double sampled = lambda + random.nextGaussian() * Math.sqrt(lambda);
        return Math.max(0, (int) Math.round(sampled));
    }

    private String normalizeDistributionType(SimConfig.DistributionSpec spec, String fallback) {
        String raw = spec == null ? null : spec.getType();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim().toUpperCase();
    }

    private long serviceRangeMin() {
        return rangeValue(config.getRandomBounds().getServiceRange(), 0, 60L);
    }

    private long serviceRangeMax() {
        return rangeValue(config.getRandomBounds().getServiceRange(), 1, 300L);
    }

    private long diningRangeMin() {
        return rangeValue(config.getRandomBounds().getDiningRange(), 0, 600L);
    }

    private long diningRangeMax() {
        return rangeValue(config.getRandomBounds().getDiningRange(), 1, 1800L);
    }

    private long rangeValue(List<Integer> range, int index, long fallback) {
        if (range == null || range.size() <= index || range.get(index) == null) {
            return fallback;
        }
        return Math.max(1L, range.get(index));
    }

    private int currentPeopleInSystem() {
        int queueSize = 0;
        for (int size : canteenState.getWindowQueues()) {
            queueSize += Math.max(0, size);
        }
        return queueSize + canteenState.getOccupiedSeats() + Math.max(0, pendingSeatDecisionCount);
    }

    private int maxPeopleCapacity() {
        int seatCapacity = Math.max(0, canteenState.getTotalSeats());
        int queueCapacity = Math.max(0, config.getQueueLimit()) * Math.max(0, canteenState.getWindowCount());
        return Math.max(1, seatCapacity + queueCapacity);
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private double discretizeProbability(double value, double step) {
        if (step <= 0) {
            return clamp(value, 0.0, 1.0);
        }
        double clamped = clamp(value, 0.0, 1.0);
        double scaled = Math.round(clamped / step) * step;
        return clamp(scaled, 0.0, 1.0);
    }

    private Student.PackPreferenceLevel resolvePackPreferenceLevel(double packPreference, double minPref, double maxPref) {
        double span = Math.max(0.0001, maxPref - minPref);
        double normalized = (packPreference - minPref) / span;
        if (normalized < 0.34) {
            return Student.PackPreferenceLevel.DINE_IN_BIASED;
        }
        if (normalized < 0.67) {
            return Student.PackPreferenceLevel.BALANCED;
        }
        return Student.PackPreferenceLevel.TAKEAWAY_BIASED;
    }

    private Student.PatienceLevel samplePatienceLevel() {
        double roll = nextDouble();
        if (roll < 0.30) {
            return Student.PatienceLevel.LOW;
        }
        if (roll < 0.75) {
            return Student.PatienceLevel.MEDIUM;
        }
        return Student.PatienceLevel.HIGH;
    }

    private int resolvePatienceLimit(int queueLimit, Student.PatienceLevel patienceLevel) {
        if (queueLimit <= 0) {
            return 0;
        }

        return switch (patienceLevel) {
            case LOW -> nextInt(Math.max(0, queueLimit - 4), Math.max(0, queueLimit - 1));
            case MEDIUM -> nextInt(Math.max(0, queueLimit - 2), queueLimit + 1);
            case HIGH -> nextInt(queueLimit, queueLimit + 4);
        };
    }

    private Student.SeatToleranceLevel sampleSeatToleranceLevel(Student.PackPreferenceLevel packPreferenceLevel) {
        double roll = nextDouble();
        return switch (packPreferenceLevel) {
            case TAKEAWAY_BIASED -> {
                if (roll < 0.60) {
                    yield Student.SeatToleranceLevel.LOW;
                }
                if (roll < 0.90) {
                    yield Student.SeatToleranceLevel.MEDIUM;
                }
                yield Student.SeatToleranceLevel.HIGH;
            }
            case BALANCED -> {
                if (roll < 0.25) {
                    yield Student.SeatToleranceLevel.LOW;
                }
                if (roll < 0.75) {
                    yield Student.SeatToleranceLevel.MEDIUM;
                }
                yield Student.SeatToleranceLevel.HIGH;
            }
            case DINE_IN_BIASED -> {
                if (roll < 0.10) {
                    yield Student.SeatToleranceLevel.LOW;
                }
                if (roll < 0.55) {
                    yield Student.SeatToleranceLevel.MEDIUM;
                }
                yield Student.SeatToleranceLevel.HIGH;
            }
        };
    }

    private int resolveSeatSearchPatience(Student.SeatToleranceLevel seatToleranceLevel, Student.PatienceLevel patienceLevel) {
        int base = switch (seatToleranceLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };

        int patienceAdjustment = switch (patienceLevel) {
            case LOW -> -1;
            case MEDIUM -> 0;
            case HIGH -> 1;
        };
        return Math.max(0, Math.min(4, base + patienceAdjustment));
    }
}
