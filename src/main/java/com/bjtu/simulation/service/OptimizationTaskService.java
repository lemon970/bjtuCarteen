package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.dto.OptimizationRequest;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

/**
 * 异步 batch optimize 任务服务。与 {@link SimulationTaskService} 平行存在,
 * 互不复用线程池 / 任务表,避免单任务与批任务互相饥饿。
 *
 * <p>PR-1 第一版:**所有 batch task 与 batch 内 configs 全部串行执行**。
 * - HTTP 立即返回,客户端不再阻塞 → 解决超时问题。
 * - 完全规避 SimulationRunService 是否线程安全的不确定性。
 *
 * <p>线程池:有界 {@link ThreadPoolExecutor}(core=1, max=1, queue=8)。
 * 单 worker 保证任意时刻 **最多** 一个 batch 在跑、最多一次
 * {@code simulationRunService.run(...)} 调用在飞;再加 batch 内 for 循环本就串行,
 * SimulationRunService 永远只被这一个 worker 线程调用。
 *
 * <p>同时排队上限 1 + 8 = 9 个 batch;超出抛 {@link RejectedExecutionException},
 * controller 映射成 503。PR-2 完成 SimulationRunService thread-safety audit
 * 之后才会评估是否提升 max,或者引入 batch 内并行。
 *
 * <p>TTL / 容量:与 {@link SimulationTaskService} 一致(30 分钟保留 + 200 任务上限)。
 * 终态任务超过 retention 后被 {@link #purgeExpired} 清理;表满时清最旧 terminal。
 *
 * <p>本期不实现:cancel / SSE / 强制 timeout。CANCELLED 状态作为 enum 占位,无路径触达。
 */
@Service
public class OptimizationTaskService {

    static final long DEFAULT_RETENTION_MILLIS = 30L * 60L * 1000L;
    static final int DEFAULT_MAX_TASKS = 200;
    /** 长任务 warning 阈值,默认 5 分钟。超过此值返回 TASK_RUNNING_LONGER_THAN_EXPECTED。 */
    static final long DEFAULT_LONG_RUNNING_THRESHOLD_MILLIS = 5L * 60L * 1000L;
    static final String WARNING_LONG_RUNNING = "TASK_RUNNING_LONGER_THAN_EXPECTED";

    private final SimulationRunService simulationRunService;
    private final SimulationConfigNormalizer configNormalizer;
    private final ObjectMapper reportMapper;
    private final OptimizationResultBuilder resultBuilder;
    private final ConcurrentHashMap<String, OptimizationTaskRecord> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor taskExecutor;
    private final LongSupplier clock;
    private final long retentionMillis;
    private final int maxTasks;
    private final long longRunningThresholdMillis;

    @Autowired
    public OptimizationTaskService(SimulationRunService simulationRunService,
                                   SimulationConfigNormalizer configNormalizer) {
        this(simulationRunService, configNormalizer,
                AppBeansConfig.createReportObjectMapper(),
                System::currentTimeMillis,
                DEFAULT_RETENTION_MILLIS,
                DEFAULT_MAX_TASKS,
                DEFAULT_LONG_RUNNING_THRESHOLD_MILLIS);
    }

    /** Test-only constructor:允许注入 clock 和阈值。 */
    OptimizationTaskService(SimulationRunService simulationRunService,
                            SimulationConfigNormalizer configNormalizer,
                            ObjectMapper reportMapper,
                            LongSupplier clock,
                            long retentionMillis,
                            int maxTasks,
                            long longRunningThresholdMillis) {
        this.simulationRunService = simulationRunService;
        this.configNormalizer = configNormalizer;
        this.reportMapper = reportMapper;
        this.resultBuilder = new OptimizationResultBuilder(reportMapper);
        this.clock = clock;
        this.retentionMillis = Math.max(1L, retentionMillis);
        this.maxTasks = Math.max(1, maxTasks);
        this.longRunningThresholdMillis = Math.max(1L, longRunningThresholdMillis);
        this.taskExecutor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                new OptimizationTaskThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 提交批任务。立即返回 record(QUEUED),后台 worker 拿起后转 RUNNING 顺序执行。
     *
     * @throws IllegalArgumentException configs 为空 / null,或 size 超 max_candidates。
     * @throws RejectedExecutionException 线程池队列已满(单 worker + 8 缓冲;PR-1 串行下最多
     *         同时存在 1 + 8 = 9 个未终结 batch)。
     */
    public OptimizationTaskRecord submit(OptimizationRequest request) {
        OptimizationRequest safeRequest = request == null ? new OptimizationRequest() : request;
        List<SimConfig> configs = explicitConfigsForAsync(safeRequest);
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("configs must not be empty for async optimize");
        }

        purgeExpired();
        if (tasks.size() >= maxTasks) {
            evictOldestTerminal();
        }

        String batchTaskId = UUID.randomUUID().toString();
        String objectiveRaw = safeRequest.getObjective();
        OptimizationTaskRecord record = new OptimizationTaskRecord(
                batchTaskId, objectiveRaw, configs, clock.getAsLong());
        tasks.put(batchTaskId, record);

        try {
            taskExecutor.execute(() -> runBatch(record));
        } catch (RejectedExecutionException ree) {
            tasks.remove(batchTaskId, record);
            throw ree;
        }
        return record;
    }

