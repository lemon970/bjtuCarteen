package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OptimizationTaskServiceTest {

    private static final ObjectMapper MAPPER = AppBeansConfig.createReportObjectMapper();
    private static final long DEFAULT_RETENTION = 30L * 60L * 1000L;
    private static final int DEFAULT_MAX_TASKS = 200;
    private static final long DEFAULT_LONG_THRESHOLD = 5L * 60L * 1000L;

    private SimulationConfigNormalizer normalizer;
    private FakeSimulationRunService fakeRunService;
    private OptimizationTaskService service;

    @BeforeEach
    void setUp() {
        normalizer = new SimulationConfigNormalizer();
        fakeRunService = new FakeSimulationRunService();
        service = new OptimizationTaskService(
                fakeRunService, normalizer, MAPPER,
                System::currentTimeMillis,
                DEFAULT_RETENTION, DEFAULT_MAX_TASKS, DEFAULT_LONG_THRESHOLD);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void submitReturnsImmediatelyWithBatchTaskId() throws Exception {
        OptimizationRequest req = makeRequest(2);
        long start = System.currentTimeMillis();
        OptimizationTaskRecord record = service.submit(req);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(record.getBatchTaskId());
        assertEquals(2, record.getTotal());
        assertTrue(elapsed < 500L, "submit should not block beyond 500ms but took " + elapsed);
        awaitTerminal(record, 5_000L);
    }

    @Test
    void statusTransitionsQueuedRunningCompleted() throws Exception {
        fakeRunService.setDelayMillis(80L);
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);

        // 不强制断言 QUEUED 必现(可能瞬间被 worker 拿走),但终态必须 COMPLETED 且经历 RUNNING。
        boolean sawRunning = false;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = record.getStatus();
            if (OptimizationTaskRecord.STATUS_RUNNING.equals(status)) {
                sawRunning = true;
            }
            if (record.isTerminal()) {
                break;
            }
            Thread.sleep(20L);
        }
        assertTrue(record.isTerminal(), "task should reach terminal within deadline");
        assertEquals(OptimizationTaskRecord.STATUS_COMPLETED, record.getStatus());
        assertTrue(sawRunning, "status should pass through RUNNING");
    }

    @Test
    void resultMatrixPreservesInputOrder() throws Exception {
        OptimizationRequest req = makeRequest(4);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        ObjectNode result = service.toResultSnapshot(record);
        assertEquals(4, result.path("results").size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, result.path("results").get(i).path("index").asInt());
        }
    }

    @Test
    void singleFailureDoesNotStopBatchAndMarksCompletedWithHasFailures() throws Exception {
        fakeRunService.failAtIndex(1, new IllegalStateException("forced-failure-1"));
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        assertEquals(OptimizationTaskRecord.STATUS_COMPLETED, record.getStatus());
        assertEquals(2, record.getCompletedCount());
        assertEquals(1, record.getFailedCount());
        assertTrue(record.hasFailures());

        ObjectNode result = service.toResultSnapshot(record);
        assertEquals(3, result.path("results").size());
        assertTrue(result.path("results").get(0).path("error_message").isNull());
        assertEquals("IllegalStateException: forced-failure-1",
                result.path("results").get(1).path("error_message").asText());        assertTrue(result.path("results").get(2).path("error_message").isNull());
        assertEquals("config[1]: IllegalStateException: forced-failure-1",
                result.path("first_failure_message").asText());
    }

    @Test
    void allFailuresMarkBatchAsFailed() throws Exception {
        fakeRunService.failAtIndex(0, new RuntimeException("zero"));
        fakeRunService.failAtIndex(1, new RuntimeException("one"));
        fakeRunService.failAtIndex(2, new RuntimeException("two"));

        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        assertEquals(OptimizationTaskRecord.STATUS_FAILED, record.getStatus());
        assertEquals(0, record.getCompletedCount());
        assertEquals(3, record.getFailedCount());
        assertTrue(record.isResultAvailable(), "result should be queryable on FAILED");

        ObjectNode result = service.toResultSnapshot(record);
        assertEquals(3, result.path("results").size());
        for (int i = 0; i < 3; i++) {
            assertFalse(result.path("results").get(i).path("error_message").isNull());
        }
    }

    @Test
    void firstFailureMessageRecordsLowestIndexOnly() throws Exception {
        fakeRunService.failAtIndex(0, new RuntimeException("first"));
        fakeRunService.failAtIndex(1, new RuntimeException("second"));
        fakeRunService.failAtIndex(2, new RuntimeException("third"));

        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        assertEquals("config[0]: RuntimeException: first", record.getFirstFailureMessage());
        assertEquals(1, record.getFirstFailureIndex());
    }

    @Test
    void percentCompleteCountsBothCompletedAndFailedWhenPartialFailure() throws Exception {
        // partial failure 终态:percent_complete 应为 (completed + failed) / total = 1.0,
        // 而不是 completed / total = 0.667。否则前端进度条永远停在 67%。
        fakeRunService.failAtIndex(1, new RuntimeException("middle-fail"));
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        assertEquals(2, record.getCompletedCount());
        assertEquals(1, record.getFailedCount());
        assertEquals(1.0, service.toStatusSnapshot(record).path("percent_complete").asDouble(), 1e-9);
    }

    @Test
    void percentCompleteReachesOneEvenWhenAllItemsFail() throws Exception {
        // all-failure 终态:percent_complete 应为 1.0,而不是 completed / total = 0。
        fakeRunService.failAtIndex(0, new RuntimeException("zero"));
        fakeRunService.failAtIndex(1, new RuntimeException("one"));
        fakeRunService.failAtIndex(2, new RuntimeException("two"));
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        assertEquals(0, record.getCompletedCount());
        assertEquals(3, record.getFailedCount());
        assertEquals(1.0, service.toStatusSnapshot(record).path("percent_complete").asDouble(), 1e-9);
    }

    @Test
    void percentCompleteReflectsCompletedPlusFailedDuringRunningMixed() {
        // running 中途场景:total=4, completed=1, failed=1 → 应该是 0.5。
        // 这里直接构造 record + register,不实际 submit,精准控制中间态。
        OptimizationTaskRecord record = new OptimizationTaskRecord(
                "running-mixed", "minimize avg_wait_time_minutes",
                List.of(new SimConfig(), new SimConfig(), new SimConfig(), new SimConfig()),
                System.currentTimeMillis());
        record.markRunning(System.currentTimeMillis());
        record.incrementCompleted();
        record.incrementFailed();
        service.registerForTest(record);

        ObjectNode snapshot = service.toStatusSnapshot(record);
        assertEquals(0.5, snapshot.path("percent_complete").asDouble(), 1e-9);
        assertEquals(1, snapshot.path("completed").asInt());
        assertEquals(1, snapshot.path("failed").asInt());
        assertEquals(4, snapshot.path("total").asInt());
    }

    @Test
    void percentCompleteIsZeroBeforeAnyItemFinishes() {
        OptimizationTaskRecord record = new OptimizationTaskRecord(
                "queued", "minimize avg_wait_time_minutes",
                List.of(new SimConfig(), new SimConfig()),
                System.currentTimeMillis());
        service.registerForTest(record);

        ObjectNode snapshot = service.toStatusSnapshot(record);
        assertEquals(0.0, snapshot.path("percent_complete").asDouble(), 1e-9);
    }

    @Test
    void percentCompleteIsZeroWhenTotalIsZero() {
        // total=0 是病理输入 (submit 已经 reject 它),但 toStatusSnapshot 不能除零。
        // 直接构造带 0 condigs 的 record(绕过 submit 校验),确保 snapshot 不抛。
        OptimizationTaskRecord record = new OptimizationTaskRecord(
                "empty", "minimize avg_wait_time_minutes",
                List.of(),
                System.currentTimeMillis());
        service.registerForTest(record);

        ObjectNode snapshot = service.toStatusSnapshot(record);
        assertEquals(0.0, snapshot.path("percent_complete").asDouble(), 1e-9);
    }

    @Test
    void submitConfigsEmptyThrowsIllegalArgument() {
        OptimizationRequest req = new OptimizationRequest();
        req.setConfigs(new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> service.submit(req));
    }

    @Test
    void submitConfigsNullThrowsIllegalArgument() {
        OptimizationRequest req = new OptimizationRequest();
        req.setConfigs(null);
        assertThrows(IllegalArgumentException.class, () -> service.submit(req));
    }

    @Test
    void submitNullRequestThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(null));
    }

    @Test
    void resultStatusBeforeCompletionIsNotAvailable() {
        fakeRunService.setDelayMillis(200L);
        OptimizationRequest req = makeRequest(2);
        OptimizationTaskRecord record = service.submit(req);

        assertFalse(record.isResultAvailable(), "RUNNING task must not expose result");
        ObjectNode status = service.toStatusSnapshot(record);
        assertFalse(status.path("result_available").asBoolean());
    }

    @Test
    void currentIndexAdvancesDuringSerialExecution() throws Exception {
        fakeRunService.setDelayMillis(60L);
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);

        int observedMax = 0;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && !record.isTerminal()) {
            observedMax = Math.max(observedMax, record.getCurrentIndex());
            Thread.sleep(15L);
        }
        awaitTerminal(record, 5_000L);
        assertTrue(observedMax >= 1, "currentIndex should advance during execution, observed=" + observedMax);
    }

    @Test
    void lastUpdatedAtRefreshesPerItem() throws Exception {
        fakeRunService.setDelayMillis(60L);
        OptimizationRequest req = makeRequest(3);
        OptimizationTaskRecord record = service.submit(req);

        long initialLastUpdated = record.getLastUpdatedAtEpochMillis();
        awaitTerminal(record, 5_000L);
        long finalLastUpdated = record.getLastUpdatedAtEpochMillis();
        assertTrue(finalLastUpdated >= initialLastUpdated,
                "last_updated_at should refresh after items complete");
    }

    @Test
    void warningsContainsLongRunningWhenThresholdExceeded() {
        long[] now = {2_000_000_000L};
        OptimizationTaskService warnService = new OptimizationTaskService(
                fakeRunService, normalizer, MAPPER,
                () -> now[0],
                DEFAULT_RETENTION, DEFAULT_MAX_TASKS, /* threshold */ 1_000L);
        try {
            // 注入一个已 RUNNING 但 startedAt 早于 (now - threshold) 的 record。
            OptimizationTaskRecord record = new OptimizationTaskRecord(
                    "long-task", "minimize avg_wait_time_minutes", List.of(new SimConfig()), now[0] - 10_000L);
            record.markRunning(now[0] - 5_000L);
            warnService.registerForTest(record);

            ObjectNode snapshot = warnService.toStatusSnapshot(record);
            assertEquals(1, snapshot.path("warnings").size());
            assertEquals(OptimizationTaskService.WARNING_LONG_RUNNING,
                    snapshot.path("warnings").get(0).asText());
        } finally {
            warnService.shutdown();
        }
    }

    @Test
    void warningsEmptyWhenWithinThreshold() {
        long[] now = {2_000_000_000L};
        OptimizationTaskService warnService = new OptimizationTaskService(
                fakeRunService, normalizer, MAPPER,
                () -> now[0],
                DEFAULT_RETENTION, DEFAULT_MAX_TASKS, /* threshold */ 1_000_000L);
        try {
            OptimizationTaskRecord record = new OptimizationTaskRecord(
                    "fresh", "minimize avg_wait_time_minutes", List.of(new SimConfig()), now[0]);
            record.markRunning(now[0] - 100L);
            warnService.registerForTest(record);

            ObjectNode snapshot = warnService.toStatusSnapshot(record);
            assertEquals(0, snapshot.path("warnings").size());
        } finally {
            warnService.shutdown();
        }
    }

    @Test
    void purgeRemovesTerminalTasksOlderThanRetention() {
        long[] now = {1_000_000_000L};
        long retention = 30L * 60L * 1000L;
        OptimizationTaskService ttlService = new OptimizationTaskService(
                fakeRunService, normalizer, MAPPER,
                () -> now[0],
                retention, DEFAULT_MAX_TASKS, DEFAULT_LONG_THRESHOLD);
        try {
            OptimizationTaskRecord stale = new OptimizationTaskRecord(
                    "stale", "minimize avg_wait_time_minutes", List.of(new SimConfig()), now[0]);
            stale.markRunning(now[0]);
            stale.markCompleted(now[0]);
            injectCompletedAt(stale, now[0] - retention - 1_000L);
            ttlService.registerForTest(stale);

            OptimizationTaskRecord fresh = new OptimizationTaskRecord(
                    "fresh", "minimize avg_wait_time_minutes", List.of(new SimConfig()), now[0]);
            fresh.markRunning(now[0]);
            fresh.markCompleted(now[0]);
            ttlService.registerForTest(fresh);

            OptimizationTaskRecord running = new OptimizationTaskRecord(
                    "running", "minimize avg_wait_time_minutes", List.of(new SimConfig()), now[0]);
            running.markRunning(now[0]);
            ttlService.registerForTest(running);

            assertEquals(3, ttlService.getTaskCount());
            int removed = ttlService.purgeExpired();
            assertEquals(1, removed);
            assertEquals(2, ttlService.getTaskCount());
            assertTrue(ttlService.get("stale").isEmpty());
            assertNotNull(ttlService.get("fresh").orElse(null));
            assertNotNull(ttlService.get("running").orElse(null));
        } finally {
            ttlService.shutdown();
        }
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertTrue(service.get("does-not-exist").isEmpty());
    }

    @Test
    void resultEachItemEchoesIndexAndConfig() throws Exception {
        OptimizationRequest req = makeRequest(2);
        // 给两条 config 设置不同 windowCount,验证 result 中能区分。
        req.getConfigs().get(0).getBaseConfig().setWindowCount(2);
        req.getConfigs().get(1).getBaseConfig().setWindowCount(3);

        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        ObjectNode result = service.toResultSnapshot(record);
        assertEquals(2, result.path("results").get(0).path("config").path("base_config").path("window_count").asInt());
        assertEquals(3, result.path("results").get(1).path("config").path("base_config").path("window_count").asInt());
    }

    @Test
    void simulationRunServiceIsNeverInvokedConcurrently() throws Exception {
        // PR-1 不变式:无论 batch 数量多少,SimulationRunService.run 始终串行调用,
        // 且只在同一个 worker 线程上发生。
        fakeRunService.setDelayMillis(50L);

        OptimizationTaskRecord first = service.submit(makeRequest(3));
        OptimizationTaskRecord second = service.submit(makeRequest(2));
        OptimizationTaskRecord third = service.submit(makeRequest(2));
        awaitTerminal(first, 10_000L);
        awaitTerminal(second, 10_000L);
        awaitTerminal(third, 10_000L);

        assertEquals(7, fakeRunService.callCount.get(), "全部 7 条 config 应都被执行");
        assertEquals(1, fakeRunService.maxObservedConcurrentCalls.get(),
                "PR-1 串行语义要求 SimulationRunService.run 任何时刻最多 1 个 in-flight 调用");
        assertEquals(1, fakeRunService.threadNamesObserved.size(),
                "PR-1 单 worker 语义要求所有 run 调用来自同一个线程,observed=" + fakeRunService.threadNamesObserved);
        assertTrue(fakeRunService.threadNamesObserved.get(0).startsWith("opt-batch-"),
                "worker 线程必须是 opt-batch-* 命名");
    }

    @Test
    void backpressureRejectsBatchWhenQueueIsFull() throws Exception {
        // 线程池 core=1 / max=1 / queue=8 → 同时最多 1 + 8 = 9 个 batch 排队或运行。
        // 用 gate 卡住第一条 run 调用,塞满队列,第 10 个 submit 必须抛 RejectedExecutionException
        // 并被 controller 翻译成 503。
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        fakeRunService.setFirstCallGate(gate);
        fakeRunService.setDelayMillis(0L);

        List<OptimizationTaskRecord> records = new ArrayList<>();
        try {
            for (int i = 0; i < 9; i++) {
                records.add(service.submit(makeRequest(1)));
            }

            assertThrows(RejectedExecutionException.class,
                    () -> service.submit(makeRequest(1)),
                    "第 10 个 submit 应在队列满时被拒绝");
            // 被拒绝的 record 不留痕迹:任务表大小回到 9。
            assertEquals(9, service.getTaskCount(), "被拒绝的 record 必须从任务表里移除");
        } finally {
            gate.countDown();
        }

        for (OptimizationTaskRecord rec : records) {
            awaitTerminal(rec, 15_000L);
        }
    }

    @Test
    void shutdownIsIdempotent() {
        service.shutdown();
        // 再次 shutdown 不应抛异常,tearDown 也会再调一次。
        service.shutdown();
        service = null; // 避免 tearDown 再次访问
    }

    @Test
    void statusSnapshotContainsExpectedFields() throws Exception {
        OptimizationRequest req = makeRequest(2);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        ObjectNode snapshot = service.toStatusSnapshot(record);
        assertNotNull(snapshot.get("batch_task_id"));
        assertNotNull(snapshot.get("status"));
        assertNotNull(snapshot.get("total"));
        assertNotNull(snapshot.get("completed"));
        assertNotNull(snapshot.get("failed"));
        assertNotNull(snapshot.get("has_failures"));
        assertNotNull(snapshot.get("first_failure_message"));
        assertNotNull(snapshot.get("percent_complete"));
        assertNotNull(snapshot.get("submitted_at_epoch_millis"));
        assertNotNull(snapshot.get("started_at_epoch_millis"));
        assertNotNull(snapshot.get("last_updated_at_epoch_millis"));
        assertNotNull(snapshot.get("completed_at_epoch_millis"));
        assertNotNull(snapshot.get("running_duration_millis"));
        assertNotNull(snapshot.get("result_available"));
        assertNotNull(snapshot.get("warnings"));
        assertTrue(snapshot.path("warnings").isArray());
        assertEquals(2, snapshot.path("total").asInt());
        assertEquals(1.0, snapshot.path("percent_complete").asDouble(), 1e-9);
    }

    @Test
    void resultSnapshotContainsExpectedTopLevelFields() throws Exception {
        OptimizationRequest req = makeRequest(2);
        OptimizationTaskRecord record = service.submit(req);
        awaitTerminal(record, 5_000L);

        ObjectNode snapshot = service.toResultSnapshot(record);
        assertEquals("batch_compare", snapshot.path("mode").asText());
        assertFalse(snapshot.path("deprecated_optimization").asBoolean(),
                "async result must NOT carry deprecated_optimization=true");
        assertNotNull(snapshot.get("batch_task_id"));
        assertNotNull(snapshot.get("evaluated_configs"));
        assertNotNull(snapshot.get("completed_count"));
        assertNotNull(snapshot.get("failed_count"));
        assertNotNull(snapshot.get("has_failures"));
        assertNotNull(snapshot.get("first_failure_message"));
        assertTrue(snapshot.path("first_failure_message").isNull(), "no failures → null");
        assertEquals(2, snapshot.path("results").size());
    }

    private OptimizationRequest makeRequest(int count) {
        OptimizationRequest req = new OptimizationRequest();
        req.setObjective("minimize avg_wait_time_minutes");
        List<SimConfig> configs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SimConfig cfg = new SimConfig();
            cfg.setDuration(0.05);
            cfg.setArrivalRate(20);
            cfg.setQueueLimit(10);
            cfg.setSeed(900L + i);
            cfg.getBaseConfig().setWindowCount(2);
            cfg.getBaseConfig().setTotalSeats(20);
            cfg.getBaseConfig().setTotalStudents(0);
            configs.add(cfg);
        }
        req.setConfigs(configs);
        return req;
    }

    private static void awaitTerminal(OptimizationTaskRecord record, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (record.isTerminal()) {
                return;
            }
            Thread.sleep(20L);
        }
        if (!record.isTerminal()) {
            throw new AssertionError("task did not reach terminal in " + timeoutMillis + "ms, status=" + record.getStatus());
        }
    }

    private static void injectCompletedAt(OptimizationTaskRecord record, long completedAt) {
        try {
            Field field = OptimizationTaskRecord.class.getDeclaredField("completedAtEpochMillis");
            field.setAccessible(true);
            field.setLong(record, completedAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to inject completedAt", e);
        }
    }

    /**
     * Test-only fake:可在指定 zero-based index 抛 RuntimeException,其余 index
     * 委托给真 SimulationRunService(用极轻 config 跑出真实 SimulationReport)。
     *
     * <p>同时记录跨 run() 调用的并发观测信息(maxObservedConcurrentCalls),用于
     * 验证 PR-1 单 worker 不变式 —— SimulationRunService 永不被并发调用。
     */
    static final class FakeSimulationRunService extends SimulationRunService {
        private final Map<Integer, RuntimeException> indexFailures = new HashMap<>();
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxObservedConcurrentCalls = new AtomicInteger(0);
        final CopyOnWriteArrayList<String> threadNamesObserved = new CopyOnWriteArrayList<>();
        private volatile long delayMillis = 0L;
        private volatile java.util.concurrent.CountDownLatch firstCallGate;

        void failAtIndex(int zeroBasedIndex, RuntimeException ex) {
            indexFailures.put(zeroBasedIndex, ex);
        }

        void setDelayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        void setFirstCallGate(java.util.concurrent.CountDownLatch gate) {
            this.firstCallGate = gate;
        }

        @Override
        public SimulationReport run(SimConfig config, String reportId) {
            int idx = callCount.getAndIncrement();
            int current = inFlight.incrementAndGet();
            maxObservedConcurrentCalls.updateAndGet(prev -> Math.max(prev, current));
            threadNamesObserved.addIfAbsent(Thread.currentThread().getName());
            try {
                RuntimeException err = indexFailures.get(idx);
                if (err != null) {
                    throw err;
                }
                if (idx == 0 && firstCallGate != null) {
                    try {
                        firstCallGate.await(30L, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (delayMillis > 0L) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.run(config, reportId);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }
}
