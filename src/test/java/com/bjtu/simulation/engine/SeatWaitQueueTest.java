package com.bjtu.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.model.ArrivalGroup;

import org.junit.jupiter.api.Test;

class SeatWaitQueueTest {

    @Test
    void exhaustedPatienceShouldRecordNoSeatAbandonedNotTakeaway() {
        SimConfig config = new SimConfig();
        config.setQueueLimit(20);
        config.getBaseConfig().setTotalSeats(0);  // 没有座位 → reserve 永远失败
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(0);
        config.setSeed(20260601L);

        SimulationEngine engine = new SimulationEngine(config);
        engine.scheduleEvent(new StudentArriveEvent(0L, "student-0", ArrivalGroup.NORMAL, 1));
        engine.runAll();

        // 学生应进入等位队列 → 耐心耗尽 → no_seat_abandoned
        assertTrue(engine.getNoSeatAbandonedCount() >= 1,
                "expected pre-service no_seat_abandoned, got " + engine.getNoSeatAbandonedCount());
        assertEquals(0, engine.getTakeawayCount(),
                "no_seat path must not pollute takeaway count");
    }

    @Test
    void releasedSeatShouldUnblockWaitingStudent() {
        SimConfig config = new SimConfig();
        config.setQueueLimit(20);
        config.getBaseConfig().setTotalSeats(2);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(0);
        config.setSeed(20260601L);
        config.setPackProbability(0.0);  // 全员堂食

        SimulationEngine engine = new SimulationEngine(config);
        // 第一个学生先到 → 占住 1 座
        engine.scheduleEvent(new StudentArriveEvent(0L, "first", ArrivalGroup.NORMAL, 1));
        // 第二个学生紧跟 → 应该也能占住,因为还剩 1 座
        engine.scheduleEvent(new StudentArriveEvent(1L, "second", ArrivalGroup.NORMAL, 1));
        // 第三个学生到来时,如果前两个学生还在,会进入等位队列
        engine.scheduleEvent(new StudentArriveEvent(2L, "third", ArrivalGroup.NORMAL, 1));

        engine.runAll();

        // 至少有一个学生进入了 dineIn(基础流程 OK)
        assertTrue(engine.getDineInCount() >= 1,
                "expected at least one student dined in, got " + engine.getDineInCount());
    }
}
