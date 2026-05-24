package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WhitelistedParam;

import org.junit.jupiter.api.Test;

/**
 * RFC-010C:T-10C-MUT-1 ~ T-10C-MUT-12。
 *
 * <p>守住 mutator 闭合 switch 不依赖 reflection、SERVICE_RANGE_SCALE 锚定到默认 baseline、
 * fromName 严格守门。</p>
 */
class WhitelistedParameterMutatorTest {

    private final WhitelistedParameterMutator mutator = new WhitelistedParameterMutator();

    private SimConfig fresh() {
        SimConfig c = new SimConfig();
        c.getBaseConfig().setWindowCount(4);
        c.getBaseConfig().setTakeawayWindowCount(1);
        c.getBaseConfig().setTotalSeats(40);
        return c;
    }

    // ---- T-10C-MUT-1 ----

    @Test
    void mut1_arrivalRate() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.ARRIVAL_RATE, 120.5);
        assertEquals(120.5, c.getArrivalRate(), 0.0);
    }

    // ---- T-10C-MUT-2 ----

    @Test
    void mut2_windowCountRound() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.WINDOW_COUNT, 4.7);
        assertEquals(5, c.getBaseConfig().getWindowCount(), "Math.round(4.7) == 5");
    }

    // ---- T-10C-MUT-3 ----

    @Test
    void mut3_takeawayWindowCountRound() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.TAKEAWAY_WINDOW_COUNT, 1.4);
        assertEquals(1, c.getBaseConfig().getTakeawayWindowCount(), "Math.round(1.4) == 1");
    }

    // ---- T-10C-MUT-4 ----

    @Test
    void mut4_totalSeatsRound() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.TOTAL_SEATS, 79.6);
        assertEquals(80, c.getBaseConfig().getTotalSeats(), "Math.round(79.6) == 80");
    }

    // ---- T-10C-MUT-5 ----

    @Test
    void mut5_serviceRangeScaleOne() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 1.0);
        List<Integer> range = c.getRandomBounds().getServiceRange();
        assertEquals(2, range.size());
        assertEquals(45, range.get(0).intValue(), "scale=1.0 → min=45 baseline");
        assertEquals(180, range.get(1).intValue(), "scale=1.0 → max=180 baseline");
    }

    // ---- T-10C-MUT-6 ----

    @Test
    void mut6_serviceRangeScaleUp() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 1.3);
        List<Integer> range = c.getRandomBounds().getServiceRange();
        // round(45*1.3) = round(58.5) = 59 (HALF_UP); round(180*1.3) = round(234.0) = 234
        assertEquals(59, range.get(0).intValue(), "scale=1.3 → min=59");
        assertEquals(234, range.get(1).intValue(), "scale=1.3 → max=234");
    }

    // ---- T-10C-MUT-7 ----

    @Test
    void mut7_serviceRangeScaleDown() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 0.7);
        List<Integer> range = c.getRandomBounds().getServiceRange();
        // 45*0.7 在 IEEE 754 下 = 31.4999999999... → Math.round → 31(非数学直觉的 32)
        // 180*0.7 = 125.9999999999... → Math.round → 126
        assertEquals(31, range.get(0).intValue(), "scale=0.7 → min=Math.round(45*0.7) (IEEE 754) = 31");
        assertEquals(126, range.get(1).intValue(), "scale=0.7 → max=Math.round(180*0.7) = 126");
    }

    @Test
    void mut7b_serviceRangeScaleAnchorsToBaselineNotPreviousValue() {
        // 链式 scale 必须不出现累积:第二次 apply(0.7) 应基于 [45,180],不是基于 [59,234]
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 1.3);
        mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 0.7);
        List<Integer> range = c.getRandomBounds().getServiceRange();
        // 与 mut7 同算法:45*0.7→31,180*0.7→126,而不是基于 [59,234] 链式 scale
        assertEquals(31, range.get(0).intValue(), "二次 scale 应锚定到 baseline=45,得 31(而非 59*0.7=41)");
        assertEquals(126, range.get(1).intValue(), "二次 scale 应锚定到 baseline=180,得 126(而非 234*0.7=164)");
    }

    // ---- T-10C-MUT-8 ----

    @Test
    void mut8_serviceRangeScaleNonPositive() {
        SimConfig c = fresh();
        IllegalArgumentException ex0 = assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, 0.0));
        assertTrue(ex0.getMessage().contains("must be > 0"));

        IllegalArgumentException exNeg = assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, -0.1));
        assertTrue(exNeg.getMessage().contains("must be > 0"));

        assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(c, WhitelistedParam.SERVICE_RANGE_SCALE, Double.POSITIVE_INFINITY));
    }

    // ---- T-10C-MUT-9 ----

    @Test
    void mut9_packProbability() {
        SimConfig c = fresh();
        mutator.apply(c, WhitelistedParam.PACK_PROBABILITY, 0.42);
        assertEquals(0.42, c.getPackProbability(), 0.0);
    }

    // ---- T-10C-MUT-10 ----

    @Test
    void mut10_fromNameCaseInsensitive() {
        assertEquals(WhitelistedParam.ARRIVAL_RATE, WhitelistedParam.fromName("ARRIVAL_RATE"));
        assertEquals(WhitelistedParam.ARRIVAL_RATE, WhitelistedParam.fromName("arrival_rate"));
        assertEquals(WhitelistedParam.SERVICE_RANGE_SCALE, WhitelistedParam.fromName("Service_Range_Scale"));
        assertEquals(WhitelistedParam.PACK_PROBABILITY, WhitelistedParam.fromName("PACK_PROBABILITY"));
    }

    // ---- T-10C-MUT-11 ----

    @Test
    void mut11_fromNameNonWhitelistedThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WhitelistedParam.fromName("queueLimit"));
        assertTrue(ex.getMessage().contains("parameter not whitelisted: queueLimit"),
                () -> "异常 message 必须含 'parameter not whitelisted: queueLimit',实际:" + ex.getMessage());
    }

    @Test
    void mut11b_fromNameDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> WhitelistedParam.fromName("duration"));
        assertThrows(IllegalArgumentException.class,
                () -> WhitelistedParam.fromName(""));
    }

    // ---- T-10C-MUT-12 ----

    @Test
    void mut12_fromNameNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> WhitelistedParam.fromName(null));
    }

    @Test
    void applyNullArgsThrow() {
        SimConfig c = fresh();
        assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(null, WhitelistedParam.ARRIVAL_RATE, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> mutator.apply(c, null, 1.0));
    }
}
