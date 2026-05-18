#include "AnalysisCore.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <random>
#include <unordered_map>

namespace analysis {

namespace {

double mean(const std::vector<double>& xs) {
    if (xs.empty()) return 0.0;
    double sum = std::accumulate(xs.begin(), xs.end(), 0.0);
    return sum / xs.size();
}

double stddev(const std::vector<double>& xs) {
    if (xs.size() < 2) return 0.0;
    double m = mean(xs);
    double acc = 0.0;
    for (double v : xs) acc += (v - m) * (v - m);
    return std::sqrt(acc / (xs.size() - 1));
}

std::vector<double> readNumberArray(const JsonValue& parent, const std::string& key) {
    std::vector<double> out;
    const auto& node = parent.path(key);
    if (!node.isArray()) return out;
    out.reserve(node.asArray().size());
    for (const auto& v : node.asArray()) {
        if (v.isNumber()) out.push_back(v.asNumber());
    }
    return out;
}

std::vector<double> extractWaitTimeMinutes(const JsonValue& report) {
    // The summary stores aggregated metrics, not raw samples; fall back to
    // wait_time_metrics.distribution when raw samples are not exposed.
    std::vector<double> samples;
    const auto& summary = report.path("summary");
    const auto& history = summary.path("history");
    if (history.isArray()) {
        // history rows do not carry per-student wait time; reconstruct from
        // wait_time_metrics distribution buckets if available.
    }
    const auto& dist = summary.path("wait_time_metrics.wait_time_distribution");
    if (dist.isArray()) {
        for (const auto& bucket : dist.asArray()) {
            double lower = bucket.path("lower_bound_minutes").asNumber();
            double upper = bucket.path("upper_bound_minutes").asNumber();
            int count = static_cast<int>(bucket.path("count").asNumber());
            double mid = (upper > lower) ? (lower + upper) / 2.0 : lower;
            for (int i = 0; i < count; ++i) samples.push_back(mid);
        }
    }
    return samples;
}

}  // namespace

ConfidenceInterval bootstrapCi(const std::vector<double>& samples,
                               int iterations,
                               double alpha,
                               unsigned long long seed) {
    ConfidenceInterval ci;
    ci.sampleCount = static_cast<int>(samples.size());
    if (samples.empty()) return ci;
    ci.mean = mean(samples);
    if (samples.size() < 2 || iterations < 10) {
        ci.lower = ci.mean;
        ci.upper = ci.mean;
        return ci;
    }

    std::mt19937_64 rng(seed);
    std::uniform_int_distribution<std::size_t> pick(0, samples.size() - 1);
    std::vector<double> resampledMeans;
    resampledMeans.reserve(iterations);
    for (int it = 0; it < iterations; ++it) {
        double sum = 0.0;
        for (std::size_t i = 0; i < samples.size(); ++i) {
            sum += samples[pick(rng)];
        }
        resampledMeans.push_back(sum / samples.size());
    }
    std::sort(resampledMeans.begin(), resampledMeans.end());
    auto pickIdx = [&](double q) {
        double pos = q * (resampledMeans.size() - 1);
        std::size_t i = static_cast<std::size_t>(pos);
        return resampledMeans[std::min(i, resampledMeans.size() - 1)];
    };
    ci.lower = pickIdx(alpha / 2.0);
    ci.upper = pickIdx(1.0 - alpha / 2.0);
    return ci;
}

BottleneckResult computeBottleneck(const JsonValue& timeline) {
    BottleneckResult result;
    if (!timeline.isArray() || timeline.asArray().empty()) return result;

    // Aggregate per-window queue lengths; track sustained congestion (any window > 5).
    std::unordered_map<int, double> windowSums;
    int sustained = 0;
    int totalSamples = 0;
    for (const auto& point : timeline.asArray()) {
        const auto& queueSizes = point.path("window_queue_sizes");
        if (!queueSizes.isArray()) continue;
        ++totalSamples;
        bool anyHot = false;
        int idx = 0;
        for (const auto& q : queueSizes.asArray()) {
            double v = q.asNumber();
            windowSums[idx] += v;
            if (v >= 5.0) anyHot = true;
            ++idx;
        }
        if (anyHot) ++sustained;
    }
    if (windowSums.empty()) return result;

    std::vector<double> means;
    int worstId = -1;
    double worstMean = -1.0;
    for (const auto& [id, sum] : windowSums) {
        double m = sum / std::max(1, totalSamples);
        means.push_back(m);
        if (m > worstMean) { worstMean = m; worstId = id; }
    }
    std::sort(means.begin(), means.end());
    double total = std::accumulate(means.begin(), means.end(), 0.0);
    double gini = 0.0;
    if (total > 0.0 && means.size() >= 2) {
        double weighted = 0.0;
        for (std::size_t i = 0; i < means.size(); ++i) {
            weighted += (2.0 * (i + 1) - means.size() - 1) * means[i];
        }
        gini = weighted / (means.size() * total);
        if (gini < 0.0) gini = 0.0;
    }

    double sustainedShare = totalSamples == 0 ? 0.0 : static_cast<double>(sustained) / totalSamples;
    result.giniCoefficient = gini;
    result.sustainedPeakMinutes = sustained;
    result.worstWindowId = worstId;
    result.score = std::min(100.0, (gini * 70.0 + sustainedShare * 30.0) * 100.0);
    return result;
}

AnovaResult computeAnova(const std::vector<std::vector<double>>& groups,
                         const std::vector<std::string>& labels) {
    AnovaResult result;
    if (groups.size() < 2) return result;
    int validGroups = 0;
    int totalN = 0;
    double grandSum = 0.0;
    std::vector<double> groupMeans(groups.size(), 0.0);
    for (std::size_t i = 0; i < groups.size(); ++i) {
        if (groups[i].empty()) continue;
        ++validGroups;
        groupMeans[i] = mean(groups[i]);
        grandSum += std::accumulate(groups[i].begin(), groups[i].end(), 0.0);
        totalN += static_cast<int>(groups[i].size());
    }
    if (validGroups < 2 || totalN < validGroups + 1) return result;

    double grandMean = grandSum / totalN;
    double ssBetween = 0.0;
    double ssWithin = 0.0;
    for (std::size_t i = 0; i < groups.size(); ++i) {
        if (groups[i].empty()) continue;
        ssBetween += groups[i].size() * (groupMeans[i] - grandMean) * (groupMeans[i] - grandMean);
        for (double v : groups[i]) ssWithin += (v - groupMeans[i]) * (v - groupMeans[i]);
    }
    int dfBetween = validGroups - 1;
    int dfWithin = totalN - validGroups;
    double msBetween = ssBetween / std::max(1, dfBetween);
    double msWithin = ssWithin / std::max(1, dfWithin);

    result.enabled = true;
    result.groupCount = validGroups;
    result.totalSamples = totalN;
    result.betweenGroupVariance = msBetween;
    result.withinGroupVariance = msWithin;
    result.fStatistic = msWithin > 0.0 ? msBetween / msWithin : 0.0;

    int strongestIdx = -1;
    int weakestIdx = -1;
    double bestMean = -std::numeric_limits<double>::infinity();
    double worstMean = std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < groups.size(); ++i) {
        if (groups[i].empty()) continue;
        if (groupMeans[i] > bestMean) { bestMean = groupMeans[i]; strongestIdx = static_cast<int>(i); }
        if (groupMeans[i] < worstMean) { worstMean = groupMeans[i]; weakestIdx = static_cast<int>(i); }
    }
    auto resolveLabel = [&](int idx) -> std::string {
        if (idx < 0) return "";
        if (idx < static_cast<int>(labels.size())) return labels[idx];
        return std::to_string(idx);
    };
    result.strongestGroup = resolveLabel(strongestIdx);
    result.weakestGroup = resolveLabel(weakestIdx);
    return result;
}