    public Optional<OptimizationTaskRecord> get(String batchTaskId) {
        return Optional.ofNullable(tasks.get(batchTaskId));
    }

    int getTaskCount() {
        return tasks.size();
    }

    /**
     * status 视图:轻量信息 + 心跳字段,**不**包含 itemResults。
     * 长任务自动追加 TASK_RUNNING_LONGER_THAN_EXPECTED warning。
     */
    public ObjectNode toStatusSnapshot(OptimizationTaskRecord record) {
        long now = clock.getAsLong();
        ObjectNode data = reportMapper.createObjectNode();
        data.put("batch_task_id", record.getBatchTaskId());
        data.put("status", record.getStatus());
        data.put("objective", normalizedObjectiveLabel(record.getObjectiveRaw()));
        data.put("total", record.getTotal());
        data.put("completed", record.getCompletedCount());
        data.put("failed", record.getFailedCount());
        data.put("has_failures", record.hasFailures());
        if (record.getFirstFailureMessage() != null) {
            data.put("first_failure_message", record.getFirstFailureMessage());
        } else {
            data.putNull("first_failure_message");
        }
        data.put("current_index", record.getCurrentIndex());
        double percent = record.getTotal() == 0
                ? 0.0
                : (double) record.getCompletedCount() / (double) record.getTotal();
        data.put("percent_complete", percent);
        data.put("submitted_at_epoch_millis", record.getSubmittedAtEpochMillis());
        data.put("started_at_epoch_millis", record.getStartedAtEpochMillis());
        data.put("last_updated_at_epoch_millis", record.getLastUpdatedAtEpochMillis());
        data.put("completed_at_epoch_millis", record.getCompletedAtEpochMillis());
        long runningDuration = computeRunningDuration(record, now);
        data.put("running_duration_millis", runningDuration);
        data.put("result_available", record.isResultAvailable());

        ArrayNode warnings = reportMapper.createArrayNode();
        if (OptimizationTaskRecord.STATUS_RUNNING.equals(record.getStatus())
                && record.getStartedAtEpochMillis() > 0L
                && (now - record.getStartedAtEpochMillis()) >= longRunningThresholdMillis) {
            warnings.add(WARNING_LONG_RUNNING);
        }
        data.set("warnings", warnings);
        return data;
    }

    /**
     * 完整 result 视图。仅在 isResultAvailable() = true 时被 controller 调用。
     * schema 与同步 /optimize 接口对齐,新增 has_failures / failed_count /
     * first_failure_message / completed_count。每条 item 自带 error_message
     * (成功为 null,失败为 "<exception class>: <msg>")。
     */
    public ObjectNode toResultSnapshot(OptimizationTaskRecord record) {
        ObjectNode data = reportMapper.createObjectNode();
        data.put("mode", "batch_compare");
        data.put("deprecated_optimization", false);
        data.put("batch_task_id", record.getBatchTaskId());
        data.put("objective", normalizedObjectiveLabel(record.getObjectiveRaw()));
        data.put("evaluated_configs", record.getTotal());
        data.put("completed_count", record.getCompletedCount());
        data.put("failed_count", record.getFailedCount());
        data.put("has_failures", record.hasFailures());
        if (record.getFirstFailureMessage() != null) {
            data.put("first_failure_message", record.getFirstFailureMessage());
        } else {
            data.putNull("first_failure_message");
        }

        ArrayNode results = reportMapper.createArrayNode();
        for (int i = 0; i < record.getTotal(); i++) {
            ObjectNode item = record.getItemResults().get(i);
            if (item != null) {
                results.add(item);
            }
        }
        data.set("results", results);
        return data;
    }

