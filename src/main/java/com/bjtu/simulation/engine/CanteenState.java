package com.bjtu.simulation.engine;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.model.DiningArea;
import com.bjtu.simulation.model.TableSnapshot;

public class CanteenState {
    private final DiningArea diningArea;
    private final List<Integer> windowQueues;

    public CanteenState(int windowCount, int totalSeats) {
        this(windowCount, totalSeats, 0, 0, 0.8);
    }

    public CanteenState(int windowCount,
                        int totalSeats,
                        int numFourSeatTables,
                        int numTwoSeatTables,
                        double largeTableRatio) {
        this.diningArea = new DiningArea(totalSeats, numFourSeatTables, numTwoSeatTables, largeTableRatio);
        this.windowQueues = new ArrayList<>();
        for (int i = 0; i < Math.max(0, windowCount); i++) {
            windowQueues.add(0);
        }
    }

    public int findShortestQueueIndex() {
        if (windowQueues.isEmpty()) {
            return -1;
        }
        int minIndex = 0;
        int minSize = windowQueues.get(0);
        for (int i = 1; i < windowQueues.size(); i++) {
            if (windowQueues.get(i) < minSize) {
                minSize = windowQueues.get(i);
                minIndex = i;
            }
        }
        return minIndex;
    }

    public int findShortestQueue() {
        int minIndex = findShortestQueueIndex();
        if (minIndex >= 0) {
            joinQueue(minIndex);
        }
        return minIndex;
    }

    public void joinQueue(int windowId) {
        joinQueue(windowId, 1);
    }

    public void joinQueue(int windowId, int partySize) {
        if (windowId < 0 || windowId >= windowQueues.size()) {
            return;
        }
        windowQueues.set(windowId, windowQueues.get(windowId) + Math.max(1, partySize));
    }

    public void leaveQueue(int windowId) {
        leaveQueue(windowId, 1);
    }

    public void leaveQueue(int windowId, int partySize) {
        if (windowId < 0 || windowId >= windowQueues.size()) {
            return;
        }
        int current = windowQueues.get(windowId);
        if (current > 0) {
            windowQueues.set(windowId, Math.max(0, current - Math.max(1, partySize)));
        }
    }

    public int addWindow() {
        windowQueues.add(0);
        return windowQueues.size() - 1;
    }

    public boolean tryOccupySeat() {
        return diningArea.tryOccupySeat();
    }

    public DiningArea.SeatAllocation tryOccupySeats(int seats, long currentTime) {
        return diningArea.tryOccupySeats(seats, currentTime);
    }

    public DiningArea.SeatAllocation tryOccupySeats(int seats, long currentTime, String groupId) {
        return diningArea.tryOccupySeats(seats, currentTime, groupId);
    }

    public void releaseSeat() {
        diningArea.releaseSeat();
    }

    public void releaseSeats(DiningArea.SeatAllocation allocation, long currentTime) {
        diningArea.releaseSeats(allocation, currentTime);
    }

    public int getWindowCount() {
        return windowQueues.size();
    }

    public List<Integer> getWindowQueues() {
        return windowQueues;
    }

    public List<Integer> getWindows() {
        return this.windowQueues;
    }

    public int getOccupiedSeats() {
        return diningArea.getOccupiedSeats();
    }

    public int getTotalSeats() {
        return diningArea.getTotalSeats();
    }

    public int getEmptySeats() {
        return diningArea.getEmptySeats();
    }

    public DiningArea getDiningArea() {
        return diningArea;
    }

    public List<TableSnapshot> getTableSnapshots(long currentTime) {
        return diningArea.getTableSnapshots(currentTime);
    }

    public long getOccupiedSeatSeconds(long currentTime) {
        return diningArea.getOccupiedSeatSeconds(currentTime);
    }

    public List<com.bjtu.simulation.model.SeatCellSnapshot> getSeatCells(long currentTime) {
        return diningArea.getSeatCells(currentTime);
    }
}
