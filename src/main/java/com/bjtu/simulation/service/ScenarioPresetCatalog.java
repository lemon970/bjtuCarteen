package com.bjtu.simulation.service;

import java.util.List;
import java.util.Optional;

import com.bjtu.simulation.dto.ScenarioExpectedMetrics;
import com.bjtu.simulation.dto.ScenarioPreset;
import com.bjtu.simulation.dto.SimConfig;

import org.springframework.stereotype.Service;

@Service
public class ScenarioPresetCatalog {

    public List<ScenarioPreset> list() {
        return List.of(
                preset("baseline_offpeak", "平峰基线", "验证系统在正常负载下的等待、座位和窗口状态。", "基线模型",
                        config("平峰基线", 2.0, 120, 300, 1000, 6, 1, 24, 0.10, 1.18,
                                85, 45, 170, 1500, 900, 2400, false, 1.0, 0.05, 0.40, "sunny", 1.0, 20260601L),
                        expected(240, "7%-13%", "0-6 分钟", "20%-45%")),
                preset("lunch_peak_pressure", "午高峰压力测试", "验证 600 人级高峰到达、排队和座位压力。", "高峰模型",
                        config("午高峰压力测试", 2.0, 300, 250, 1000, 8, 1, 40, 0.13, 1.2,
                                90, 45, 180, 1500, 900, 2400, true, 1.0, 0.05, 0.42, "sunny", 1.0, 20260512L),
                        expected(600, "10%-16%", "4-12 分钟", "40%-70%")),
                preset("seat_shortage", "座位紧张模型", "验证座位不足时找座、打包和长等待风险。", "座位压力",
                        config("座位紧张模型", 2.0, 260, 160, 1000, 8, 1, 36, 0.13, 1.2,
                                90, 45, 180, 1800, 1100, 2700, true, 1.0, 0.05, 0.45, "sunny", 1.0, 20260602L),
                        expected(520, "10%-16%", "6-15 分钟", "65%-90%")),
                preset("takeaway_intervention", "打包窗口干预", "验证增加打包窗口后是否缓解队列且不异常抬高打包率。", "干预模型",
                        config("打包窗口干预", 2.0, 300, 250, 1000, 9, 2, 40, 0.13, 1.25,
                                90, 45, 180, 1500, 900, 2400, true, 1.0, 0.05, 0.45, "sunny", 1.0, 20260603L),
                        expected(600, "10%-16%", "3-10 分钟", "40%-70%")),
                preset("rain_emergency", "雨天应急预案", "验证雨天偏好变化、窗口压力和座位压力联动。", "应急模型",
                        config("雨天应急预案", 1.5, 320, 220, 1000, 9, 2, 45, 0.20, 1.25,
                                95, 55, 190, 1500, 900, 2400, true, 1.25, 0.10, 0.50, "rainy", 1.25, 20260604L),
                        expected(480, "22%-28%", "5-14 分钟", "45%-75%")),
                preset("group_high_concentration", "高成组浓度模型", "验证拼桌、拆组、同桌入座率在高成组比例下的表现。", "群体模型",
                        groupConfig("高成组浓度模型", 1.5, 240, 180, 800, 7, 1, 32, 0.15, 1.2,
                                90, 50, 180, 1500, 900, 2400, true, 1.0, 0.05, 0.42, "sunny", 1.0, 20260605L,
                                40, 3, 5, 0, 0.85, true, 0.65, 4),
                        expected(360, "12%-18%", "3-9 分钟", "55%-85%")));
    }