    /**
     * 清理已终结(COMPLETED/FAILED/CANCELLED)且 completedAt 早于 (now - retentionMillis) 的任务。
     */
    int purgeExpired() {
        long now = clock.getAsLong();
        long cutoff = now - retentionMillis;
        int removed = 0;
        for (var entry : new ArrayList<>(tasks.entrySet())) {
            OptimizationTaskRecord record = entry.getValue();
            if (record == null || !record.isTerminal()) {
                continue;
            }
            long completedAt = record.getCompletedAtEpochMillis();
            if (completedAt > 0L && completedAt < cutoff) {
                tasks.remove(entry.getKey(), record);
                removed++;
            }
        }
        return removed;
    }

    private void evictOldestTerminal() {
        Optional<OptimizationTaskRecord> oldest = tasks.values().stream()
                .filter(OptimizationTaskRecord::isTerminal)
                .min(Comparator.comparingLong(OptimizationTaskRecord::getCompletedAtEpochMillis));
        oldest.ifPresent(r -> tasks.remove(r.getBatchTaskId(), r));
    }

    private List<SimConfig> explicitConfigsForAsync(OptimizationRequest request) {
        List<SimConfig> source = request.getConfigs();
        if (source == null || source.isEmpty()) {
            // 异步路径不沿用同步接口"包装单 config"的兼容逻辑。
            return List.of();
        }

        int limit = Math.min(source.size(), Math.max(1, request.getMaxCandidates()));
        List<SimConfig> configs = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            configs.add(configNormalizer.normalize(cloneConfig(source.get(i))));
        }
        return configs;
    }

    private SimConfig cloneConfig(SimConfig config) {
        return reportMapper.convertValue(config == null ? new SimConfig() : config, SimConfig.class);
    }

    private void runBatch(OptimizationTaskRecord record) {
        OptimizationResultBuilder.Objective objective = resultBuilder.parseObjective(record.getObjectiveRaw());
        record.markRunning(clock.getAsLong());

        List<SimConfig> configs = record.getConfigs();
        for (int i = 0; i < configs.size(); i++) {
            int index = i + 1;
            record.setCurrentIndex(index);
            record.touchLastUpdated(clock.getAsLong());
            SimConfig config = configs.get(i);
            try {
                SimulationReport report = simulationRunService.run(config, UUID.randomUUID().toString());
                ObjectNode item = resultBuilder.buildSuccessItemNodeWithErrorField(index, report, objective);
                record.setItemResult(i, item);
                record.incrementCompleted();
            } catch (Throwable t) {
                ObjectNode item = resultBuilder.buildFailedItemNode(index, config, t);
                record.setItemResult(i, item);
                record.incrementFailed();
                record.recordFirstFailure(index, t);
            } finally {
                record.touchLastUpdated(clock.getAsLong());
            }
        }

        long now = clock.getAsLong();
        if (record.getCompletedCount() == 0 && record.getFailedCount() == record.getTotal()) {
            record.markFailed(now);
        } else {
            record.markCompleted(now);
        }
    }

    private long computeRunningDuration(OptimizationTaskRecord record, long now) {
        long started = record.getStartedAtEpochMillis();
        if (started <= 0L) {
            return 0L;
        }
        if (record.isTerminal() && record.getCompletedAtEpochMillis() > 0L) {
            return Math.max(0L, record.getCompletedAtEpochMillis() - started);
        }
        return Math.max(0L, now - started);
    }

    private String normalizedObjectiveLabel(String raw) {
        OptimizationResultBuilder.Objective objective = resultBuilder.parseObjective(raw);
        return objective.direction() + " " + objective.metric();
    }

    /** Test-only:从外部注入一个已存在的 record(用于 TTL/容量场景)。 */
    void registerForTest(OptimizationTaskRecord record) {
        tasks.put(record.getBatchTaskId(), record);
    }

    private static final class OptimizationTaskThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "opt-batch-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
