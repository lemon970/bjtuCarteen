// Canteen analysis CLI.
// Subcommands:
//   --mode=simulate                                 (legacy demo, kept for reference)
//   --mode=analyze --input=PATH --output=PATH       (single-report bootstrap CI + bottleneck)
//   --mode=batch-analyze --input-dir=DIR --output=PATH (cross-report Monte Carlo + ANOVA)
//
// Errors go to stderr; on success the program writes the analysis JSON to --output
// (or stdout when --output is omitted) and returns 0. Non-zero exit signals failure.
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "AnalysisCore.h"
#include "DiningSimulation.h"
#include "JsonUtil.h"

namespace fs = std::filesystem;

namespace {

struct CliArgs {
    std::string mode;
    std::string input;
    std::string output;
    std::string inputDir;
};

CliArgs parseArgs(int argc, char** argv) {
    CliArgs cli;
    auto stripPrefix = [](const std::string& arg, const char* prefix) -> std::string {
        std::string p = prefix;
        if (arg.compare(0, p.size(), p) == 0) return arg.substr(p.size());
        return {};
    };
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (auto v = stripPrefix(arg, "--mode="); !v.empty()) cli.mode = v;
        else if (auto v = stripPrefix(arg, "--input="); !v.empty()) cli.input = v;
        else if (auto v = stripPrefix(arg, "--output="); !v.empty()) cli.output = v;
        else if (auto v = stripPrefix(arg, "--input-dir="); !v.empty()) cli.inputDir = v;
    }
    return cli;
}

int writeResult(const analysis::JsonValue& value, const std::string& outputPath) {
    std::string body = analysis::JsonWriter::write(value, true);
    if (outputPath.empty()) {
        std::cout << body << std::endl;
        return 0;
    }
    fs::path p(outputPath);
    if (p.has_parent_path()) {
        std::error_code ec;
        fs::create_directories(p.parent_path(), ec);
    }
    std::ofstream out(outputPath, std::ios::binary);
    if (!out) {
        std::cerr << "cannot write output file: " << outputPath << std::endl;
        return 4;
    }
    out << body;
    return 0;
}

int runAnalyze(const CliArgs& cli) {
    if (cli.input.empty()) {
        std::cerr << "--input is required for --mode=analyze" << std::endl;
        return 2;
    }
    try {
        auto report = analysis::JsonParser::parseFile(cli.input);
        auto analysisResult = analysis::analyzeReport(report);
        return writeResult(analysisResult, cli.output);
    } catch (const std::exception& ex) {
        std::cerr << "analyze failed: " << ex.what() << std::endl;
        return 5;
    }
}

int runBatchAnalyze(const CliArgs& cli) {
    if (cli.inputDir.empty()) {
        std::cerr << "--input-dir is required for --mode=batch-analyze" << std::endl;
        return 2;
    }
    fs::path dir(cli.inputDir);
    if (!fs::is_directory(dir)) {
        std::cerr << "input dir not found: " << cli.inputDir << std::endl;
        return 3;
    }
    std::vector<analysis::JsonValue> reports;
    std::vector<std::string> labels;
    for (const auto& entry : fs::directory_iterator(dir)) {
        if (!entry.is_regular_file()) continue;
        const auto& path = entry.path();
        if (path.extension() != ".json") continue;
        std::string name = path.filename().string();
        if (name.find("analysis") != std::string::npos) continue;  // skip prior analysis outputs
        try {
            reports.push_back(analysis::JsonParser::parseFile(path.string()));
            labels.push_back(name);
        } catch (const std::exception& ex) {
            std::cerr << "skipping " << name << ": " << ex.what() << std::endl;
        }
    }
    if (reports.size() < 2) {
        std::cerr << "batch-analyze requires at least 2 valid reports, found " << reports.size() << std::endl;
        return 6;
    }
    auto batchResult = analysis::analyzeBatch(reports, labels);
    return writeResult(batchResult, cli.output);
}

int runLegacySimulate() {
    DiningSimulation simulation;
    SimulationConfig config;
    config.simulationName = "cli-demo";
    config.duration = 1.0;
    config.arrivalRate = 60.0;
    config.queueLimit = 10;
    config.packProbability = 0.2;
    config.seed = 42;
    config.baseConfig.windowCount = 4;
    config.baseConfig.totalSeats = 40;
    config.weatherConfig.currentWeather = "sunny";
    config.weatherConfig.weatherImpactFactor = 1.0;
    config.randomBounds.serviceRange = { 60, 180 };
    config.randomBounds.diningRange = { 600, 1200 };
    simulation.configure(config);
    simulation.runSimulation();
    SimulationReport report = simulation.getReport();
    simulation.printReport();
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    CliArgs cli = parseArgs(argc, argv);
    if (cli.mode.empty() || cli.mode == "simulate") {
        return runLegacySimulate();
    }
    if (cli.mode == "analyze") return runAnalyze(cli);
    if (cli.mode == "batch-analyze") return runBatchAnalyze(cli);
    std::cerr << "unknown --mode: " << cli.mode << std::endl;
    std::cerr << "valid modes: simulate | analyze | batch-analyze" << std::endl;
    return 1;
}
