package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bjtu.simulation.dto.QueueChoiceModel;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WindowAttractivenessConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SimulationConfigNormalizer {

    private static final Logger log = LoggerFactory.getLogger(SimulationConfigNormalizer.class);

    private static final double MAX_DURATION_HOURS = 16.0;

    // RFC-009 §8.1 警告通道:PR-9B 阶段以 ThreadLocal 暴露给同线程内的调用方,便于测试断言。
    // PR-9D 引入 window_choice_metrics / precheck.warnings 时,此通道由报告层接管。
    private static final ThreadLocal<List<String>> LAST_WARNINGS = ThreadLocal.withInitial(ArrayList::new);

    public SimConfig normalize(SimConfig raw) {
        SimConfig config = raw == null ? new SimConfig() : raw;

        LAST_WARNINGS.set(new ArrayList<>());

        if (config.getBaseConfig() == null) {
            config.setBaseConfig(new SimConfig.BaseConfig());
        }
        if (config.getWeatherConfig() == null) {
            config.setWeatherConfig(new SimConfig.WeatherConfig());
        }
        if (config.getRandomBounds() == null) {
            config.setRandomBounds(new SimConfig.RandomBounds());
        }
        if (config.getPeakConfig() == null) {
            config.setPeakConfig(new SimConfig.PeakConfig());
        }
        if (config.getGroupConfig() == null) {
            config.setGroupConfig(new SimConfig.GroupConfig());
        }
        if (config.getArrivalDist() == null) {
            config.setArrivalDist(SimConfig.DistributionSpec.poisson());
        }
        if (config.getWindowServiceDist() == null) {
            config.setWindowServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (config.getNormalServiceDist() == null) {
            config.setNormalServiceDist(SimConfig.DistributionSpec.exponential());
        }
        if (config.getDiningTimeDist() == null) {
            config.setDiningTimeDist(SimConfig.DistributionSpec.uniform());
        }

        validate(config);
        normalizeMutableDefaults(config);
        return config;
    }

    private void validate(SimConfig config) {
        if (Double.isNaN(config.getDuration()) || Double.isInfinite(config.getDuration()) || config.getDuration() <= 0) {
            throw new IllegalArgumentException("duration must be > 0");
        }
        if (config.getDuration() > MAX_DURATION_HOURS) {
            throw new IllegalArgumentException("duration must be <= " + MAX_DURATION_HOURS + " hours to keep timeline at minute granularity");
        }
        if (Double.isNaN(config.getArrivalRate()) || Double.isInfinite(config.getArrivalRate()) || config.getArrivalRate() < 0) {
            throw new IllegalArgumentException("arrivalRate must be >= 0");
        }
        if (config.getBaseConfig().getWindowCount() < 1) {
            throw new IllegalArgumentException("windowCount must be >= 1");
        }
        if (config.getBaseConfig().getTakeawayWindowCount() < 0) {
            throw new IllegalArgumentException("takeawayWindowCount must be >= 0");
        }
        if (config.getBaseConfig().getTakeawayWindowCount() > config.getBaseConfig().getWindowCount()) {
            throw new IllegalArgumentException("takeawayWindowCount must be <= windowCount");
        }
        double takeawayServiceTimeMultiplier = config.getBaseConfig().getTakeawayServiceTimeMultiplier();
        if (Double.isNaN(takeawayServiceTimeMultiplier)
                || Double.isInfinite(takeawayServiceTimeMultiplier)
                || takeawayServiceTimeMultiplier < 1.0) {
            throw new IllegalArgumentException("takeawayServiceTimeMultiplier must be >= 1");
        }
        if (config.getBaseConfig().getTotalSeats() < 0) {
            throw new IllegalArgumentException("totalSeats must be >= 0");
        }
        if (config.getBaseConfig().getTotalStudents() < 0) {
            throw new IllegalArgumentException("totalStudents must be >= 0");
        }
        if (config.getQueueLimit() < 0) {
            throw new IllegalArgumentException("queueLimit must be >= 0");
        }
        if (config.getPackProbability() < 0 || config.getPackProbability() > 1) {
            throw new IllegalArgumentException("packProbability must be in [0, 1]");
        }
        if (config.getGroupArrivalProb() < 0 || config.getGroupArrivalProb() > 1) {
            throw new IllegalArgumentException("groupArrivalProb must be in [0, 1]");
        }
        if (config.getPartySize() < 1) {
            throw new IllegalArgumentException("partySize must be >= 1");
        }
        validateGroupConfig(config.getGroupConfig());
        if (Double.isNaN(config.getWalkTimeMean()) || Double.isInfinite(config.getWalkTimeMean()) || config.getWalkTimeMean() < 0) {
            throw new IllegalArgumentException("walkTimeMean must be >= 0");
        }
        if (Double.isNaN(config.getCongestionPenalty()) || Double.isInfinite(config.getCongestionPenalty()) || config.getCongestionPenalty() < 0) {
            throw new IllegalArgumentException("congestionPenalty must be >= 0");
        }
        if (config.getBaseConfig().getNumFourSeatTables() < 0) {
            throw new IllegalArgumentException("numFourSeatTables must be >= 0");
        }
        if (config.getBaseConfig().getNumTwoSeatTables() < 0) {
            throw new IllegalArgumentException("numTwoSeatTables must be >= 0");
        }
        if (config.getBaseConfig().getLargeTableRatio() < 0 || config.getBaseConfig().getLargeTableRatio() > 1) {
            throw new IllegalArgumentException("largeTableRatio must be in [0, 1]");
        }
        validateQueueChoiceModel(config.getBaseConfig());
    }

    /**
     * RFC-009 §8.1 配置校验规则。
     *
     * <ul>
     *   <li>{@code popular_window_ratio + cold_window_ratio <= 1.0}</li>
     *   <li>{@code popular_attractiveness >= normal_attractiveness >= cold_attractiveness}</li>
     *   <li>所有 attractiveness 必须 > 0</li>
     *   <li>{@code queue_choice_model = PREFERENCE_AWARE} 时缺失 window_attractiveness 自动补默认值并 warning</li>
     *   <li>{@code popular + cold = 1.0}(无 NORMAL 窗口)合法但 warning {@code no_normal_windows}</li>
     * </ul>
     */
    private void validateQueueChoiceModel(SimConfig.BaseConfig baseConfig) {
        QueueChoiceModel model = baseConfig.getQueueChoiceModel();
        if (model == null) {
            baseConfig.setQueueChoiceModel(QueueChoiceModel.STATIC_SPLIT);
            model = QueueChoiceModel.STATIC_SPLIT;
        }
        WindowAttractivenessConfig attr = baseConfig.getWindowAttractiveness();
        if (model == QueueChoiceModel.STATIC_SPLIT) {
            // STATIC_SPLIT 下不强制 attractiveness 存在;若用户传入了非默认值仍做基础校验,
            // 防止后续切到 PREFERENCE_AWARE 时才发现配置非法。
            if (attr != null) {
                validateAttractivenessFields(attr);
            }
            return;
        }

        if (attr == null) {
            attr = new WindowAttractivenessConfig();
            baseConfig.setWindowAttractiveness(attr);
            addWarning("window_attractiveness_missing_filled_default");
            log.warn("queueChoiceModel={} but windowAttractiveness missing; filled with defaults", model);
        }

        validateAttractivenessFields(attr);

        double sum = attr.getPopularWindowRatio() + attr.getColdWindowRatio();
        if (sum > 1.0 + 1e-9) {
            throw new IllegalArgumentException(
                    "popularWindowRatio + coldWindowRatio must be <= 1.0 (got " + sum + ")");
        }
        if (Math.abs(sum - 1.0) <= 1e-9) {
            addWarning("no_normal_windows");
        }
    }

    private void validateAttractivenessFields(WindowAttractivenessConfig attr) {
        if (attr.getPopularWindowRatio() < 0.0 || attr.getPopularWindowRatio() > 1.0) {
            throw new IllegalArgumentException("popularWindowRatio must be in [0, 1]");
        }
        if (attr.getColdWindowRatio() < 0.0 || attr.getColdWindowRatio() > 1.0) {
            throw new IllegalArgumentException("coldWindowRatio must be in [0, 1]");
        }
        if (!(attr.getPopularAttractiveness() > 0.0)
                || !(attr.getNormalAttractiveness() > 0.0)
                || !(attr.getColdAttractiveness() > 0.0)) {
            throw new IllegalArgumentException(
                    "attractiveness values must be > 0 (popular="
                            + attr.getPopularAttractiveness()
                            + ", normal=" + attr.getNormalAttractiveness()
                            + ", cold=" + attr.getColdAttractiveness() + ")");
        }
        if (attr.getPopularAttractiveness() < attr.getNormalAttractiveness()) {
            throw new IllegalArgumentException(
                    "popularAttractiveness (" + attr.getPopularAttractiveness()
                            + ") must be >= normalAttractiveness ("
                            + attr.getNormalAttractiveness() + ")");
        }
        if (attr.getNormalAttractiveness() < attr.getColdAttractiveness()) {
            throw new IllegalArgumentException(
                    "normalAttractiveness (" + attr.getNormalAttractiveness()
                            + ") must be >= coldAttractiveness ("
                            + attr.getColdAttractiveness() + ")");
        }
    }

    private static void addWarning(String code) {
        LAST_WARNINGS.get().add(code);
    }

    /** RFC-009 PR-9B:暴露最近一次 normalize 收集到的 warning 编码,供测试与后续报告层使用。 */
    public List<String> drainLastWarnings() {
        List<String> snapshot = new ArrayList<>(LAST_WARNINGS.get());
        // remove() 避免在线程池中长期占用 ThreadLocal 内存(下次 normalize() 入口也会重建)
        LAST_WARNINGS.remove();
        return Collections.unmodifiableList(snapshot);
    }

    private void normalizeMutableDefaults(SimConfig config) {
        if (config.getWeatherConfig().getWeatherImpactFactor() < 0) {
            config.getWeatherConfig().setWeatherImpactFactor(0);
        }
        if (config.getPeakConfig().getClassPeakStartMinute() < 0) {
            config.getPeakConfig().setClassPeakStartMinute(0);
        }
        if (config.getPeakConfig().getClassPeakEndMinute() < config.getPeakConfig().getClassPeakStartMinute()) {
            config.getPeakConfig().setClassPeakEndMinute(config.getPeakConfig().getClassPeakStartMinute());
        }
        if (Double.isNaN(config.getPeakConfig().getClassPeakMultiplier())
                || Double.isInfinite(config.getPeakConfig().getClassPeakMultiplier())
                || config.getPeakConfig().getClassPeakMultiplier() < 1) {
            config.getPeakConfig().setClassPeakMultiplier(1.0);
        }
        config.getPeakConfig().setClassPeakWindows(normalizePeakWindows(config.getPeakConfig().getClassPeakWindows()));

        config.getRandomBounds().setArrivalInterval(Math.max(0, config.getRandomBounds().getArrivalInterval()));
        config.getRandomBounds().setServiceRange(normalizeIntRange(config.getRandomBounds().getServiceRange(), 45, 180));
        config.getRandomBounds().setDiningRange(normalizeIntRange(config.getRandomBounds().getDiningRange(), 900, 2400));
        normalizeDistributionSpec(config.getArrivalDist(), "POISSON");
        normalizeDistributionSpec(config.getWindowServiceDist(), "EXPONENTIAL");
        normalizeDistributionSpec(config.getNormalServiceDist(), "EXPONENTIAL");
        normalizeDistributionSpec(config.getDiningTimeDist(), "UNIFORM");
        // [重构] 到达率由 arrivalRate 统一定义，原因是前端旧 lambda 与到达率不同步会直接造成总人数偏差。
        config.getArrivalDist().setLambda(Math.max(0.0, config.getArrivalRate()));
        normalizeGroupConfig(config.getGroupConfig());
    }

    private void validateGroupConfig(SimConfig.GroupConfig groupConfig) {
        if (groupConfig == null) {
            return;
        }
        if (groupConfig.getGroupCount() < 0) {
            throw new IllegalArgumentException("groupCount must be >= 0");
        }
        if (groupConfig.getSizeMin() < 1 || groupConfig.getSizeMax() < 1) {
            throw new IllegalArgumentException("group size must be >= 1");
        }
        if (groupConfig.getArrivalSpreadSeconds() < 0) {
            throw new IllegalArgumentException("arrivalSpreadSeconds must be >= 0");
        }
        if (groupConfig.getBehaviorCorrelation() < 0 || groupConfig.getBehaviorCorrelation() > 1) {
            throw new IllegalArgumentException("behaviorCorrelation must be in [0, 1]");
        }
    }

    private void normalizeGroupConfig(SimConfig.GroupConfig groupConfig) {
        if (groupConfig == null) {
            return;
        }
        int min = Math.max(1, Math.min(groupConfig.getSizeMin(), groupConfig.getSizeMax()));
        int max = Math.max(min, Math.max(groupConfig.getSizeMin(), groupConfig.getSizeMax()));
        groupConfig.setSizeMin(min);
        groupConfig.setSizeMax(max);
        groupConfig.setGroupCount(Math.max(0, groupConfig.getGroupCount()));
        groupConfig.setArrivalSpreadSeconds(Math.max(0, groupConfig.getArrivalSpreadSeconds()));
        groupConfig.setBehaviorCorrelation(Math.max(0.0, Math.min(1.0, groupConfig.getBehaviorCorrelation())));
    }

    private void normalizeDistributionSpec(SimConfig.DistributionSpec spec, String defaultType) {
        if (spec == null) {
            return;
        }
        if (spec.getType() == null || spec.getType().isBlank()) {
            spec.setType(defaultType);
        } else {
            spec.setType(spec.getType().trim().toUpperCase());
        }
        if (Double.isNaN(spec.getLambda()) || Double.isInfinite(spec.getLambda()) || spec.getLambda() < 0) {
            spec.setLambda(0.0);
        }
        if (Double.isNaN(spec.getMean()) || Double.isInfinite(spec.getMean()) || spec.getMean() < 0) {
            spec.setMean(0.0);
        }
        if (Double.isNaN(spec.getStd()) || Double.isInfinite(spec.getStd()) || spec.getStd() < 0) {
            spec.setStd(0.0);
        }
        if (spec.getMin() < 0) {
            spec.setMin(0L);
        }
        if (spec.getMax() < 0) {
            spec.setMax(0L);
        }
        if (spec.getMax() > 0 && spec.getMax() < spec.getMin()) {
            long min = spec.getMin();
            spec.setMin(spec.getMax());
            spec.setMax(min);
        }
    }

    private List<SimConfig.PeakConfig.PeakWindow> normalizePeakWindows(List<SimConfig.PeakConfig.PeakWindow> source) {
        List<SimConfig.PeakConfig.PeakWindow> normalized = new ArrayList<>();
        if (source == null) {
            return normalized;
        }

        for (SimConfig.PeakConfig.PeakWindow peakWindow : source) {
            if (peakWindow == null) {
                continue;
            }
            int start = Math.max(0, peakWindow.getStartMinute());
            int end = Math.max(start, peakWindow.getEndMinute());
            double multiplier = peakWindow.getMultiplier();
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 1.0) {
                multiplier = 1.0;
            }
            normalized.add(new SimConfig.PeakConfig.PeakWindow(start, end, multiplier));
        }
        return normalized;
    }

    private List<Integer> normalizeIntRange(List<Integer> source, int defaultMin, int defaultMax) {
        int min = defaultMin;
        int max = defaultMax;

        if (source != null && source.size() >= 2) {
            int a = source.get(0) == null ? defaultMin : source.get(0);
            int b = source.get(1) == null ? defaultMax : source.get(1);
            min = Math.min(a, b);
            max = Math.max(a, b);
        }

        min = Math.max(1, min);
        max = Math.max(min + 1, max);

        List<Integer> normalized = new ArrayList<>();
        normalized.add(min);
        normalized.add(max);
        return normalized;
    }
}
