package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjtu.simulation.dto.SimConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SimulationTaskServiceLifecycleTest {

    @Test
    void purgeShouldRemoveCompletedTasksOlderThanRetention() {
        long[] now = {1_000_000_000L};
        long retention = 30L * 60L * 1000L;
        SimulationTaskService service = new SimulationTaskService(
                new SimulationRunService(),
                new SimulationReportRepository(new ObjectMapper()),
                new ObjectMapper(),
                () -> now[0],
                retention,
                200);

        SimulationTaskRecord stale = new SimulationTaskRecord("task-stale", "rep-stale", new SimConfig(), now[0]);
        stale.markRunning();
        stale.markFailed(new RuntimeException("forced failure for test"));
        // 把 completedAt 强制压回到很早,模拟过期
        injectCompletedAt(stale, now[0] - retention - 1_000L);
        service.registerForTest(stale);

        SimulationTaskRecord fresh = new SimulationTaskRecord("task-fresh", "rep-fresh", new SimConfig(), now[0]);
        fresh.markRunning();
        fresh.markCompleted(null);
        injectCompletedAt(fresh, now[0]);
        service.registerForTest(fresh);

        SimulationTaskRecord running = new SimulationTaskRecord("task-running", "rep-running", new SimConfig(), now[0]);
        running.markRunning();
        service.registerForTest(running);

        assertEquals(3, service.getTaskCount());
        int removed = service.purgeExpired();
        assertEquals(1, removed);
        assertEquals(2, service.getTaskCount());

        // running 任务保留
        assertNotNull(service.get("task-running").orElse(null));
        // fresh 任务保留(在 TTL 内)
        assertNotNull(service.get("task-fresh").orElse(null));
        // 过期任务被清理
        assertTrue(service.get("task-stale").isEmpty(), "stale task should be purged");
    }

    @Test
    void purgeShouldNotEvictRunningTasksEvenWhenOld() {
        long[] now = {1_000_000_000L};
        SimulationTaskService service = new SimulationTaskService(
                new SimulationRunService(),
                new SimulationReportRepository(new ObjectMapper()),
                new ObjectMapper(),
                () -> now[0],
                30L * 60L * 1000L,
                200);

        // 提交了很久但仍在运行的任务
        SimulationTaskRecord longRunning = new SimulationTaskRecord(
                "task-long", "rep-long", new SimConfig(), now[0] - 24L * 60 * 60 * 1000);
        longRunning.markRunning();
        service.registerForTest(longRunning);

        int removed = service.purgeExpired();
        assertEquals(0, removed, "running tasks must not be purged");
        assertNotNull(service.get("task-long").orElse(null));
    }

    private static void injectCompletedAt(SimulationTaskRecord record, long completedAt) {
        try {
            java.lang.reflect.Field field = SimulationTaskRecord.class.getDeclaredField("completedAtEpochMillis");
            field.setAccessible(true);
            field.setLong(record, completedAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to inject completedAt", e);
        }
    }
}
