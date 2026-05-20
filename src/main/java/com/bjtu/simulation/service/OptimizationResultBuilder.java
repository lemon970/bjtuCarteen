package com.bjtu.simulation.service;

import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.dto.SimulationReport;
import com.bjtu.simulation.dto.SimulationSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 同步与异步 optimize 接口共用的 result item 构造器。package-private,无 @Service。
 *
 * <p>抽取目的:防止同步 {@link OptimizationService} 与异步 {@code OptimizationTaskService}
 * 输出漂移。任何字段新增或语义变更都应只在本类发生。
 *
 * <p>对外暴露三个 build 方法:
 * <ul>
 *   <li>{@link #buildItemNode}:同步路径,字面等价于旧 OptimizationService.toResultNode
 *       —— 不带 error_message 字段。</li>
 *   <li>{@link #buildSuccessItemNodeWithErrorField}:异步成功 item,在 buildItemNode
 *       基础上追加 error_message=null,与失败 item 的 schema 对齐。</li>
 *   <li>{@link #buildFailedItemNode}:异步失败 item,error_message=&lt;exception class&gt;: &lt;msg&gt;。</li>
 * </ul>
 */
final class OptimizationResultBuilder {

    private final ObjectMapper reportMapper;

    OptimizationResultBuilder(ObjectMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /**
     * 同步路径专用:与旧 OptimizationService.toResultNode 字面等价。不带 error_message。
     */
    ObjectNode buildItemNode(int index, SimulationReport report, Objective objective) {
        SimulationSummary summary = report.getSummary();
        ObjectNode node = reportMapper.createObjectNode();
        node.put("index", index);
        node.put("report_id", report.getReportId());
        node.set("config", reportMapper.valueToTree(report.getConfig()));
        node.set("summary", lightweightSummaryNode(summary));
        node.put("objective_value", metricValue(summary, objective.metric()));
        return node;
    }

    /**
     * 异步成功 item:在 buildItemNode 基础上追加 error_message=null,
     * 让一个 batch 内成功与失败 item 的字段集合一致,前端不必判断字段存在性。
     */
    ObjectNode buildSuccessItemNodeWithErrorField(int index, SimulationReport report, Objective objective) {
        ObjectNode node = buildItemNode(index, report, objective);
        node.putNull("error_message");
        return node;
    }

    /**
     * 异步失败 item:summary/objective_value/report_id 为 null,error_message 为
     * "&lt;exception class&gt;: &lt;message&gt;",不带 stack trace。
     *
     * <p>config 字段保留输入,便于前端展示是哪条配置失败。
     */
    ObjectNode buildFailedItemNode(int index, SimConfig config, Throwable error) {
        ObjectNode node = reportMapper.createObjectNode();
        node.put("index", index);
        node.putNull("report_id");
        node.set("config", reportMapper.valueToTree(config == null ? new SimConfig() : config));
        node.putNull("summary");
        node.putNull("objective_value");
        node.put("error_message", formatErrorMessage(error));
        return node;
    }

    /**
     * 解析 "minimize|maximize <metric>" 字符串。
     * 与旧 OptimizationService.parseObjective 行为一致,空 / null 落到默认 minimize typical_wait_time_minutes。
     */
    Objective parseObjective(String objective) {
        String raw = objective == null || objective.isBlank()
                ? "minimize typical_wait_time_minutes"
                : objective.trim().toLowerCase();
        String[] parts = raw.split("\\s+");
        String direction = parts.length > 0 && "maximize".equals(parts[0]) ? "maximize" : "minimize";
        String metric = parts.length > 1 ? parts[1] : "typical_wait_time_minutes";
        return new Objective(direction, metric);
    }

    private JsonNode lightweightSummaryNode(SimulationSummary summary) {
        JsonNode node = reportMapper.valueToTree(summary);
        if (node instanceof ObjectNode summaryObject) {
            summaryObject.remove("history");
            summaryObject.remove("timeline");
            summaryObject.remove("table_snapshots");
        }
        return node;
    }

    private double metricValue(SimulationSummary summary, String metric) {
        return switch (normalizeField(metric)) {
            case "served_count" -> summary.getServedCount();
            case "abandoned_count" -> summary.getAbandonedCount();
            case "max_queue_size" -> summary.getMaxQueueSize();
            case "max_total_queue_size" -> summary.getMaxTotalQueueSize();
            case "seat_utilization_rate" -> summary.getSeatUtilizationRate();
            case "takeaway_rate" -> summary.getTakeawayRate();
            case "raw_avg_wait_time_minutes", "avg_wait_time_minutes" -> summary.getAvgWaitTimeMinutes();
            case "steady_avg_wait_time_minutes" -> summary.getSteadyAvgWaitTimeMinutes();
            case "typical_wait_time_minutes" -> summary.getTypicalWaitTimeMinutes();
            case "median_wait_time_minutes" -> summary.getMedianWaitTimeMinutes();
            case "p75_wait_time_minutes" -> summary.getP75WaitTimeMinutes();
            case "p90_wait_time_minutes" -> summary.getP90WaitTimeMinutes();
            case "long_wait_rate" -> summary.getLongWaitRate();
            case "zero_wait_rate" -> summary.getZeroWaitRate();
            case "edge_wait_sample_rate" -> summary.getEdgeWaitSampleRate();
            default -> summary.getAvgWaitTimeMinutes();
        };
    }

    private String normalizeField(String field) {
        return field == null ? "" : field.trim().toLowerCase();
    }

    private static String formatErrorMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String simpleName = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null || message.isBlank() ? simpleName : simpleName + ": " + message;
    }

    /**
     * 同步与异步路径共享的 objective 视图。direction ∈ {minimize, maximize},
     * metric 是 SimulationSummary 上的指标名(已小写)。
     */
    record Objective(String direction, String metric) {
    }
}
