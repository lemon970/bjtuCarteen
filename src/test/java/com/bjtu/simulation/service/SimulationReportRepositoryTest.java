package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D1 isSafeReportId(纯函数,完整参数化覆盖) + D2 listReports envelope 形状(真实 reports/ 平滑路径)。
 * D3(损坏 JSON 标 parse_error)需要将 REPORTS_DIR 改为可注入,属于 production 重构,
 * 不在本轮"只补测试,不改代码"范围内,留作后续轮次。
 */
class SimulationReportRepositoryTest {

    private final SimulationReportRepository repository = new SimulationReportRepository();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "x/y", "a b", "../escape", "报告", "name with space", "id\\bad", "name@home", "with#hash"})
    void isSafeReportIdShouldRejectInvalidIds(String reportId) {
        assertFalse(repository.isSafeReportId(reportId),
                () -> "expected " + reportId + " to be rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "abc-1.0_X", "Z9", "report-id_with.dots", "UUID-1234abcd"})
    void isSafeReportIdShouldAcceptValidIds(String reportId) {
        assertTrue(repository.isSafeReportId(reportId),
                () -> "expected " + reportId + " to be accepted");
    }

    @Test
    void listReportsShouldReturnEnvelopeShapeAndNotThrow() {
        // 兼容两种状态:reports/ 不存在或存在(测试环境是真实工作目录,
        // 上下游 mvn test 时通常已有历史报告)。无论哪种都应返回结构良好的 envelope。
        JsonNode envelope = assertDoesNotThrow(() -> repository.listReports());

        assertNotNull(envelope);
        assertTrue(envelope.isObject(), "envelope must be a JSON object");
        assertTrue(envelope.has("count"), "envelope must expose 'count'");
        assertTrue(envelope.path("count").isInt(), "'count' must be an integer");
        assertTrue(envelope.has("reports"), "envelope must expose 'reports'");
        assertTrue(envelope.path("reports").isArray(), "'reports' must be a JSON array");
        // count 应该等于 reports 数组长度
        int count = envelope.path("count").asInt();
        int actualSize = envelope.path("reports").size();
        assertTrue(count == actualSize,
                () -> "count=" + count + " must match reports[].size=" + actualSize);
    }
}
