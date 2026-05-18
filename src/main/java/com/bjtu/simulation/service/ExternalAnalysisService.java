package com.bjtu.simulation.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bridges the Java backend to the C++ statistical analysis CLI shipped under
 * {@code dataAnalyze/bin}. The binary may be absent in CI / on dev machines that
 * skipped the C++ build — in that case the service returns
 * {@code AnalysisResult.unavailable(...)} so callers can degrade gracefully
 * instead of bubbling a 5xx.
 */
@Service
public class ExternalAnalysisService {

    private static final long PROCESS_TIMEOUT_SECONDS = 30L;
    private static final Path DEFAULT_BINARY_DIR = Paths.get("dataAnalyze", "bin");

    private final SimulationReportRepository reportRepository;
    private final ObjectMapper reportMapper;
    private final Path binaryPath;
    private final ProcessRunner processRunner;
    private final InternalStatisticsAnalyzer fallback;

    @Autowired
    public ExternalAnalysisService(SimulationReportRepository reportRepository,
                                   InternalStatisticsAnalyzer fallback) {
        this(reportRepository,
                AppBeansConfig.createReportObjectMapper(),
                resolveDefaultBinary(),
                new DefaultProcessRunner(),
                fallback);
    }

    public ExternalAnalysisService(SimulationReportRepository reportRepository,
                                   ObjectMapper reportMapper,
                                   Path binaryPath,
                                   ProcessRunner processRunner) {
        this(reportRepository, reportMapper, binaryPath, processRunner,
                new InternalStatisticsAnalyzer(reportMapper));
    }

    public ExternalAnalysisService(SimulationReportRepository reportRepository,
                                   ObjectMapper reportMapper,
                                   Path binaryPath,
                                   ProcessRunner processRunner,
                                   InternalStatisticsAnalyzer fallback) {
        this.reportRepository = Objects.requireNonNull(reportRepository);
        this.reportMapper = Objects.requireNonNull(reportMapper);
        this.binaryPath = binaryPath;
        this.processRunner = Objects.requireNonNull(processRunner);
        this.fallback = Objects.requireNonNull(fallback);
    }

