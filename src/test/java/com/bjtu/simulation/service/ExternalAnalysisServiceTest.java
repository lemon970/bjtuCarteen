package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.bjtu.simulation.config.AppBeansConfig;
import com.bjtu.simulation.service.ExternalAnalysisService.AnalysisResult;
import com.bjtu.simulation.service.ExternalAnalysisService.ProcessExecutionOutcome;
import com.bjtu.simulation.service.ExternalAnalysisService.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAnalysisServiceTest {

    private final ObjectMapper mapper = AppBeansConfig.createReportObjectMapper();

    @Test
    void shouldFallbackToJavaAnalyzerWhenBinaryMissing() {
        SimulationReportRepository repo = new StubRepo(true, Optional.of(mapper.createObjectNode().put("report_id", "rid")));
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReport("abc-123");

        assertTrue(result.isAvailable(), () -> "expected fallback available, reason=" + result.getReason());
        assertNotNull(result.getPayload());
        assertEquals("java-internal", result.getPayload().path("computed_by").asText());
        assertEquals("1.0", result.getPayload().path("schema_version").asText());
        assertTrue(result.getPayload().has("headline_metrics"));
    }

    @Test
    void shouldReturnUnavailableWhenReportIdInvalid() {
        SimulationReportRepository repo = new StubRepo(false, Optional.empty());
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReport("../traversal");

        assertFalse(result.isAvailable());
        assertEquals("invalid report id", result.getReason());
    }

    @Test
    void shouldReturnAvailableWhenRunnerSucceeds(@TempDir Path tempDir) throws IOException {
        Path fakeBinary = Files.writeString(tempDir.resolve("canteen-analyze"), "stub");
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "rid")));

        RecordingRunner runner = new RecordingRunner(0, false, "");
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, fakeBinary, runner);

        AnalysisResult result = service.runForReport("rid-1");

        assertTrue(result.isAvailable(), () -> "expected available, reason=" + result.getReason());
        assertNotNull(result.getPayload());
        assertEquals("1.0", result.getPayload().path("schema_version").asText());
        assertEquals("cpp-canteen-analyze", result.getPayload().path("computed_by").asText());
        // Verifies: binary path passed, mode=analyze, --input/--output present.
        assertTrue(runner.lastCommand.get(0).endsWith("canteen-analyze"));
        assertEquals("--mode=analyze", runner.lastCommand.get(1));
        assertTrue(runner.lastCommand.get(2).startsWith("--input="));
        assertTrue(runner.lastCommand.get(3).startsWith("--output="));
    }

    @Test
    void shouldReturnUnavailableWhenRunnerExitsNonZero(@TempDir Path tempDir) throws IOException {
        Path fakeBinary = Files.writeString(tempDir.resolve("canteen-analyze"), "stub");
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "rid")));
        RecordingRunner runner = new RecordingRunner(5, false, "parse failed");
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, fakeBinary, runner);

        AnalysisResult result = service.runForReport("rid-1");

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("exited with code 5"));
        assertTrue(result.getReason().contains("parse failed"));
    }

    @Test
    void shouldReturnUnavailableWhenRunnerTimesOut(@TempDir Path tempDir) throws IOException {
        Path fakeBinary = Files.writeString(tempDir.resolve("canteen-analyze"), "stub");
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "rid")));
        RecordingRunner runner = new RecordingRunner(-1, true, "");
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, fakeBinary, runner);

        AnalysisResult result = service.runForReport("rid-1");

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("timed out"));
    }

    // C1: runForReports 成功路径 - batch 调用 C++ binary 写 cross-scenario.json
    @Test
    void runForReportsShouldInvokeBatchModeAndWrapPayload(@TempDir Path tempDir) throws IOException {
        Path fakeBinary = Files.writeString(tempDir.resolve("canteen-analyze"), "stub");
        Map<String, Optional<JsonNode>> reports = new HashMap<>();
        reports.put("rid-A", Optional.of(mapper.createObjectNode().put("report_id", "rid-A")));
        reports.put("rid-B", Optional.of(mapper.createObjectNode().put("report_id", "rid-B")));
        MapRepo repo = new MapRepo(id -> true, reports);

        RecordingRunner runner = new RecordingRunner(0, false, "");
        ExternalAnalysisService service = new ExternalAnalysisService(repo, mapper, fakeBinary, runner);

        AnalysisResult result = service.runForReports(List.of("rid-A", "rid-B"));

        assertTrue(result.isAvailable(), () -> "expected batch available, reason=" + result.getReason());
        assertEquals("cpp-canteen-analyze", result.getPayload().path("computed_by").asText());
        assertTrue(runner.lastCommand.get(1).equals("--mode=batch-analyze"),
                "command must use batch-analyze mode, got: " + runner.lastCommand);
        assertTrue(runner.lastCommand.get(2).startsWith("--input-dir="),
                "command must pass --input-dir=, got: " + runner.lastCommand);
        assertTrue(runner.lastCommand.get(3).startsWith("--output="));
    }

    // C2: < 2 ids 立即拒绝
    @Test
    void runForReportsWithSingleIdShouldRejectAtLeastTwo() {
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "x")));
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReports(List.of("only-one"));

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("at least 2"),
                () -> "expected 'at least 2' reason, got: " + result.getReason());
    }

    // C3: batch 含非法 id - 路径遍历防护
    @Test
    void runForReportsShouldRejectInvalidReportIdInBatch() {
        Map<String, Optional<JsonNode>> reports = new HashMap<>();
        reports.put("good", Optional.of(mapper.createObjectNode().put("report_id", "good")));
        Predicate<String> safeCheck = id -> id != null && !id.contains("..") && !id.contains("/");
        MapRepo repo = new MapRepo(safeCheck, reports);

        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReports(List.of("good", "../bad"));

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("invalid report id"),
                () -> "expected invalid id reason, got: " + result.getReason());
        assertTrue(result.getReason().contains("../bad"),
                () -> "expected the offending id to be named, got: " + result.getReason());
    }

    // C4: < 2 readable - 报告丢失/损坏
    @Test
    void runForReportsShouldFailWhenFewerThanTwoReportsReadable() {
        Map<String, Optional<JsonNode>> reports = new HashMap<>();
        reports.put("present", Optional.of(mapper.createObjectNode().put("report_id", "present")));
        reports.put("missing", Optional.empty());
        MapRepo repo = new MapRepo(id -> true, reports);

        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReports(List.of("present", "missing"));

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("fewer than 2 readable"),
                () -> "expected 'fewer than 2 readable' reason, got: " + result.getReason());
    }

    // C5: binary 缺失 - batch 路径 fallback 到 Java analyzer
    @Test
    void runForReportsShouldFallbackToJavaWhenBinaryMissing() {
        Map<String, Optional<JsonNode>> reports = new HashMap<>();
        reports.put("rid-A", Optional.of(mapper.createObjectNode().put("report_id", "rid-A")));
        reports.put("rid-B", Optional.of(mapper.createObjectNode().put("report_id", "rid-B")));
        MapRepo repo = new MapRepo(id -> true, reports);

        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, Path.of("does-not-exist"), failingRunner());

        AnalysisResult result = service.runForReports(List.of("rid-A", "rid-B"));

        assertTrue(result.isAvailable(), () -> "expected fallback available, reason=" + result.getReason());
        assertEquals("java-internal", result.getPayload().path("computed_by").asText());
    }

    // C6: binary 写出文件存在但是数组而非对象 - schema 漂移防护
    @Test
    void shouldReturnUnavailableWhenBinaryOutputIsNotJsonObject(@TempDir Path tempDir) throws IOException {
        Path fakeBinary = Files.writeString(tempDir.resolve("canteen-analyze"), "stub");
        SimulationReportRepository repo = new StubRepo(true,
                Optional.of(mapper.createObjectNode().put("report_id", "rid")));
        ProcessRunner arrayWriter = (command, timeout, unit) -> {
            String output = command.stream()
                    .filter(arg -> arg.startsWith("--output="))
                    .findFirst()
                    .orElseThrow()
                    .substring("--output=".length());
            Files.writeString(Path.of(output), "[1,2,3]");
            return new ProcessExecutionOutcome(false, 0, "");
        };
        ExternalAnalysisService service = new ExternalAnalysisService(
                repo, mapper, fakeBinary, arrayWriter);

        AnalysisResult result = service.runForReport("rid-1");

        assertFalse(result.isAvailable());
        assertTrue(result.getReason().contains("not a json object"),
                () -> "expected 'not a json object' reason, got: " + result.getReason());
    }

    private ProcessRunner failingRunner() {
        return (command, timeout, unit) -> { throw new AssertionError("runner should not be invoked"); };
    }

    private final class RecordingRunner implements ProcessRunner {
        private final int exitCode;
        private final boolean timeout;
        private final String stderr;
        private List<String> lastCommand;

        RecordingRunner(int exitCode, boolean timeout, String stderr) {
            this.exitCode = exitCode;
            this.timeout = timeout;
            this.stderr = stderr;
        }

        @Override
        public ProcessExecutionOutcome run(List<String> command, long t, TimeUnit unit) throws IOException {
            this.lastCommand = command;
            // Honour the contract: on success, the binary writes the analysis JSON to --output.
            if (!timeout && exitCode == 0) {
                String output = command.stream()
                        .filter(arg -> arg.startsWith("--output="))
                        .findFirst()
                        .orElseThrow()
                        .substring("--output=".length());
                JsonNode payload = mapper.createObjectNode()
                        .put("schema_version", "1.0")
                        .put("source_report_id", "rid-1");
                Files.writeString(Path.of(output), mapper.writeValueAsString(payload));
            }
            return new ProcessExecutionOutcome(timeout, exitCode, stderr);
        }
    }

    private static final class StubRepo extends SimulationReportRepository {
        private final boolean validId;
        private final Optional<JsonNode> stored;

        StubRepo(boolean validId, Optional<JsonNode> stored) {
            super();
            this.validId = validId;
            this.stored = stored;
        }

        @Override
        public boolean isSafeReportId(String reportId) {
            return validId;
        }

        @Override
        public Optional<JsonNode> readById(String reportId) {
            return stored;
        }
    }

    private static final class MapRepo extends SimulationReportRepository {
        private final Predicate<String> safeCheck;
        private final Map<String, Optional<JsonNode>> stored;

        MapRepo(Predicate<String> safeCheck, Map<String, Optional<JsonNode>> stored) {
            super();
            this.safeCheck = safeCheck;
            this.stored = stored;
        }

        @Override
        public boolean isSafeReportId(String reportId) {
            return safeCheck.test(reportId);
        }

        @Override
        public Optional<JsonNode> readById(String reportId) {
            return stored.getOrDefault(reportId, Optional.empty());
        }
    }
}
