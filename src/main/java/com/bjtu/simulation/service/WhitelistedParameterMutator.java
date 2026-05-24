package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.List;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.WhitelistedParam;

import org.springframework.stereotype.Service;

/**
 * RFC-010C:把一个 {@code (parameter, value)} 对写入 {@link SimConfig} 的具名 setter。
 *
 * <p><strong>严禁 reflection</strong>:全部用闭合 {@code switch} 分发,enum 全覆盖编译期检查;
 * 任何不在 {@link WhitelistedParam} 中的参数名通过 {@link WhitelistedParam#fromName(String)}
 * 在入口就被拒绝,不会进入这个 mutator。扩展白名单必须经独立 RFC + 独立测试。</p>
 *
 * <p>{@link WhitelistedParam#SERVICE_RANGE_SCALE} 是合成参数:把
 * {@code RandomBounds.serviceRange} 锚定到默认 {@link SimConfig} 的 {@code [45, 180]} 基线后,
 * 用 scale 倍率乘后取整 / 截断。绝不在已 mutated 的 config 上链式 scale,避免重复扫描出现
 * 非线性累积偏差。</p>
 */
@Service
public class WhitelistedParameterMutator {

    /** 锚到默认 SimConfig 的 serviceRange,作为 SERVICE_RANGE_SCALE 的不动 baseline。 */
    private static final List<Integer> SERVICE_RANGE_BASELINE =
            List.copyOf(new SimConfig().getRandomBounds().getServiceRange());

    public void apply(SimConfig config, WhitelistedParam param, double value) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (param == null) {
            throw new IllegalArgumentException("param must not be null");
        }
        switch (param) {
            case ARRIVAL_RATE:
                config.setArrivalRate(value);
                break;
            case WINDOW_COUNT:
                config.getBaseConfig().setWindowCount((int) Math.round(value));
                break;
            case TAKEAWAY_WINDOW_COUNT:
                config.getBaseConfig().setTakeawayWindowCount((int) Math.round(value));
                break;
            case TOTAL_SEATS:
                config.getBaseConfig().setTotalSeats((int) Math.round(value));
                break;
            case SERVICE_RANGE_SCALE:
                applyServiceRangeScale(config, value);
                break;
            case PACK_PROBABILITY:
                config.setPackProbability(value);
                break;
            default:
                // enum 全覆盖,switch 命中是编译期保证;default 仅为新增枚举时的快速失败。
                throw new IllegalStateException("unhandled whitelisted param: " + param);
        }
    }

    private void applyServiceRangeScale(SimConfig config, double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("SERVICE_RANGE_SCALE must be > 0, got " + scale);
        }
        int newMin = Math.max(1, (int) Math.round(SERVICE_RANGE_BASELINE.get(0) * scale));
        int newMax = Math.max(newMin, (int) Math.round(SERVICE_RANGE_BASELINE.get(1) * scale));
        List<Integer> next = new ArrayList<>(2);
        next.add(newMin);
        next.add(newMax);
        config.getRandomBounds().setServiceRange(next);
    }
}
