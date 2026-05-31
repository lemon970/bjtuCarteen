package com.bjtu.simulation.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.bjtu.simulation.dto.WindowAttractivenessConfig;

/**
 * RFC-009 §4.3 普通窗口角色分配器。
 *
 * <p><strong>关键约束</strong>:不消耗 {@link SimulationEngine} 主 {@code randomSampler}。
 * 必须使用从 {@code effectiveSeed} 派生的独立 {@link Random}:</p>
 *
 * <pre>
 * roleSeed   = effectiveSeed ^ ROLE_ASSIGNMENT_SALT
 * roleRandom = new Random(roleSeed)
 * </pre>
 *
 * <p>这样 PREFERENCE_AWARE 与 STATIC_SPLIT 在同 seed 下,与角色分配无关的
 * 后续随机流(到达、画像、座位等)保持一致。</p>
 *
 * <p>取整规则(RFC Rev 3 Q2):</p>
 * <ul>
 *   <li>{@code popularCount = clamp(round(ratio × normalWindowCount), 0, normalWindowCount)}</li>
 *   <li>{@code coldCount    = clamp(round(ratio × normalWindowCount), 0, normalWindowCount - popularCount)}</li>
 *   <li>剩余的普通窗口 = NORMAL。</li>
 * </ul>
 *
 * <p>角色随机分配到具体的普通窗口下标(Fisher-Yates 洗牌的轻量等价)。</p>
 */
final class WindowRoleAssigner {

    /** 黄金比 fractional 部分,常用做 hash salt。 */
    static final long ROLE_ASSIGNMENT_SALT = 0x9E3779B97F4A7C15L;

    private WindowRoleAssigner() {
    }

    /**
     * @param windowTypes        引擎构建的窗口类型列表("NORMAL" / "TAKEAWAY"),长度 = windowCount
     * @param attractiveness     已通过 Validator 的吸引力配置(非 null,所有 attractiveness > 0)
     * @param effectiveSeed      仿真主 seed,用于派生独立 roleRandom
     * @return                   长度与 windowTypes 相同的角色列表;打包窗口固定为 TAKEAWAY
     */
    static List<WindowRole> assign(List<String> windowTypes,
                                   WindowAttractivenessConfig attractiveness,
                                   long effectiveSeed) {
        if (windowTypes == null || windowTypes.isEmpty()) {
            return Collections.emptyList();
        }
        int total = windowTypes.size();
        WindowRole[] roles = new WindowRole[total];
        List<Integer> normalIndices = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if ("TAKEAWAY".equalsIgnoreCase(windowTypes.get(i))) {
                roles[i] = WindowRole.TAKEAWAY;
            } else {
                normalIndices.add(i);
            }
        }
        int normalCount = normalIndices.size();
        if (normalCount == 0) {
            return List.of(roles);
        }

        int popularCount = clamp(
                (int) Math.round(attractiveness.getPopularWindowRatio() * normalCount),
                0, normalCount);
        int coldCount = clamp(
                (int) Math.round(attractiveness.getColdWindowRatio() * normalCount),
                0, normalCount - popularCount);

        // 用独立 Random 洗牌普通窗口下标,前 popularCount 个标 POPULAR,
        // 紧接着 coldCount 个标 COLD,其余 NORMAL。
        Random roleRandom = new Random(effectiveSeed ^ ROLE_ASSIGNMENT_SALT);
        Collections.shuffle(normalIndices, roleRandom);

        for (int i = 0; i < normalCount; i++) {
            int windowId = normalIndices.get(i);
            if (i < popularCount) {
                roles[windowId] = WindowRole.POPULAR;
            } else if (i < popularCount + coldCount) {
                roles[windowId] = WindowRole.COLD;
            } else {
                roles[windowId] = WindowRole.NORMAL;
            }
        }
        return List.of(roles);
    }

    /**
     * RFC-009 §4.4:weighted sampling 池的权重数组。
     * 普通窗口按角色对应 attractiveness;打包窗口用 {@code normalAttractiveness} 中性权重。
     */
    static double[] buildWeights(List<WindowRole> roles, WindowAttractivenessConfig attractiveness) {
        double popular = attractiveness.getPopularAttractiveness();
        double normal = attractiveness.getNormalAttractiveness();
        double cold = attractiveness.getColdAttractiveness();
        double[] weights = new double[roles.size()];
        for (int i = 0; i < roles.size(); i++) {
            switch (roles.get(i)) {
                case POPULAR -> weights[i] = popular;
                case NORMAL -> weights[i] = normal;
                case COLD -> weights[i] = cold;
                case TAKEAWAY -> weights[i] = normal;
            }
        }
        return weights;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
