package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulationConfigNormalizerTest {

    private final SimulationConfigNormalizer normalizer = new SimulationConfigNormalizer();

    @Test
    void normalizeNullShouldReturnConfigWithDefaultSubObjects() {
        SimConfig config = normalizer.normalize(null);

        assertNotNull(config);
        assertNotNull(config.getBaseConfig());
        assertNotNull(config.getWeatherConfig());
        assertNotNull(config.getRandomBounds());
        assertNotNull(config.getPeakConfig());
        assertNotNull(config.getGroupConfig());
        assertNotNull(config.getArrivalDist());
        assertNotNull(config.getWindowServiceDist());
        assertNotNull(config.getNormalServiceDist());
        assertNotNull(config.getDiningTimeDist());
    }

    @Test
    void durationAboveSixteenHoursShouldRejectWithBoundaryMessage() {
        SimConfig config = freshConfig();
        config.setDuration(16.5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("<= 16.0"),
                () -> "expected boundary message, got: " + ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0})
    void invalidDurationShouldRejectWithPositivityMessage(double badDuration) {
        SimConfig config = freshConfig();
        config.setDuration(badDuration);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("duration must be > 0"),
                () -> "expected positivity message for " + badDuration + ", got: " + ex.getMessage());
    }

    @Test
    void takeawayWindowCountAboveWindowCountShouldReject() {
        SimConfig config = freshConfig();
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(3);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize(config));
        assertTrue(ex.getMessage().contains("<= windowCount"),
                () -> "expected '<= windowCount' message, got: " + ex.getMessage());
    }

    @Test
    void groupSizeMinGreaterThanMaxShouldSwapSilently() {
        SimConfig config = freshConfig();
        config.getGroupConfig().setSizeMin(5);
        config.getGroupConfig().setSizeMax(2);

        SimConfig normalized = assertDoesNotThrow(() -> normalizer.normalize(config));

        assertEquals(2, normalized.getGroupConfig().getSizeMin());
        assertEquals(5, normalized.getGroupConfig().getSizeMax());
    }

    @Test
    void distributionSpecAndPeakWindowShouldNormalizeDefensively() {
        SimConfig config = freshConfig();
        SimConfig.DistributionSpec spec = config.getArrivalDist();
        spec.setMin(100L);
        spec.setMax(40L);

        SimConfig.PeakConfig.PeakWindow window = new SimConfig.PeakConfig.PeakWindow(10, 20, 0.5);
        config.getPeakConfig().setClassPeakWindows(List.of(window));

        SimConfig normalized = normalizer.normalize(config);

        assertEquals(40L, normalized.getArrivalDist().getMin(), "min/max should swap when max < min");
        assertEquals(100L, normalized.getArrivalDist().getMax());
        assertEquals(1, normalized.getPeakConfig().getClassPeakWindows().size());
        assertEquals(1.0, normalized.getPeakConfig().getClassPeakWindows().get(0).getMultiplier(), 1e-9,
                "peak multiplier < 1 should be clamped to 1.0");
    }

    private SimConfig freshConfig() {
        SimConfig config = new SimConfig();
        config.setDuration(1.0);
        config.setArrivalRate(60);
        config.setQueueLimit(10);
        config.setPackProbability(0.2);
        config.setGroupArrivalProb(0.0);
        config.setPartySize(1);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(0);
        config.getBaseConfig().setTotalSeats(20);
        config.getBaseConfig().setTotalStudents(0);
        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        return config;
    }
}