    public AnalysisResult runForReport(String reportId) {
        if (!reportRepository.isSafeReportId(reportId)) {
            return AnalysisResult.unavailable("invalid report id");
        }
        var maybeReport = reportRepository.readById(reportId);
        if (maybeReport.isEmpty()) {
            return AnalysisResult.unavailable("report not found: " + reportId);
        }
        if (!isBinaryAvailable()) {
            return AnalysisResult.available(fallback.analyze(maybeReport.get()));
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("canteen-analyze-");
            Path inputFile = tempDir.resolve(reportId + ".json");
            Path outputFile = tempDir.resolve(reportId + "-analysis.json");
            Files.writeString(inputFile,
                    reportMapper.writeValueAsString(maybeReport.get()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            List<String> command = List.of(
                    binaryPath.toAbsolutePath().toString(),
                    "--mode=analyze",
                    "--input=" + inputFile.toAbsolutePath(),
                    "--output=" + outputFile.toAbsolutePath());
            ProcessExecutionOutcome outcome = processRunner.run(command, PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return interpret(outcome, outputFile, "cpp-canteen-analyze");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return AnalysisResult.unavailable("analysis invocation failed: " + e.getMessage());
        } finally {
            cleanup(tempDir);
        }
    }

    public AnalysisResult runForReports(List<String> reportIds) {
        if (reportIds == null || reportIds.size() < 2) {
            return AnalysisResult.unavailable("batch analyze requires at least 2 report ids");
        }
        for (String id : reportIds) {
            if (!reportRepository.isSafeReportId(id)) {
                return AnalysisResult.unavailable("invalid report id in batch: " + id);
            }
        }
        List<JsonNode> resolved = new java.util.ArrayList<>();
        for (String id : reportIds) {
            reportRepository.readById(id).ifPresent(resolved::add);
        }
        if (resolved.size() < 2) {
            return AnalysisResult.unavailable("fewer than 2 readable reports for batch analysis");
        }
        if (!isBinaryAvailable()) {
            return AnalysisResult.available(fallback.batchAnalyze(resolved));
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("canteen-batch-");
            Path inputDir = Files.createDirectory(tempDir.resolve("inputs"));
            int written = 0;
            for (int i = 0; i < resolved.size(); i++) {
                Files.writeString(inputDir.resolve(reportIds.get(i) + ".json"),
                        reportMapper.writeValueAsString(resolved.get(i)),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                ++written;
            }
            if (written < 2) {
                return AnalysisResult.unavailable("fewer than 2 readable reports for batch analysis");
            }
            Path outputFile = tempDir.resolve("cross-scenario.json");
            List<String> command = List.of(
                    binaryPath.toAbsolutePath().toString(),
                    "--mode=batch-analyze",
                    "--input-dir=" + inputDir.toAbsolutePath(),
                    "--output=" + outputFile.toAbsolutePath());
            ProcessExecutionOutcome outcome = processRunner.run(command, PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return interpret(outcome, outputFile, "cpp-canteen-analyze");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return AnalysisResult.unavailable("analysis invocation failed: " + e.getMessage());
        } finally {
            cleanup(tempDir);
        }
    }

    private boolean isBinaryAvailable() {
        return binaryPath != null && Files.isRegularFile(binaryPath);
    }

    private AnalysisResult interpret(ProcessExecutionOutcome outcome, Path outputFile, String computedBy) throws IOException {
        if (outcome.timedOut()) {
            return AnalysisResult.unavailable("analysis binary timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }
        if (outcome.exitCode() != 0) {
            return AnalysisResult.unavailable("analysis binary exited with code " + outcome.exitCode()
                    + (outcome.stderr().isEmpty() ? "" : ": " + outcome.stderr().strip()));
        }
        if (!Files.isRegularFile(outputFile)) {
            return AnalysisResult.unavailable("analysis binary produced no output file");
        }
        JsonNode payload = reportMapper.readTree(outputFile.toFile());
        if (!payload.isObject()) {
            return AnalysisResult.unavailable("analysis output is not a json object");
        }
        ObjectNode object = (ObjectNode) payload;
        if (!object.has("computed_by")) {
            object.put("computed_by", computedBy);
        }
        return AnalysisResult.available(object);
    }

    private void cleanup(Path tempDir) {
        if (tempDir == null) return;
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* leave behind */ }
            });
        } catch (IOException ignored) {
            // tmp dir cleanup is best-effort.
        }
    }

    private static Path resolveDefaultBinary() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String exe = os.contains("win") ? "canteen-analyze.exe" : "canteen-analyze";
        return DEFAULT_BINARY_DIR.resolve(exe);
    }

    public Path getBinaryPath() {
        return binaryPath;
    }

    public interface ProcessRunner {
        ProcessExecutionOutcome run(List<String> command, long timeout, TimeUnit unit) throws IOException, InterruptedException;
    }

    private static final class DefaultProcessRunner implements ProcessRunner {
        @Override
        public ProcessExecutionOutcome run(List<String> command, long timeout, TimeUnit unit)
                throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            // StringBuffer (vs StringBuilder) 因为 errReader 线程在 join 超时后仍可能 append，
            // 主线程随后调用 toString() 形成跨线程读写；StringBuffer 内置同步保证原子性。
            StringBuffer stderr = new StringBuffer();
            Thread errReader = new Thread(() -> {
                try (var in = process.getErrorStream()) {
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) stderr.append(new String(buf, 0, n));
                } catch (IOException ignored) { /* end-of-stream */ }
            }, "canteen-analyze-stderr");
            errReader.setDaemon(true);
            errReader.start();
            boolean finished = process.waitFor(timeout, unit);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessExecutionOutcome(true, -1, stderr.toString());
            }
            errReader.join(1_000L);
            return new ProcessExecutionOutcome(false, process.exitValue(), stderr.toString());
        }
    }

    public record ProcessExecutionOutcome(boolean timedOut, int exitCode, String stderr) {}

    public static final class AnalysisResult {
        private final boolean available;
        private final ObjectNode payload;
        private final String reason;

        private AnalysisResult(boolean available, ObjectNode payload, String reason) {
            this.available = available;
            this.payload = payload;
            this.reason = reason;
        }
        public static AnalysisResult available(ObjectNode payload) { return new AnalysisResult(true, payload, null); }
        public static AnalysisResult unavailable(String reason) { return new AnalysisResult(false, null, reason); }
        public boolean isAvailable() { return available; }
        public ObjectNode getPayload() { return payload; }
        public String getReason() { return reason; }

        public Map<String, Object> toEnvelope() {
            return available
                    ? Map.of("available", true, "data", payload)
                    : Map.of("available", false, "reason", reason == null ? "unknown" : reason);
        }
    }
}
