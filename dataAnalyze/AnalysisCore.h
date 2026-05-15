// Statistical post-processing for SimulationReport JSON files.
// Inputs come from the Java backend; outputs feed the AnalysisController.
//
// Algorithms:
//   - Bootstrap 95% CI for wait-time / utilisation / takeaway-rate
//   - Gini-coefficient bottleneck score over per-window queue lengths
//   - Monte-Carlo aggregation across multiple seeds (mean / std / min / max)
//   - One-way ANOVA across scenarios (typical wait time)
#ifndef ANALYSIS_CORE_H
#define ANALYSIS_CORE_H

#include <string>
#include <vector>

#include "JsonUtil.h"

namespace analysis {

struct ConfidenceInterval {
    double mean = 0.0;
    double lower = 0.0;
    double upper = 0.0;
    int sampleCount = 0;
};

struct BottleneckResult {
    double score = 0.0;          // 0..100
    double giniCoefficient = 0.0;
    int worstWindowId = -1;
    int sustainedPeakMinutes = 0;
};

struct MonteCarloAggregate {
    std::string metricName;
    double mean = 0.0;
    double stddev = 0.0;
    double min = 0.0;
    double max = 0.0;
    int reportCount = 0;
};

struct AnovaResult {
    bool enabled = false;
    double fStatistic = 0.0;
    double betweenGroupVariance = 0.0;
    double withinGroupVariance = 0.0;
    int groupCount = 0;
    int totalSamples = 0;
    std::string strongestGroup;  // scenario id with highest mean
    std::string weakestGroup;    // scenario id with lowest mean
};

// Single-report analysis: bootstrap CIs + bottleneck + reproduction of headline metrics.
JsonValue analyzeReport(const JsonValue& report);

// Cross-report analysis: monte-carlo aggregation + ANOVA.
JsonValue analyzeBatch(const std::vector<JsonValue>& reports,
                       const std::vector<std::string>& reportLabels);

// --- Pure-math primitives (exposed for unit tests / reuse) ---
ConfidenceInterval bootstrapCi(const std::vector<double>& samples,
                               int iterations,
                               double alpha,
                               unsigned long long seed);
BottleneckResult computeBottleneck(const JsonValue& timeline);
AnovaResult computeAnova(const std::vector<std::vector<double>>& groups,
                         const std::vector<std::string>& labels);

}  // namespace analysis

#endif  // ANALYSIS_CORE_H
