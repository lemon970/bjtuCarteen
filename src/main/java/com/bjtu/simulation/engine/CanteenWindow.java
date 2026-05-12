package com.bjtu.simulation.engine;

public class CanteenWindow {
    private final int id;
    private final String name;
    private int queueSize = 0;

    public CanteenWindow(int id, String name) {
        this.id = id;
        this.name = name;
    }
    public String getName() {
        return name;
    }
    public void addToQueue() { this.queueSize++; }
    public void removeFromQueue() { if (queueSize > 0) this.queueSize--; }
    public int getQueueSize() { return queueSize; }
    public int getId() { return id; }
}