package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.bjtu.simulation.model.ArrivalGroup;
import com.bjtu.simulation.model.Student;

import org.junit.jupiter.api.Test;

/**
 * 第十轮 A:验证 WindowSelectionPolicy 在 willTakeaway=true 时优先路由到打包窗口。
 *
 * 第七轮删掉 willTakeaway 硬路由后,该参数实际未被使用,导致雨天 32% intent
 * 打包学生(包括成组学生)全部被路由到普通窗口。第十轮把 wantsTakeaway 重新接通,
 * 使用 chooseBestWindow(takeawayOnly=true) 复用现有评分逻辑(队列 + 偏好 + 延迟 + 类型惩罚)。
 */
class WindowSelectionPolicyTakeawayIntentTest {

    private final WindowSelectionPolicy policy = new WindowSelectionPolicy();

    @Test
    void wantsTakeawayShouldRouteToTakeawayWindowWhenAvailable() {
        // 3 windows: 2 normal (queues 0, 0) + 1 takeaway (queue 2)
        CanteenState state = stateWithQueues(List.of(0, 0, 2));
        List<String> windowTypes = List.of("NORMAL", "NORMAL", "TAKEAWAY");
        List<Long> available = List.of(0L, 0L, 0L);
        Student student = dineInBiasedStudent("s1", 30);

        int chosen = policy.choose(student, state, available, windowTypes,
                /*currentTime=*/0L, /*queuePressure=*/0.0, /*seatPressure=*/0.0,
                /*takeawayWindowCount=*/1, /*willTakeaway=*/true);

        assertEquals(2, chosen,
                "wantsTakeaway=true 应优先选打包窗口(index 2),即使普通窗口空");
    }

    @Test
    void wantsTakeawayShouldFallbackWhenAllTakeawayWindowsExceedPatience() {
        // 1 normal (queue 1) + 1 takeaway (queue 50, exceeds patience)
        CanteenState state = stateWithQueues(List.of(1, 50));
        List<String> windowTypes = List.of("NORMAL", "TAKEAWAY");
        List<Long> available = List.of(0L, 0L);
        Student student = dineInBiasedStudent("s2", /*patienceLimit=*/20);

        int chosen = policy.choose(student, state, available, windowTypes,
                0L, 0.0, 0.0, 1, true);

        assertEquals(0, chosen, "打包窗口超耐心后,应 fallback 到普通窗口");
        assertNotEquals(-1, chosen, "fallback 不应返回 -1");
    }

    @Test
    void noTakeawayIntentShouldNotPreferTakeawayWindow() {
        // 1 normal (queue 0) + 1 takeaway (queue 0) — 两个都空
        CanteenState state = stateWithQueues(List.of(0, 0));
        List<String> windowTypes = List.of("NORMAL", "TAKEAWAY");
        List<Long> available = List.of(0L, 0L);
        Student student = dineInBiasedStudent("s3", 30);

        int chosen = policy.choose(student, state, available, windowTypes,
                0L, 0.0, 0.0, 1, /*willTakeaway=*/false);

        // DINE_IN_BIASED + willTakeaway=false → 走原 packPreferenceLevel 路径
        // windowTypePenalty: TAKEAWAY +1.50 / NORMAL -0.20 → 选 NORMAL
        assertEquals(0, chosen,
                "wantsTakeaway=false 的 dine-in 学生应走原路径,选普通窗口");
    }

    @Test
    void zeroTakeawayWindowCountShouldNotEnterTakeawayBranch() {
        // 全部都是普通窗口
        CanteenState state = stateWithQueues(List.of(0, 0));
        List<String> windowTypes = List.of("NORMAL", "NORMAL");
        List<Long> available = List.of(0L, 0L);
        Student student = dineInBiasedStudent("s4", 30);

        int chosen = policy.choose(student, state, available, windowTypes,
                0L, 0.0, 0.0, /*takeawayWindowCount=*/0, /*willTakeaway=*/true);

        // takeawayWindowCount=0,新增的 willTakeaway 分支跳过,走原路径
        assertTrue(chosen == 0 || chosen == 1, "应在两个普通窗口中选,got " + chosen);
    }

    private CanteenState stateWithQueues(List<Integer> queues) {
        CanteenState state = new CanteenState(queues.size(), /*totalSeats=*/100);
        for (int i = 0; i < queues.size(); i++) {
            int target = Math.max(0, queues.get(i));
            for (int j = 0; j < target; j++) {
                state.joinQueue(i);
            }
        }
        return state;
    }

    private Student dineInBiasedStudent(String id, int patienceLimit) {
        return new Student(
                id,
                /*packPreference=*/0.10,
                patienceLimit,
                /*windowPreference=*/0,
                /*seatSearchPatience=*/2,
                ArrivalGroup.NORMAL,
                Student.PackPreferenceLevel.DINE_IN_BIASED,
                Student.PatienceLevel.MEDIUM,
                Student.SeatToleranceLevel.MEDIUM,
                /*partySize=*/1,
                /*groupId=*/null,
                /*groupSize=*/1,
                /*groupMemberIndex=*/0,
                /*wantsTakeaway=*/false);
    }
}
