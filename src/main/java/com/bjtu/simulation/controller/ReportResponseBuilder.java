package com.bjtu.simulation.controller;

import java.util.Optional;

import com.bjtu.simulation.service.SimulationReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ReportResponseBuilder {
    private final ObjectMapper mapper;
    private final SimulationReportRepository reportRepository;

    ReportResponseBuilder(ObjectMapper mapper, SimulationReportRepository reportRepository) {
        this.mapper = mapper;
        this.reportRepository = reportRepository;
    }

    ObjectNode buildArrayPage(String reportId, String collectionName, JsonNode source, int page, int pageSize) {
        int totalItems = source.size();
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / pageSize);
        int fromIndex = Math.min((page - 1) * pageSize, totalItems);
        int toIndex = Math.min(fromIndex + pageSize, totalItems);

        ArrayNode items = mapper.createArrayNode();
        for (int i = fromIndex; i < toIndex; i++) {
            items.add(source.get(i));
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("report_id", reportId);
        data.put("collection", collectionName);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("total_items", totalItems);
        data.put("total_pages", totalPages);
        data.put("has_next", page < totalPages);
        data.put("has_previous", page > 1 && totalPages > 0);
        data.set("items", items);
        return data;
    }

    String buildCsv(JsonNode report, JsonNode history, String reportId) {
        StringBuilder csv = new StringBuilder();
        csv.append("section,report_id,time_seconds,minute,metric_a,metric_b,metric_c,metric_d,metric_e,result,base_probability,preference_factor,seat_pressure_factor,wait_pressure_factor,queue_pressure_factor,weather_factor,decision_reason\n");
        JsonNode summary = report.path("summary");
        appendArrivalRows(csv, reportId, summary.path("arrival_samples"));
        appendDecisionRows(csv, reportId, summary.path("takeaway_decision_records"));
        JsonNode historySource = history != null && history.isArray() ? history : summary.path("history");
        appendHistoryRows(csv, reportId, historySource);
        return csv.toString();
    }

    JsonNode responseReportNode(JsonNode reportNode, boolean includeHistory) {
        JsonNode copy = reportNode.deepCopy();
        if (!includeHistory && copy instanceof ObjectNode reportObject) {
            JsonNode summary = reportObject.path("summary");
            if (summary instanceof ObjectNode summaryObject) {
                summaryObject.remove("history");
                stripTimelineTableSnapshots(summaryObject);
            }
        }
        return copy;
    }

    void attachHistoryIfAvailable(JsonNode reportNode, String reportId) {
        if (!(reportNode instanceof ObjectNode reportObject)) {
            return;
        }
        JsonNode summary = reportObject.path("summary");
        if (!(summary instanceof ObjectNode summaryObject) || summaryObject.path("history").isArray()) {
            return;
        }
        Optional<JsonNode> history = reportRepository.readHistoryById(reportId);
        history.ifPresent(node -> {
            if (node.isArray()) {
                summaryObject.set("history", node);
            }
        });
    }

    private void appendArrivalRows(StringBuilder csv, String reportId, JsonNode samples) {
        if (!samples.isArray()) {
            return;
        }
        for (JsonNode sample : samples) {
            csv.append("arrival,")
                    .append(csv(reportId)).append(',')
                    .append(sample.path("time_seconds").asLong()).append(',')
                    .append(sample.path("minute").asLong()).append(',')
                    .append(sample.path("interval_seconds").asLong()).append(',')
                    .append(sample.path("lambda_per_hour").asDouble()).append(',')
                    .append(csv(sample.path("arrival_group").asText(""))).append(',')
                    .append(sample.path("party_size").asInt()).append(',')
                    .append(',')
                    .append("scheduled")
                    .append('\n');
        }
    }

    private void appendDecisionRows(StringBuilder csv, String reportId, JsonNode decisions) {
        if (!decisions.isArray()) {
            return;
        }
        for (JsonNode decision : decisions) {
            csv.append("takeaway_decision,")
                    .append(csv(reportId)).append(',')
                    .append(decision.path("time_seconds").asLong()).append(',')
                    .append(decision.path("time_seconds").asLong() / 60).append(',')
                    .append(decision.path("final_probability").asDouble()).append(',')
                    .append(decision.path("random_roll").asDouble()).append(',')
                    .append(decision.path("seat_utilization_rate").asDouble()).append(',')
                    .append(decision.path("queue_pressure").asDouble()).append(',')
                    .append(decision.path("wait_minutes").asDouble()).append(',')
                    .append(decision.path("takeaway").asBoolean() ? "takeaway" : "dine_in")
                    .append(',')
                    .append(decision.path("base_probability").asDouble()).append(',')
                    .append(decision.path("preference_factor").asDouble()).append(',')
                    .append(decision.path("seat_pressure_factor").asDouble()).append(',')
                    .append(decision.path("wait_pressure_factor").asDouble()).append(',')
                    .append(decision.path("queue_pressure_factor").asDouble()).append(',')
                    .append(decision.path("weather_factor").asDouble()).append(',')
                    .append(csv(decision.path("decision_reason").asText("")))
                    .append('\n');
        }
    }

    private void appendHistoryRows(StringBuilder csv, String reportId, JsonNode history) {
        if (!history.isArray()) {
            return;
        }
        for (JsonNode point : history) {
            csv.append("history,")
                    .append(csv(reportId)).append(',')
                    .append(point.path("time").asLong()).append(',')
                    .append(point.path("time").asLong() / 60).append(',')
                    .append(point.path("total_queue_size").asInt()).append(',')
                    .append(point.path("occupied_seats").asInt()).append(',')
                    .append(point.path("takeaway_count").asInt()).append(',')
                    .append(point.path("served_count").asInt()).append(',')
                    .append(point.path("arrived_count").asInt()).append(',')
                    .append(csv(point.path("event_message").asText("")))
                    .append('\n');
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private void stripTimelineTableSnapshots(ObjectNode summaryObject) {
        JsonNode timeline = summaryObject.path("timeline");
        if (!timeline.isArray()) {
            return;
        }
        for (JsonNode item : timeline) {
            if (item instanceof ObjectNode itemObject) {
                itemObject.remove("table_snapshots");
            }
        }
    }
}
