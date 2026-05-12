package com.bjtu.simulation.engine;

import java.util.concurrent.atomic.AtomicLong;

public abstract class BaseEvent implements Comparable<BaseEvent> {
    public static final int DEFAULT_PRIORITY = 0;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long eventTime;
    private final int priority;
    private final long sequenceNumber;

    protected BaseEvent(long eventTime) {
        this(eventTime, DEFAULT_PRIORITY);
    }

    protected BaseEvent(long eventTime, int priority) {
        this.eventTime = eventTime;
        this.priority = priority;
        this.sequenceNumber = SEQUENCE.getAndIncrement();
    }

    public long getEventTime() {
        return eventTime;
    }

    public int getPriority() {
        return priority;
    }

    public abstract void process(SimulationEngine engine);

    @Override
    public int compareTo(BaseEvent other) {
        int timeOrder = Long.compare(this.eventTime, other.eventTime);
        if (timeOrder != 0) {
            return timeOrder;
        }
        int priorityOrder = Integer.compare(other.priority, this.priority);
        if (priorityOrder != 0) {
            return priorityOrder;
        }
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }
}
