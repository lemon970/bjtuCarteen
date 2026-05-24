package com.bjtu.simulation.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D1 isSafeReportId(纯函数,完整参数化覆盖)。
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
}