JsonValue analyzeReport(const JsonValue& report) {
    JsonObject root;
    root.emplace("schema_version", JsonValue(std::string("1.0")));
    root.emplace("source_report_id", report.path("report_id").isNull()
                                          ? JsonValue(std::string(""))
                                          : JsonValue(report.path("report_id").asString()));

    auto waitSamples = extractWaitTimeMinutes(report);
    auto waitCi = bootstrapCi(waitSamples, 1000, 0.05, 0xC4F37EE7ULL);

    JsonObject confidenceIntervals;
    JsonObject waitCiObj;
    waitCiObj.emplace("metric", JsonValue(std::string("wait_time_minutes")));
    waitCiObj.emplace("mean", JsonValue(waitCi.mean));
    waitCiObj.emplace("lower", JsonValue(waitCi.lower));
    waitCiObj.emplace("upper", JsonValue(waitCi.upper));
    waitCiObj.emplace("sample_count", JsonValue(waitCi.sampleCount));
    waitCiObj.emplace("alpha", JsonValue(0.05));
    confidenceIntervals.emplace("wait_time_minutes", JsonValue(std::move(waitCiObj)));
    root.emplace("confidence_intervals", JsonValue(std::move(confidenceIntervals)));

    BottleneckResult bottleneck = computeBottleneck(report.path("summary.timeline"));
    JsonObject bottleneckObj;
    bottleneckObj.emplace("score", JsonValue(bottleneck.score));
    bottleneckObj.emplace("gini_coefficient", JsonValue(bottleneck.giniCoefficient));
    bottleneckObj.emplace("worst_window_id", JsonValue(bottleneck.worstWindowId));
    bottleneckObj.emplace("sustained_peak_minutes", JsonValue(bottleneck.sustainedPeakMinutes));
    root.emplace("bottleneck", JsonValue(std::move(bottleneckObj)));

    JsonObject headline;
    headline.emplace("typical_wait_time_minutes",
                     JsonValue(report.path("summary.wait_time_metrics.typical_wait_time_minutes").asNumber()));
    headline.emplace("seat_utilization_rate",
                     JsonValue(report.path("summary.seat_utilization_rate").asNumber()));
    headline.emplace("takeaway_rate",
                     JsonValue(report.path("summary.takeaway_rate").asNumber()));
    headline.emplace("served_count",
                     JsonValue(report.path("summary.served_count").asLong()));
    headline.emplace("abandoned_count",
                     JsonValue(report.path("summary.abandoned_count").asLong()));
    root.emplace("headline_metrics", JsonValue(std::move(headline)));

    return JsonValue(std::move(root));
}

