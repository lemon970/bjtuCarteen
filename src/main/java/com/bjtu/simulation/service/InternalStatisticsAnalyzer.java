package com.bjtu.simulation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bjtu.simulation.config.AppBeansConfig;

/**
 * Java fallback for the C++ {@code canteen-analyze} binary. Produces statistics
 * that match the C++ output schema (confidence_intervals / bottleneck /
 * headline_metrics / monte_carlo / anova) so the frontend renders the same UI
 * regardless of which engine ran.
 */
@Service
public class InternalStatisticsAnalyzer {

    private static final String SCHEMA_VERSION = "1.0";
    private static final int BOOTSTRAP_SAMPLES = 1000;
    private static final long BOOTSTRAP_SEED = 0x6B57F11C9D4E2A75L;

    private final ObjectMapper mapper;

    @Autowired
    public InternalStatisticsAnalyzer() {
        this(AppBeansConfig.createReportObjectMapper());
    }

    InternalStatisticsAnalyzer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode analyze(JsonNode report) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("source_report_id", report.path("report_id").asText(""));
        root.put("computed_by", "java-internal");

        JsonNode summary = report.path("summary");

        root.set("confidence_intervals", buildConfidenceIntervals(summary));
        root.set("bottleneck", buildBottleneck(summary));
        root.set("headline_metrics", buildHeadlineMetrics(summary));
        return root;
    }

    public ObjectNode batchAnalyze(List<JsonNode> reports) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("computed_by", "java-internal");
        root.put("report_count", reports == null ? 0 : reports.size());
        if (reports == null || reports.isEmpty()) {
            return root;
        }
        root.set("monte_carlo", buildMonteCarlo(reports));
        root.set("anova", buildAnova(reports));
        return root;
    }

    private ObjectNode buildConfidenceIntervals(JsonNode summary) {
        ObjectNode wrap = mapper.createObjectNode();
        List<Double> waitSamples = extractWaitSamples(summary);
        wrap.set("wait_time_minutes", buildBootstrapInterval("wait_time_minutes", waitSamples));

        List<Double> utilizationSamples = extractTimelineDoubles(summary, "seat_utilization_rate");
        wrap.set("seat_utilization_rate", buildBootstrapInterval("seat_utilization_rate", utilizationSamples));
        return wrap;
    }

    private ObjectNode buildBootstrapInterval(String metric, List<Double> samples) {
        ObjectNode node = mapper.createObjectNode();
        node.put("metric", metric);
        node.put("alpha", 0.05);
        node.put("sample_count", samples.size());
        if (samples.isEmpty()) {
            node.put("mean", 0.0);
            node.put("lower", 0.0);
            node.put("upper", 0.0);
            return node;
        }
        double sampleMean = SimulationMath.mean(samples);
        if (samples.size() < 5) {
            node.put("mean", round3(sampleMean));
            node.put("lower", round3(sampleMean));
            node.put("upper", round3(sampleMean));
            return node;
        }
        Random random = new Random(BOOTSTRAP_SEED);
        List<Double> bootMeans = new ArrayList<>(BOOTSTRAP_SAMPLES);
        for (int i = 0; i < BOOTSTRAP_SAMPLES; i++) {
            double sum = 0.0;
            for (int j = 0; j < samples.size(); j++) {
                sum += samples.get(random.nextInt(samples.size()));
            }
            bootMeans.add(sum / samples.size());
        }
        node.put("mean", round3(sampleMean));
        node.put("lower", round3(SimulationMath.percentile(bootMeans, 0.025)));
        node.put("upper", round3(SimulationMath.percentile(bootMeans, 0.975)));
        return node;
    }

    private ObjectNode buildBottleneck(JsonNode summary) {
        ObjectNode node = mapper.createObjectNode();
        List<Integer> windowServed = extractIntList(summary.path("window_served_counts"));
        double gini = giniCoefficient(windowServed);
        int worstWindow = argMax(windowServed);

        List<Double> queueSeries = extractTimelineDoubles(summary, "total_queue_size");
        double avgQueue = SimulationMath.mean(queueSeries);
        double threshold = Math.max(1.0, avgQueue * 1.5);
        int sustainedFrames = 0;
        for (double q : queueSeries) {
            if (q >= threshold) {
                sustainedFrames++;
            }
        }
        double score = SimulationMath.clamp(gini * 50.0 + Math.min(50.0, sustainedFrames * 1.5), 0.0, 100.0);

        node.put("score", round3(score));
        node.put("gini_coefficient", round3(gini));
        node.put("worst_window_id", worstWindow);
        node.put("sustained_peak_minutes", sustainedFrames);
        return node;
    }

    private ObjectNode buildHeadlineMetrics(JsonNode summary) {
        ObjectNode node = mapper.createObjectNode();
        node.put("typical_wait_time_minutes", round3(readDouble(summary, "typical_wait_time_minutes",
                readDouble(summary, "avg_wait_time_minutes", 0.0))));
        node.put("seat_utilization_rate", round3(readDouble(summary, "seat_utilization_rate", 0.0)));
        node.put("takeaway_rate", round3(readDouble(summary, "takeaway_rate", 0.0)));
        node.put("served_count", summary.path("served_count").asInt(0));
        node.put("abandoned_count", summary.path("abandoned_count").asInt(0));
        return node;
    }

    private ObjectNode buildMonteCarlo(List<JsonNode> reports) {
        ObjectNode wrap = mapper.createObjectNode();
        wrap.set("typical_wait_time_minutes",
                describeMetric(reports, summary -> readDouble(summary, "typical_wait_time_minutes",
                        readDouble(summary, "avg_wait_time_minutes", 0.0))));
        wrap.set("seat_utilization_rate",
                describeMetric(reports, summary -> readDouble(summary, "seat_utilization_rate", 0.0)));
        wrap.set("takeaway_rate",
                describeMetric(reports, summary -> readDouble(summary, "takeaway_rate", 0.0)));
        wrap.set("arrived_count",
                describeMetric(reports, summary -> (double) summary.path("arrived_count").asInt(0)));
        return wrap;
    }

    private ObjectNode describeMetric(List<JsonNode> reports, MetricExtractor extractor) {
        List<Double> values = new ArrayList<>(reports.size());
        for (JsonNode report : reports) {
            values.add(extractor.extract(report.path("summary")));
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("report_count", values.size());
        if (values.isEmpty()) {
            node.put("mean", 0.0);
            node.put("stddev", 0.0);
            node.put("min", 0.0);
            node.put("max", 0.0);
            return node;
        }
        double mean = SimulationMath.mean(values);
        double sumSq = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double stddev = values.size() > 1 ? Math.sqrt(sumSq / (values.size() - 1)) : 0.0;
        node.put("mean", round3(mean));
        node.put("stddev", round3(stddev));
        node.put("min", round3(min));
        node.put("max", round3(max));
        return node;
    }

    private ObjectNode buildAnova(List<JsonNode> reports) {
        ObjectNode node = mapper.createObjectNode();
        List<List<Double>> groups = new ArrayList<>(reports.size());
        List<String> labels = new ArrayList<>(reports.size());
        for (JsonNode report : reports) {
            List<Double> samples = extractTimelineDoubles(report.path("summary"), "total_queue_size");
            if (samples.size() >= 2) {
                groups.add(samples);
                labels.add(report.path("scenario_id").asText(report.path("report_id").asText("")));
            }
        }
        if (groups.size() < 2) {
            node.put("enabled", false);
            node.put("reason", "fewer than 2 reports with enough samples");
            return node;
        }

        double grandSum = 0.0;
        int totalSamples = 0;
        List<Double> groupMeans = new ArrayList<>(groups.size());
        for (List<Double> group : groups) {
            for (double v : group) {
                grandSum += v;
            }
            totalSamples += group.size();
            groupMeans.add(SimulationMath.mean(group));
        }
        double grandMean = totalSamples == 0 ? 0.0 : grandSum / totalSamples;
        double ssBetween = 0.0;
        double ssWithin = 0.0;
        for (int i = 0; i < groups.size(); i++) {
            List<Double> group = groups.get(i);
            double groupMean = groupMeans.get(i);
            ssBetween += group.size() * (groupMean - grandMean) * (groupMean - grandMean);
            for (double v : group) {
                ssWithin += (v - groupMean) * (v - groupMean);
            }
        }
        double dfBetween = groups.size() - 1;
        double dfWithin = Math.max(1, totalSamples - groups.size());
        double msBetween = dfBetween > 0 ? ssBetween / dfBetween : 0.0;
        double msWithin = ssWithin / dfWithin;
        double f = msWithin > 0 ? msBetween / msWithin : 0.0;

        int strongest = argMax(groupMeans);
        int weakest = argMin(groupMeans);

        node.put("enabled", true);
        node.put("f_statistic", round3(f));
        node.put("between_group_variance", round3(msBetween));
        node.put("within_group_variance", round3(msWithin));
        node.put("group_count", groups.size());
        node.put("total_samples", totalSamples);
        node.put("strongest_group", labels.get(strongest));
        node.put("weakest_group", labels.get(weakest));
        return node;
    }

    private List<Double> extractWaitSamples(JsonNode summary) {
        double avgWait = readDouble(summary, "typical_wait_time_minutes",
                readDouble(summary, "avg_wait_time_minutes", 0.0));
        if (avgWait <= 0.0) {
            return List.of();
        }
        int servedCount = summary.path("served_count").asInt(0);
        if (servedCount <= 0) {
            return List.of();
        }
        double endMinutes = readDouble(summary, "simulation_end_time_minutes", 0.0);
        if (endMinutes <= 0.0) {
            return List.of();
        }
        double serviceRatePerMinute = servedCount / endMinutes;
        if (serviceRatePerMinute <= 0.0) {
            return List.of();
        }
        List<Double> queueSeries = extractTimelineDoubles(summary, "total_queue_size");
        if (queueSeries.isEmpty()) {
            return List.of(avgWait);
        }
        List<Double> waits = new ArrayList<>(queueSeries.size());
        for (double q : queueSeries) {
            waits.add(SimulationMath.clamp(q / serviceRatePerMinute, 0.0, 60.0));
        }
        return waits;
    }

    private List<Double> extractTimelineDoubles(JsonNode summary, String field) {
        JsonNode timeline = summary.path("timeline");
        if (!timeline.isArray()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode point : timeline) {
            JsonNode value = point.path(field);
            if (value.isNumber()) {
                values.add(value.asDouble());
            }
        }
        return values;
    }

    private List<Integer> extractIntList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            values.add(value.asInt(0));
        }
        return values;
    }

    private double giniCoefficient(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        long total = 0;
        for (int v : values) {
            total += Math.max(0, v);
        }
        if (total <= 0) {
            return 0.0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double cumulative = 0.0;
        for (int i = 0; i < n; i++) {
            cumulative += (i + 1.0) * sorted.get(i);
        }
        return SimulationMath.clamp((2.0 * cumulative) / (n * (double) total) - (n + 1.0) / n, 0.0, 1.0);
    }

    private int argMax(List<? extends Number> values) {
        if (values == null || values.isEmpty()) {
            return -1;
        }
        int best = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).doubleValue() > values.get(best).doubleValue()) {
                best = i;
            }
        }
        return best;
    }

    private int argMin(List<? extends Number> values) {
        if (values == null || values.isEmpty()) {
            return -1;
        }
        int best = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).doubleValue() < values.get(best).doubleValue()) {
                best = i;
            }
        }
        return best;
    }

    private double readDouble(JsonNode node, String field, double fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asDouble(fallback);
    }

    private double round3(double value) {
        return SimulationMath.round3(value);
    }

    @FunctionalInterface
    private interface MetricExtractor {
        double extract(JsonNode summary);
    }
}