    public Optional<ScenarioPreset> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return list().stream().filter(preset -> preset.id().equals(id)).findFirst();
    }

    private ScenarioPreset preset(String id,
                                  String name,
                                  String purpose,
                                  String category,
                                  SimConfig config,
                                  ScenarioExpectedMetrics expectedMetrics) {
        return new ScenarioPreset(id, name, purpose, category, config, expectedMetrics);
    }

    private ScenarioExpectedMetrics expected(int arrivals, String takeaway, String wait, String seat) {
        return new ScenarioExpectedMetrics(arrivals, takeaway, wait, seat);
    }

    private SimConfig config(String name,
                             double duration,
                             double arrivalRate,
                             int totalSeats,
                             int totalStudents,
                             int windowCount,
                             int takeawayWindowCount,
                             int queueLimit,
                             double packProbability,
                             double takeawayMultiplier,
                             int serviceMean,
                             int serviceMin,
                             int serviceMax,
                             int diningMean,
                             int diningMin,
                             int diningMax,
                             boolean peakEnabled,
                             double peakScale,
                             double preferenceMin,
                             double preferenceMax,
                             String weather,
                             double weatherFactor,
                             long seed) {
        SimConfig config = new SimConfig();
        config.setSimulationName(name);
        config.setDuration(duration);
        config.setArrivalRate(arrivalRate);
        config.setQueueLimit(queueLimit);
        config.setPackProbability(packProbability);
        config.setSeed(seed);
        config.setGroupArrivalProb(0.08);
        config.setPartySize(3);
        config.setWalkTimeMean(8.0);
        config.setCongestionPenalty(0.35);

        config.getBaseConfig().setTotalSeats(totalSeats);
        config.getBaseConfig().setTotalStudents(totalStudents);
        config.getBaseConfig().setWindowCount(windowCount);
        config.getBaseConfig().setTakeawayWindowCount(takeawayWindowCount);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(takeawayMultiplier);

        config.getWeatherConfig().setCurrentWeather(weather);
        config.getWeatherConfig().setWeatherImpactFactor(weatherFactor);

        config.getRandomBounds().setArrivalInterval(0);
        config.getRandomBounds().setServiceRange(List.of(serviceMin, serviceMax));
        config.getRandomBounds().setDiningRange(List.of(diningMin, diningMax));
        config.getRandomBounds().setPreferenceRange(List.of(preferenceMin, preferenceMax));

        config.setArrivalDist(distribution("POISSON", arrivalRate, 0, 0, 0, 0));
        config.setNormalServiceDist(distribution("NORMAL", 0, serviceMean, Math.max(1, (serviceMax - serviceMin) / 6.0), serviceMin, serviceMax));
        config.setWindowServiceDist(distribution("NORMAL", 0, serviceMean, Math.max(1, (serviceMax - serviceMin) / 6.0), serviceMin, serviceMax));
        config.setDiningTimeDist(distribution("NORMAL", 0, diningMean, Math.max(1, (diningMax - diningMin) / 6.0), diningMin, diningMax));

        config.getPeakConfig().setClassPeakEnabled(peakEnabled);
        config.getPeakConfig().setClassPeakWindows(peakEnabled
                ? List.of(
                        new SimConfig.PeakConfig.PeakWindow(12, 32, 2.6 * peakScale),
                        new SimConfig.PeakConfig.PeakWindow(64, 86, 1.8 * peakScale))
                : List.of());
        return config;
    }

    private SimConfig groupConfig(String name,
                                  double duration,
                                  double arrivalRate,
                                  int totalSeats,
                                  int totalStudents,
                                  int windowCount,
                                  int takeawayWindowCount,
                                  int queueLimit,
                                  double packProbability,
                                  double takeawayMultiplier,
                                  int serviceMean,
                                  int serviceMin,
                                  int serviceMax,
                                  int diningMean,
                                  int diningMin,
                                  int diningMax,
                                  boolean peakEnabled,
                                  double peakScale,
                                  double preferenceMin,
                                  double preferenceMax,
                                  String weather,
                                  double weatherFactor,
                                  long seed,
                                  int groupCount,
                                  int groupSizeMin,
                                  int groupSizeMax,
                                  int arrivalSpreadSeconds,
                                  double behaviorCorrelation,
                                  boolean preferAdjacent,
                                  double groupArrivalProb,
                                  int partySize) {
        SimConfig config = config(name, duration, arrivalRate, totalSeats, totalStudents,
                windowCount, takeawayWindowCount, queueLimit, packProbability, takeawayMultiplier,
                serviceMean, serviceMin, serviceMax, diningMean, diningMin, diningMax,
                peakEnabled, peakScale, preferenceMin, preferenceMax, weather, weatherFactor, seed);
        config.setGroupArrivalProb(groupArrivalProb);
        config.setPartySize(partySize);
        SimConfig.GroupConfig group = config.getGroupConfig();
        group.setEnabled(true);
        group.setGroupCount(groupCount);
        group.setSizeMin(groupSizeMin);
        group.setSizeMax(groupSizeMax);
        group.setArrivalSpreadSeconds(arrivalSpreadSeconds);
        group.setBehaviorCorrelation(behaviorCorrelation);
        group.setPreferAdjacentSeats(preferAdjacent);
        return config;
    }

    private SimConfig.DistributionSpec distribution(String type,
                                                   double lambda,
                                                   double mean,
                                                   double std,
                                                   long min,
                                                   long max) {
        SimConfig.DistributionSpec spec = new SimConfig.DistributionSpec();
        spec.setType(type);
        spec.setLambda(lambda);
        spec.setMean(mean);
        spec.setStd(std);
        spec.setMin(min);
        spec.setMax(max);
        return spec;
    }
}