JsonValue analyzeBatch(const std::vector<JsonValue>& reports,
                       const std::vector<std::string>& reportLabels) {
    JsonObject root;
    root.emplace("schema_version", JsonValue(std::string("1.0")));
    root.emplace("report_count", JsonValue(static_cast<long long>(reports.size())));

    std::vector<double> waitMeans;
    std::vector<double> seatUtils;
    std::vector<double> takeawayRates;
    std::vector<double> arrivedCounts;
    std::vector<std::vector<double>> waitGroups;
    std::vector<std::string> labels = reportLabels;
    labels.resize(reports.size());

    for (std::size_t i = 0; i < reports.size(); ++i) {
        const auto& r = reports[i];
        waitMeans.push_back(r.path("summary.wait_time_metrics.typical_wait_time_minutes").asNumber());
        seatUtils.push_back(r.path("summary.seat_utilization_rate").asNumber());
        takeawayRates.push_back(r.path("summary.takeaway_rate").asNumber());
        arrivedCounts.push_back(r.path("summary.arrived_count").asNumber());
        auto group = extractWaitTimeMinutes(r);
        if (!group.empty()) waitGroups.push_back(std::move(group));
        if (labels[i].empty()) labels[i] = "report_" + std::to_string(i);
    }

    auto pushAggregate = [&](JsonObject& parent, const char* name, const std::vector<double>& xs) {
        JsonObject obj;
        obj.emplace("metric", JsonValue(std::string(name)));
        obj.emplace("mean", JsonValue(mean(xs)));
        obj.emplace("stddev", JsonValue(stddev(xs)));
        if (!xs.empty()) {
            obj.emplace("min", JsonValue(*std::min_element(xs.begin(), xs.end())));
            obj.emplace("max", JsonValue(*std::max_element(xs.begin(), xs.end())));
        }
        obj.emplace("report_count", JsonValue(static_cast<long long>(xs.size())));
        parent.emplace(name, JsonValue(std::move(obj)));
    };

    JsonObject monteCarlo;
    pushAggregate(monteCarlo, "typical_wait_time_minutes", waitMeans);
    pushAggregate(monteCarlo, "seat_utilization_rate", seatUtils);
    pushAggregate(monteCarlo, "takeaway_rate", takeawayRates);
    pushAggregate(monteCarlo, "arrived_count", arrivedCounts);
    root.emplace("monte_carlo", JsonValue(std::move(monteCarlo)));

    AnovaResult anova = computeAnova(waitGroups, labels);
    JsonObject anovaObj;
    anovaObj.emplace("enabled", JsonValue(anova.enabled));
    anovaObj.emplace("f_statistic", JsonValue(anova.fStatistic));
    anovaObj.emplace("between_group_variance", JsonValue(anova.betweenGroupVariance));
    anovaObj.emplace("within_group_variance", JsonValue(anova.withinGroupVariance));
    anovaObj.emplace("group_count", JsonValue(anova.groupCount));
    anovaObj.emplace("total_samples", JsonValue(anova.totalSamples));
    anovaObj.emplace("strongest_group", JsonValue(anova.strongestGroup));
    anovaObj.emplace("weakest_group", JsonValue(anova.weakestGroup));
    root.emplace("anova", JsonValue(std::move(anovaObj)));

    return JsonValue(std::move(root));
}

}  // namespace analysis
