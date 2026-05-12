package com.bjtu.simulation.model;

public class Student {
    public enum PackPreferenceLevel {
        DINE_IN_BIASED,
        BALANCED,
        TAKEAWAY_BIASED
    }

    public enum PatienceLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum SeatToleranceLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    private final String id;
    private final double packPreference;
    private final int patienceLimit;
    private final int windowPreference;
    private final int seatSearchPatience;
    private final ArrivalGroup arrivalGroup;
    private final PackPreferenceLevel packPreferenceLevel;
    private final PatienceLevel patienceLevel;
    private final SeatToleranceLevel seatToleranceLevel;
    private final int partySize;
    private StudentState state;
    private DiningArea.SeatAllocation seatAllocation;

    public Student(String id,
                   double packPreference,
                   int patienceLimit,
                   int windowPreference,
                   int seatSearchPatience,
                   ArrivalGroup arrivalGroup,
                   PackPreferenceLevel packPreferenceLevel,
                   PatienceLevel patienceLevel,
                   SeatToleranceLevel seatToleranceLevel) {
        this(id,
                packPreference,
                patienceLimit,
                windowPreference,
                seatSearchPatience,
                arrivalGroup,
                packPreferenceLevel,
                patienceLevel,
                seatToleranceLevel,
                1);
    }

    public Student(String id,
                   double packPreference,
                   int patienceLimit,
                   int windowPreference,
                   int seatSearchPatience,
                   ArrivalGroup arrivalGroup,
                   PackPreferenceLevel packPreferenceLevel,
                   PatienceLevel patienceLevel,
                   SeatToleranceLevel seatToleranceLevel,
                   int partySize) {
        this.id = id;
        this.packPreference = packPreference;
        this.patienceLimit = patienceLimit;
        this.windowPreference = windowPreference;
        this.seatSearchPatience = seatSearchPatience;
        this.arrivalGroup = arrivalGroup;
        this.packPreferenceLevel = packPreferenceLevel == null ? PackPreferenceLevel.BALANCED : packPreferenceLevel;
        this.patienceLevel = patienceLevel == null ? PatienceLevel.MEDIUM : patienceLevel;
        this.seatToleranceLevel = seatToleranceLevel == null ? SeatToleranceLevel.MEDIUM : seatToleranceLevel;
        this.partySize = Math.max(1, partySize);
        this.state = StudentState.ARRIVED;
        this.seatAllocation = null;
    }

    public String getId() {
        return id;
    }

    public double getPackPreference() {
        return packPreference;
    }

    public int getPatienceLimit() {
        return patienceLimit;
    }

    public int getWindowPreference() {
        return windowPreference;
    }

    public int getSeatSearchPatience() {
        return seatSearchPatience;
    }

    public ArrivalGroup getArrivalGroup() {
        return arrivalGroup;
    }

    public PackPreferenceLevel getPackPreferenceLevel() {
        return packPreferenceLevel;
    }

    public PatienceLevel getPatienceLevel() {
        return patienceLevel;
    }

    public SeatToleranceLevel getSeatToleranceLevel() {
        return seatToleranceLevel;
    }

    public int getPartySize() {
        return partySize;
    }

    public StudentState getState() {
        return state;
    }

    public void setState(StudentState state) {
        this.state = state;
    }

    public DiningArea.SeatAllocation getSeatAllocation() {
        return seatAllocation;
    }

    public void setSeatAllocation(DiningArea.SeatAllocation seatAllocation) {
        this.seatAllocation = seatAllocation;
    }
}
