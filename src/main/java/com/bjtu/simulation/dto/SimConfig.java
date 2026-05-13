package com.bjtu.simulation.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class SimConfig {
    @JsonAlias("simulation_name")
    private String simulationName = "default-simulation";

    @JsonAlias({"duration_hours", "duration_hour"})
    @DecimalMin(value = "0.0", inclusive = false, message = "duration must be > 0")
    private double duration = 1.0;

    @JsonAlias({"arrival_rate", "arrival_rate_per_hour"})
    @DecimalMin(value = "0.0", message = "arrivalRate must be >= 0")
    private double arrivalRate = 60.0;

    @JsonAlias("queue_limit")
    @Min(value = 0, message = "queueLimit must be >= 0")
    private int queueLimit = 10;

    @JsonAlias("pack_probability")
    @DecimalMin(value = "0.0", message = "packProbability must be in [0, 1]")
    @DecimalMax(value = "1.0", message = "packProbability must be in [0, 1]")
    private double packProbability = 0.2;

    @JsonAlias("group_arrival_prob")
    @DecimalMin(value = "0.0", message = "groupArrivalProb must be in [0, 1]")
    @DecimalMax(value = "1.0", message = "groupArrivalProb must be in [0, 1]")
    private double groupArrivalProb = 0.0;

    @JsonAlias("party_size")
    @Min(value = 1, message = "partySize must be >= 1")
    private int partySize = 1;

    @JsonAlias("walk_time_mean")
    @DecimalMin(value = "0.0", message = "walkTimeMean must be >= 0")
    private double walkTimeMean = 0.0;

    @JsonAlias("congestion_penalty")
    @DecimalMin(value = "0.0", message = "congestionPenalty must be >= 0")
    private double congestionPenalty = 0.0;

    private Long seed;

    @Valid
    @NotNull
    @JsonAlias("arrival_dist")
    private DistributionSpec arrivalDist = DistributionSpec.poisson();

    @Valid
    @NotNull
    @JsonAlias("window_service_dist")
    private DistributionSpec windowServiceDist = DistributionSpec.exponential();

    @Valid
    @NotNull
    @JsonAlias("normal_service_dist")
    private DistributionSpec normalServiceDist = DistributionSpec.exponential();

    @Valid
    @NotNull
    @JsonAlias("dining_time_dist")
    private DistributionSpec diningTimeDist = DistributionSpec.uniform();

    @Valid
    @NotNull
    @JsonAlias("base_config")
    private BaseConfig baseConfig = new BaseConfig();

    @Valid
    @NotNull
    @JsonAlias("weather_config")
    private WeatherConfig weatherConfig = new WeatherConfig();

    @Valid
    @NotNull
    @JsonAlias("random_bounds")
    private RandomBounds randomBounds = new RandomBounds();

    @Valid
    @NotNull
    @JsonAlias("peak_config")
    private PeakConfig peakConfig = new PeakConfig();

    public static class BaseConfig {
        @JsonAlias("window_count")
        @Min(value = 1, message = "windowCount must be >= 1")
        private int windowCount = 5;

        @JsonAlias("takeaway_window_count")
        @Min(value = 0, message = "takeawayWindowCount must be >= 0")
        private int takeawayWindowCount = 0;

        @JsonAlias("takeaway_service_time_multiplier")
        @DecimalMin(value = "1.0", message = "takeawayServiceTimeMultiplier must be >= 1")
        private double takeawayServiceTimeMultiplier = 1.15;

        @JsonAlias("total_seats")
        @Min(value = 0, message = "totalSeats must be >= 0")
        private int totalSeats = 50;

        @JsonAlias("total_students")
        @Min(value = 0, message = "totalStudents must be >= 0")
        private int totalStudents = 0;

        @JsonAlias("num_four_seat_tables")
        @Min(value = 0, message = "numFourSeatTables must be >= 0")
        private int numFourSeatTables = 0;

        @JsonAlias("num_two_seat_tables")
        @Min(value = 0, message = "numTwoSeatTables must be >= 0")
        private int numTwoSeatTables = 0;

        @JsonAlias("large_table_ratio")
        @DecimalMin(value = "0.0", message = "largeTableRatio must be in [0, 1]")
        @DecimalMax(value = "1.0", message = "largeTableRatio must be in [0, 1]")
        private double largeTableRatio = 0.8;

        public int getWindowCount() {
            return windowCount;
        }

        public void setWindowCount(int windowCount) {
            this.windowCount = windowCount;
        }

        public int getTakeawayWindowCount() {
            return takeawayWindowCount;
        }

        public void setTakeawayWindowCount(int takeawayWindowCount) {
            this.takeawayWindowCount = takeawayWindowCount;
        }

        public double getTakeawayServiceTimeMultiplier() {
            return takeawayServiceTimeMultiplier;
        }

        public void setTakeawayServiceTimeMultiplier(double takeawayServiceTimeMultiplier) {
            this.takeawayServiceTimeMultiplier = takeawayServiceTimeMultiplier;
        }

        public int getTotalSeats() {
            return totalSeats;
        }

        public void setTotalSeats(int totalSeats) {
            this.totalSeats = totalSeats;
        }

        public int getTotalStudents() {
            return totalStudents;
        }

        public void setTotalStudents(int totalStudents) {
            this.totalStudents = totalStudents;
        }

        public int getNumFourSeatTables() {
            return numFourSeatTables;
        }

        public void setNumFourSeatTables(int numFourSeatTables) {
            this.numFourSeatTables = numFourSeatTables;
        }

        public int getNumTwoSeatTables() {
            return numTwoSeatTables;
        }

        public void setNumTwoSeatTables(int numTwoSeatTables) {
            this.numTwoSeatTables = numTwoSeatTables;
        }

        public double getLargeTableRatio() {
            return largeTableRatio;
        }

        public void setLargeTableRatio(double largeTableRatio) {
            this.largeTableRatio = largeTableRatio;
        }
    }

    public static class DistributionSpec {
        @JsonAlias("type")
        private String type = "UNIFORM";

        @JsonAlias({"lambda", "rate"})
        @DecimalMin(value = "0.0", message = "distribution lambda must be >= 0")
        private double lambda = 0.0;

        @JsonAlias({"mean", "mu"})
        @DecimalMin(value = "0.0", message = "distribution mean must be >= 0")
        private double mean = 0.0;

        @JsonAlias({"std", "sigma"})
        @DecimalMin(value = "0.0", message = "distribution std must be >= 0")
        private double std = 0.0;

        @JsonAlias("min")
        @Min(value = 0, message = "distribution min must be >= 0")
        private long min = 0L;

        @JsonAlias("max")
        @Min(value = 0, message = "distribution max must be >= 0")
        private long max = 0L;

        public static DistributionSpec poisson() {
            DistributionSpec spec = new DistributionSpec();
            spec.setType("POISSON");
            return spec;
        }

        public static DistributionSpec exponential() {
            DistributionSpec spec = new DistributionSpec();
            spec.setType("EXPONENTIAL");
            return spec;
        }

        public static DistributionSpec uniform() {
            DistributionSpec spec = new DistributionSpec();
            spec.setType("UNIFORM");
            return spec;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public double getLambda() {
            return lambda;
        }

        public void setLambda(double lambda) {
            this.lambda = lambda;
        }

        public double getMean() {
            return mean;
        }

        public void setMean(double mean) {
            this.mean = mean;
        }

        public double getStd() {
            return std;
        }

        public void setStd(double std) {
            this.std = std;
        }

        public long getMin() {
            return min;
        }

        public void setMin(long min) {
            this.min = min;
        }

        public long getMax() {
            return max;
        }

        public void setMax(long max) {
            this.max = max;
        }
    }

    public static class WeatherConfig {
        @JsonAlias("current_weather")
        private String currentWeather = "unknown";

        @JsonAlias("weather_impact_factor")
        @DecimalMin(value = "0.0", message = "weatherImpactFactor must be >= 0")
        private double weatherImpactFactor = 1.0;

        public String getCurrentWeather() {
            return currentWeather;
        }

        public void setCurrentWeather(String currentWeather) {
            this.currentWeather = currentWeather;
        }

        public double getWeatherImpactFactor() {
            return weatherImpactFactor;
        }

        public void setWeatherImpactFactor(double weatherImpactFactor) {
            this.weatherImpactFactor = weatherImpactFactor;
        }
    }

    public static class RandomBounds {
        @JsonAlias({"arrival_interval", "arrival_interval_seconds"})
        @Min(value = 0, message = "arrivalInterval must be >= 0")
        private int arrivalInterval = 0;

        @JsonAlias({"service_range", "service_range_seconds"})
        private List<Integer> serviceRange = new ArrayList<>(List.of(45, 180));

        @JsonAlias({"dining_range", "dining_range_seconds"})
        private List<Integer> diningRange = new ArrayList<>(List.of(900, 2400));

        @JsonAlias("preference_range")
        private List<Double> preferenceRange = new ArrayList<>(List.of(0.1, 0.3));

        public int getArrivalInterval() {
            return arrivalInterval;
        }

        public void setArrivalInterval(int arrivalInterval) {
            this.arrivalInterval = arrivalInterval;
        }

        public List<Integer> getServiceRange() {
            return serviceRange;
        }

        public void setServiceRange(List<Integer> serviceRange) {
            this.serviceRange = serviceRange;
        }

        public List<Integer> getDiningRange() {
            return diningRange;
        }

        public void setDiningRange(List<Integer> diningRange) {
            this.diningRange = diningRange;
        }

        public List<Double> getPreferenceRange() {
            return preferenceRange;
        }

        public void setPreferenceRange(List<Double> preferenceRange) {
            this.preferenceRange = preferenceRange;
        }
    }

    public static class PeakConfig {
        @JsonAlias({"class_peak_enabled", "class_peak_mode"})
        private boolean classPeakEnabled = false;

        @JsonAlias("class_peak_start_minute")
        @Min(value = 0, message = "classPeakStartMinute must be >= 0")
        private int classPeakStartMinute = 15;

        @JsonAlias("class_peak_end_minute")
        @Min(value = 0, message = "classPeakEndMinute must be >= 0")
        private int classPeakEndMinute = 25;

        @JsonAlias("class_peak_multiplier")
        @DecimalMin(value = "1.0", message = "classPeakMultiplier must be >= 1")
        private double classPeakMultiplier = 5.0;

        @Valid
        @JsonAlias({"class_peak_windows", "peak_windows"})
        private List<PeakWindow> classPeakWindows = new ArrayList<>();

        public static class PeakWindow {
            @JsonAlias({"start_minute", "class_peak_start_minute"})
            @Min(value = 0, message = "peak window startMinute must be >= 0")
            private int startMinute = 15;

            @JsonAlias({"end_minute", "class_peak_end_minute"})
            @Min(value = 0, message = "peak window endMinute must be >= 0")
            private int endMinute = 25;

            @JsonAlias({"multiplier", "class_peak_multiplier"})
            @DecimalMin(value = "1.0", message = "peak window multiplier must be >= 1")
            private double multiplier = 5.0;

            public PeakWindow() {
            }

            public PeakWindow(int startMinute, int endMinute, double multiplier) {
                this.startMinute = startMinute;
                this.endMinute = endMinute;
                this.multiplier = multiplier;
            }

            public int getStartMinute() {
                return startMinute;
            }

            public void setStartMinute(int startMinute) {
                this.startMinute = startMinute;
            }

            public int getEndMinute() {
                return endMinute;
            }

            public void setEndMinute(int endMinute) {
                this.endMinute = endMinute;
            }

            public double getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(double multiplier) {
                this.multiplier = multiplier;
            }
        }

        public boolean isClassPeakEnabled() {
            return classPeakEnabled;
        }

        public void setClassPeakEnabled(boolean classPeakEnabled) {
            this.classPeakEnabled = classPeakEnabled;
        }

        public int getClassPeakStartMinute() {
            return classPeakStartMinute;
        }

        public void setClassPeakStartMinute(int classPeakStartMinute) {
            this.classPeakStartMinute = classPeakStartMinute;
        }

        public int getClassPeakEndMinute() {
            return classPeakEndMinute;
        }

        public void setClassPeakEndMinute(int classPeakEndMinute) {
            this.classPeakEndMinute = classPeakEndMinute;
        }

        public double getClassPeakMultiplier() {
            return classPeakMultiplier;
        }

        public void setClassPeakMultiplier(double classPeakMultiplier) {
            this.classPeakMultiplier = classPeakMultiplier;
        }

        public List<PeakWindow> getClassPeakWindows() {
            return classPeakWindows;
        }

        public void setClassPeakWindows(List<PeakWindow> classPeakWindows) {
            this.classPeakWindows = classPeakWindows;
        }
    }

    public String getSimulationName() {
        return simulationName;
    }

    public void setSimulationName(String simulationName) {
        this.simulationName = simulationName;
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }

    public double getArrivalRate() {
        return arrivalRate;
    }

    public void setArrivalRate(double arrivalRate) {
        this.arrivalRate = arrivalRate;
    }

    public int getQueueLimit() {
        return queueLimit;
    }

    public void setQueueLimit(int queueLimit) {
        this.queueLimit = queueLimit;
    }

    public double getPackProbability() {
        return packProbability;
    }

    public void setPackProbability(double packProbability) {
        this.packProbability = packProbability;
    }

    public double getGroupArrivalProb() {
        return groupArrivalProb;
    }

    public void setGroupArrivalProb(double groupArrivalProb) {
        this.groupArrivalProb = groupArrivalProb;
    }

    public int getPartySize() {
        return partySize;
    }

    public void setPartySize(int partySize) {
        this.partySize = partySize;
    }

    public double getWalkTimeMean() {
        return walkTimeMean;
    }

    public void setWalkTimeMean(double walkTimeMean) {
        this.walkTimeMean = walkTimeMean;
    }

    public double getCongestionPenalty() {
        return congestionPenalty;
    }

    public void setCongestionPenalty(double congestionPenalty) {
        this.congestionPenalty = congestionPenalty;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public DistributionSpec getArrivalDist() {
        return arrivalDist;
    }

    public void setArrivalDist(DistributionSpec arrivalDist) {
        this.arrivalDist = arrivalDist;
    }

    public DistributionSpec getWindowServiceDist() {
        return windowServiceDist;
    }

    public void setWindowServiceDist(DistributionSpec windowServiceDist) {
        this.windowServiceDist = windowServiceDist;
    }

    public DistributionSpec getNormalServiceDist() {
        return normalServiceDist;
    }

    public void setNormalServiceDist(DistributionSpec normalServiceDist) {
        this.normalServiceDist = normalServiceDist;
    }

    public DistributionSpec getDiningTimeDist() {
        return diningTimeDist;
    }

    public void setDiningTimeDist(DistributionSpec diningTimeDist) {
        this.diningTimeDist = diningTimeDist;
    }

    public BaseConfig getBaseConfig() {
        return baseConfig;
    }

    public void setBaseConfig(BaseConfig baseConfig) {
        this.baseConfig = baseConfig;
    }

    public WeatherConfig getWeatherConfig() {
        return weatherConfig;
    }

    public void setWeatherConfig(WeatherConfig weatherConfig) {
        this.weatherConfig = weatherConfig;
    }

    public RandomBounds getRandomBounds() {
        return randomBounds;
    }

    public void setRandomBounds(RandomBounds randomBounds) {
        this.randomBounds = randomBounds;
    }

    public PeakConfig getPeakConfig() {
        return peakConfig;
    }

    public void setPeakConfig(PeakConfig peakConfig) {
        this.peakConfig = peakConfig;
    }

}
