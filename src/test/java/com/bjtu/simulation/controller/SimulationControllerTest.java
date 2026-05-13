package com.bjtu.simulation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bjtu.simulation.dto.ApiResponse;
import com.bjtu.simulation.dto.SimConfig;
import com.bjtu.simulation.engine.SeatSearchEvent;
import com.bjtu.simulation.engine.ServiceFinishEvent;
import com.bjtu.simulation.engine.SimulationEngine;
import com.bjtu.simulation.engine.StudentLeaveEvent;
import com.bjtu.simulation.model.ArrivalGroup;
import com.fasterxml.jackson.databind.JsonNode;

class SimulationControllerTest {

    private SimulationController controller;

    @BeforeEach
    void setUp() {
        controller = new SimulationController();
    }

    @Test
    void noTrafficShouldProduceZeroCounts() {
        SimConfig config = baseConfig();
        config.setArrivalRate(0);
        config.getBaseConfig().setTotalStudents(0);

        JsonNode summary = runAndGetSummary(config);

        assertEquals(0, summary.path("arrived_count").asInt());
        assertEquals(0, summary.path("abandoned_count").asInt());
        assertEquals(0, summary.path("served_count").asInt());
        assertEquals(0, summary.path("dine_in_count").asInt());
        assertEquals(0, summary.path("takeaway_count").asInt());
        assertEquals(0, summary.path("pending_seat_decision_count").asInt());
        assertEquals(0, summary.path("leave_count").asInt());
    }

    @Test
    void sameSeedShouldProduceDeterministicSummary() {
        SimConfig firstConfig = highLoadConfig();
        firstConfig.setSeed(20260414L);

        SimConfig secondConfig = highLoadConfig();
        secondConfig.setSeed(20260414L);

        JsonNode first = runAndGetSummary(firstConfig);
        JsonNode second = runAndGetSummary(secondConfig);

        assertEquals(first.path("arrived_count").asInt(), second.path("arrived_count").asInt());
        assertEquals(first.path("abandoned_count").asInt(), second.path("abandoned_count").asInt());
        assertEquals(first.path("served_count").asInt(), second.path("served_count").asInt());
        assertEquals(first.path("dine_in_count").asInt(), second.path("dine_in_count").asInt());
        assertEquals(first.path("takeaway_count").asInt(), second.path("takeaway_count").asInt());
        assertEquals(first.path("max_queue_size").asInt(), second.path("max_queue_size").asInt());
        assertEquals(first.path("peak_window_id").asInt(), second.path("peak_window_id").asInt());
    }

    @Test
    void queueLimitZeroShouldForceAbandonment() {
        SimConfig config = baseConfig();
        config.setDuration(1.0);
        config.setArrivalRate(120);
        config.setQueueLimit(0);
        config.setSeed(123L);

        JsonNode summary = runAndGetSummary(config);

        int arrived = summary.path("arrived_count").asInt();
        int abandoned = summary.path("abandoned_count").asInt();
        int served = summary.path("served_count").asInt();

        assertTrue(arrived > 0);
        assertEquals(arrived, abandoned);
        assertEquals(0, served);
    }

    @Test
    void highLoadShouldRespectCoreInvariants() {
        JsonNode summary = runAndGetSummary(highLoadConfig());

        int arrived = summary.path("arrived_count").asInt();
        int served = summary.path("served_count").asInt();
        int dineIn = summary.path("dine_in_count").asInt();
        int takeaway = summary.path("takeaway_count").asInt();
        int pendingSeatDecision = summary.path("pending_seat_decision_count").asInt();
        int leave = summary.path("leave_count").asInt();
        int occupied = summary.path("occupied_seats").asInt();
        int total = summary.path("total_seats").asInt();
        int empty = summary.path("empty_seats").asInt();

        assertTrue(served <= arrived);
        assertEquals(served, dineIn + takeaway + pendingSeatDecision);
        assertEquals(0, pendingSeatDecision);
        assertEquals(served, leave);
        assertTrue(occupied >= 0);
        assertTrue(occupied <= total);
        assertTrue(empty >= 0);
        assertTrue(summary.path("timeline").isArray());
        assertTrue(summary.path("timeline").size() >= 1);
    }

    @Test
    void latestReportEndpointShouldReturnSuccessAfterRun() {
        runAndGetSummary(baseConfig());

        ResponseEntity<ApiResponse<JsonNode>> latest = controller.getLatestReport();
        assertEquals(HttpStatus.OK, latest.getStatusCode());
        assertNotNull(latest.getBody());
        assertEquals(0, latest.getBody().getCode());
        assertNotNull(latest.getBody().getData());
    }

    @Test
    void historyReportEndpointsShouldReturnListAndReportById() {
        JsonNode report = runAndGetReport(baseConfig());
        String reportId = report.path("report_id").asText();

        ResponseEntity<ApiResponse<JsonNode>> list = controller.getReportList();
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        assertEquals(0, list.getBody().getCode());
        assertTrue(list.getBody().getData().path("count").asInt() >= 1);
        assertTrue(list.getBody().getData().path("reports").isArray());

        ResponseEntity<ApiResponse<JsonNode>> found = controller.getReportById(reportId);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertNotNull(found.getBody());
        assertEquals(0, found.getBody().getCode());
        assertEquals(reportId, found.getBody().getData().path("report_id").asText());
    }

    @Test
    void classPeakModeShouldRedistributeArrivalsAndMarkClassPeakGroup() {
        SimConfig baseline = baseConfig();
        baseline.setDuration(0.5);
        baseline.setArrivalRate(60);

        SimConfig peak = baseConfig();
        peak.setDuration(0.5);
        peak.setArrivalRate(60);
        peak.getPeakConfig().setClassPeakEnabled(true);
        peak.getPeakConfig().setClassPeakStartMinute(15);
        peak.getPeakConfig().setClassPeakEndMinute(25);
        peak.getPeakConfig().setClassPeakMultiplier(6.0);

        JsonNode baselineSummary = runAndGetSummary(baseline);
        JsonNode peakSummary = runAndGetSummary(peak);

        assertEquals(baselineSummary.path("arrived_count").asInt(), peakSummary.path("arrived_count").asInt());
        assertTrue(peakSummary.path("class_peak_arrival_count").asInt() > 0);
    }

    @Test
    void overlappingClassPeakWindowsShouldRedistributeArrivalsAndBeReturnedInConfig() {
        SimConfig baseline = baseConfig();
        baseline.setDuration(0.6);
        baseline.setArrivalRate(60);

        SimConfig peak = baseConfig();
        peak.setDuration(0.6);
        peak.setArrivalRate(60);
        peak.getPeakConfig().setClassPeakEnabled(true);
        peak.getPeakConfig().setClassPeakWindows(java.util.List.of(
                new SimConfig.PeakConfig.PeakWindow(10, 20, 4.0),
                new SimConfig.PeakConfig.PeakWindow(18, 28, 5.0)));

        JsonNode baselineSummary = runAndGetSummary(baseline);
        JsonNode peakReport = runAndGetReport(peak);
        JsonNode peakSummary = peakReport.path("summary");

        assertEquals(baselineSummary.path("arrived_count").asInt(), peakSummary.path("arrived_count").asInt());
        assertTrue(peakSummary.path("class_peak_arrival_count").asInt() > 0);
        assertEquals(2, peakReport.path("config").path("peak_config").path("class_peak_windows").size());
    }

    @Test
    void arrivalRateShouldControlTotalArrivalsWhenLegacyLambdaDiffers() {
        SimConfig config = baseConfig();
        config.setDuration(2.0);
        config.setArrivalRate(300);
        config.getArrivalDist().setLambda(180);
        config.getBaseConfig().setTotalStudents(1000);
        config.getBaseConfig().setWindowCount(8);
        config.getBaseConfig().setTotalSeats(250);
        config.setQueueLimit(40);
        config.setSeed(20260512L);

        JsonNode report = runAndGetReport(config);
        JsonNode summary = report.path("summary");

        assertEquals(600, summary.path("arrived_count").asInt());
        assertEquals(300.0, report.path("config").path("arrival_dist").path("lambda").asDouble(), 0.000001);
    }

    @Test
    void probabilityModelShouldExposePoissonExponentialSamplesAndSeatGrid() {
        JsonNode summary = runAndGetSummary(highLoadConfig());

        JsonNode model = summary.path("probability_model");
        assertEquals("POISSON", model.path("arrival_count_distribution").asText());
        assertEquals("NEGATIVE_EXPONENTIAL", model.path("interarrival_distribution").asText());
        assertTrue(model.path("arrival_sample_count").asInt() > 0);
        assertTrue(summary.path("arrival_samples").isArray());
        assertTrue(summary.path("arrival_samples").get(0).path("interval_seconds").asLong() >= 0);
        assertTrue(summary.path("takeaway_decision_records").isArray());
        assertEquals(summary.path("total_seats").asInt(), summary.path("seat_cells").size());
        assertTrue(summary.path("seat_cells").get(0).has("row"));
        assertTrue(summary.path("seat_cells").get(0).has("column"));
        assertTrue(summary.path("seat_cells").get(0).has("status"));
    }

    @Test
    void fixedIntervalArrivalShouldReportFixedInterarrivalModel() {
        SimConfig config = baseConfig();
        config.setDuration(0.1);
        config.setArrivalRate(60);
        config.getRandomBounds().setArrivalInterval(60);
        config.getBaseConfig().setTotalStudents(3);

        JsonNode summary = runAndGetSummary(config);

        assertEquals("FIXED_INTERVAL", summary.path("probability_model").path("interarrival_distribution").asText());
        assertEquals(3, summary.path("arrived_count").asInt());
    }

    @Test
    void normalDemandShouldKeepTakeawayRateControlled() {
        SimConfig config = baseConfig();
        config.setDuration(0.5);
        config.setArrivalRate(120);
        config.setPackProbability(0.2);
        config.setQueueLimit(12);
        config.getBaseConfig().setWindowCount(5);
        config.getBaseConfig().setTakeawayWindowCount(0);
        config.getBaseConfig().setTotalSeats(80);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.1, 0.3));
        config.getRandomBounds().setServiceRange(java.util.List.of(30, 60));
        config.getRandomBounds().setDiningRange(java.util.List.of(600, 900));
        config.setSeed(20260512L);

        JsonNode summary = runAndGetSummary(config);

        assertTrue(summary.path("dine_in_count").asInt() > summary.path("takeaway_count").asInt());
        assertTrue(summary.path("takeaway_rate").asDouble() < 0.5);
        assertEquals(summary.path("served_count").asInt(), summary.path("leave_count").asInt());
    }

    @Test
    void sunnyHighPeakScenarioShouldMeetCountTakeawayAndSeatUtilizationTargets() {
        SimConfig config = baseConfig();
        config.setDuration(2.0);
        config.setArrivalRate(300);
        config.getArrivalDist().setLambda(180);
        config.setPackProbability(0.15);
        config.setQueueLimit(40);
        config.getBaseConfig().setWindowCount(8);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(1.2);
        config.getBaseConfig().setTotalSeats(250);
        config.getBaseConfig().setTotalStudents(1000);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.05, 0.20));
        config.getRandomBounds().setServiceRange(java.util.List.of(45, 180));
        config.getRandomBounds().setDiningRange(java.util.List.of(900, 2400));
        config.getPeakConfig().setClassPeakEnabled(true);
        config.getPeakConfig().setClassPeakWindows(java.util.List.of(
                new SimConfig.PeakConfig.PeakWindow(12, 24, 3.2),
                new SimConfig.PeakConfig.PeakWindow(34, 48, 2.4)));
        config.setSeed(20260512L);

        SimConfig.DistributionSpec service = new SimConfig.DistributionSpec();
        service.setType("NORMAL");
        service.setMean(90);
        service.setStd(22);
        service.setMin(45);
        service.setMax(180);
        config.setNormalServiceDist(service);
        config.setWindowServiceDist(service);

        SimConfig.DistributionSpec dining = new SimConfig.DistributionSpec();
        dining.setType("NORMAL");
        dining.setMean(1500);
        dining.setStd(250);
        dining.setMin(900);
        dining.setMax(2400);
        config.setDiningTimeDist(dining);

        JsonNode summary = runAndGetSummary(config);

        assertEquals(600, summary.path("arrived_count").asInt());
        assertTrue(summary.path("takeaway_rate").asDouble() >= 0.12);
        assertTrue(summary.path("takeaway_rate").asDouble() <= 0.20);
        assertTrue(summary.path("seat_utilization_rate").asDouble() >= 0.40);
        assertTrue(summary.path("seat_utilization_rate").asDouble() <= 0.70);
        assertTrue(summary.path("takeaway_decision_records").get(0).has("base_probability"));
        assertTrue(summary.path("takeaway_decision_records").get(0).has("decision_reason"));
    }

    @Test
    void csvExportShouldContainArrivalDecisionAndHistorySections() {
        JsonNode report = runAndGetReport(highLoadConfig());
        String reportId = report.path("report_id").asText();

        ResponseEntity<String> csv = controller.exportReportCsv(reportId);

        assertEquals(HttpStatus.OK, csv.getStatusCode());
        assertNotNull(csv.getBody());
        assertTrue(csv.getBody().contains("section,report_id,time_seconds"));
        assertTrue(csv.getBody().contains("arrival,"));
        assertTrue(csv.getBody().contains("takeaway_decision,"));
        assertTrue(csv.getBody().contains("history,"));
    }

    @Test
    void queuePressureShouldRaiseTakeawayDecisionProbability() {
        SimConfig config = baseConfig();
        config.setPackProbability(0.0);
        config.setQueueLimit(2);
        config.setSeed(4096L);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTotalSeats(20);

        SimulationEngine engine = new SimulationEngine(config);
        engine.recordArrival(ArrivalGroup.NORMAL);
        engine.getCanteenState().joinQueue(0);
        engine.getCanteenState().joinQueue(1);
        engine.getCanteenState().joinQueue(1);
        engine.getCanteenState().joinQueue(0);
        engine.getCanteenState().joinQueue(1);
        engine.scheduleEvent(new ServiceFinishEvent(0L, "student-feedback", 0, 0L));

        engine.runAll();

        assertEquals(1, engine.getServedCount());
        assertEquals(1, engine.getTakeawayDecisionRecords().size());
        assertTrue(engine.getTakeawayDecisionRecords().get(0).getFinalProbability() <= 0.75);
        assertTrue(engine.getTakeawayDecisionRecords().get(0).getQueuePressureFactor() > 0.0);
        assertTrue(!engine.getTakeawayDecisionRecords().get(0).getDecisionReason().isBlank());
    }

    @Test
    void dedicatedTakeawayWindowsShouldForceServedStudentsToTakeaway() {
        SimConfig config = baseConfig();
        config.setDuration(0.2);
        config.setArrivalRate(60);
        config.setPackProbability(1.0);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(2);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(1.0, 1.0));
        config.getRandomBounds().setServiceRange(java.util.List.of(30, 60));
        config.setSeed(20260421L);

        JsonNode summary = runAndGetSummary(config);

        assertEquals(2, summary.path("takeaway_window_count").asInt());
        assertEquals(0, summary.path("normal_window_count").asInt());
        assertEquals(summary.path("served_count").asInt(), summary.path("takeaway_count").asInt());
        assertEquals(summary.path("served_count").asInt(), summary.path("takeaway_window_served_count").asInt());
        assertEquals(summary.path("served_count").asInt(), summary.path("leave_count").asInt());
        assertEquals(0, summary.path("dine_in_count").asInt());
        assertEquals(1.0, summary.path("takeaway_rate").asDouble(), 0.000001);
        assertEquals(1.0, summary.path("takeaway_window_ratio").asDouble(), 0.000001);
        assertEquals(1.0, summary.path("takeaway_window_served_rate").asDouble(), 0.000001);
        assertEquals("TAKEAWAY", summary.path("window_types").get(0).asText());
    }

    @Test
    void takeawayWindowServiceMultiplierShouldIncreaseServiceTime() {
        SimConfig config = baseConfig();
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(1);
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(2.0);
        config.getRandomBounds().setServiceRange(java.util.List.of(60, 61));
        SimConfig.DistributionSpec fixedService = new SimConfig.DistributionSpec();
        fixedService.setType("FIXED");
        fixedService.setMean(60);
        config.setNormalServiceDist(fixedService);
        config.setWindowServiceDist(fixedService);

        SimulationEngine engine = new SimulationEngine(config);

        long normalServiceTime = engine.resolveServiceTimeSeconds(0);
        long takeawayServiceTime = engine.resolveServiceTimeSeconds(1);

        assertEquals(60L, normalServiceTime);
        assertEquals(120L, takeawayServiceTime);
    }

    @Test
    void takeawayWindowCountCannotExceedWindowCount() {
        SimConfig config = baseConfig();
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTakeawayWindowCount(3);

        try {
            controller.start(config);
        } catch (IllegalArgumentException ex) {
            assertEquals("takeawayWindowCount must be <= windowCount", ex.getMessage());
            return;
        }
        throw new AssertionError("Expected takeawayWindowCount validation failure");
    }

    @Test
    void takeawayServiceTimeMultiplierCannotBeLessThanOne() {
        SimConfig config = baseConfig();
        config.getBaseConfig().setTakeawayServiceTimeMultiplier(0.8);

        try {
            controller.start(config);
        } catch (IllegalArgumentException ex) {
            assertEquals("takeawayServiceTimeMultiplier must be >= 1", ex.getMessage());
            return;
        }
        throw new AssertionError("Expected takeawayServiceTimeMultiplier validation failure");
    }

    @Test
    void seatSearchShouldResolvePendingDecisionToTakeawayWhenNoSeatAvailable() {
        SimConfig config = baseConfig();
        config.getBaseConfig().setTotalSeats(0);
        SimulationEngine engine = new SimulationEngine(config);

        engine.recordArrival(ArrivalGroup.NORMAL);
        engine.recordWaitTime(0L);
        engine.recordWindowServed(0);
        engine.recordSeatDecisionPending();
        engine.scheduleEvent(new SeatSearchEvent(30L, "student-seat-search", 1));

        engine.runAll();

        assertEquals(1, engine.getServedCount());
        assertEquals(0, engine.getPendingSeatDecisionCount());
        assertEquals(1, engine.getTakeawayCount());
        assertEquals(1, engine.getNoSeatSwitchToTakeawayCount());
        assertEquals(1, engine.getLeaveCount());
    }

    @Test
    void studentLeaveShouldReleaseOccupiedSeat() {
        SimulationEngine engine = new SimulationEngine(baseConfig());

        assertTrue(engine.getCanteenState().tryOccupySeat());
        engine.recordArrival(ArrivalGroup.NORMAL);
        engine.recordWaitTime(0L);
        engine.recordWindowServed(0);
        engine.recordDineIn();
        engine.scheduleEvent(new StudentLeaveEvent(10L, "student-dine-in"));

        engine.runAll();

        assertEquals(0, engine.getCanteenState().getOccupiedSeats());
        assertEquals(1, engine.getLeaveCount());
    }

    @Test
    void groupArrivalShouldUseTableAllocationAndMovementTime() {
        SimConfig config = baseConfig();
        config.setDuration(0.05);
        config.setArrivalRate(0);
        config.setPackProbability(0.0);
        config.setGroupArrivalProb(1.0);
        config.setPartySize(4);
        config.setWalkTimeMean(5.0);
        config.setCongestionPenalty(0.0);
        config.getBaseConfig().setWindowCount(1);
        config.getBaseConfig().setTotalStudents(4);
        config.getBaseConfig().setTotalSeats(0);
        config.getBaseConfig().setNumFourSeatTables(1);
        config.getBaseConfig().setNumTwoSeatTables(1);
        config.getRandomBounds().setArrivalInterval(1);
        config.getRandomBounds().setPreferenceRange(java.util.List.of(0.0, 0.0));

        SimConfig.DistributionSpec fixedService = new SimConfig.DistributionSpec();
        fixedService.setType("FIXED");
        fixedService.setMean(1);
        config.setNormalServiceDist(fixedService);

        SimConfig.DistributionSpec fixedDining = new SimConfig.DistributionSpec();
        fixedDining.setType("FIXED");
        fixedDining.setMean(60);
        config.setDiningTimeDist(fixedDining);

        JsonNode summary = runAndGetSummary(config);

        assertEquals(4, summary.path("arrived_count").asInt());
        assertEquals(4, summary.path("served_count").asInt());
        assertEquals(4, summary.path("dine_in_count").asInt());
        assertEquals(4, summary.path("leave_count").asInt());
        assertEquals(2, summary.path("movement_sample_count").asInt());
        assertEquals(0.167, summary.path("total_movement_time_minutes").asDouble(), 0.000001);
        assertEquals(2, summary.path("table_snapshots").size());
        assertEquals(4, summary.path("table_snapshots").get(0).path("capacity").asInt());
        assertEquals(60, summary.path("table_snapshots").get(0).path("occupied_seconds").asLong());
        assertEquals(240, summary.path("table_snapshots").get(0).path("occupied_seat_seconds").asLong());
        assertTrue(summary.path("timeline").get(0).path("table_snapshots").isMissingNode());
    }

    private JsonNode runAndGetSummary(SimConfig config) {
        JsonNode reportNode = runAndGetReport(config);
        JsonNode summaryNode = reportNode.path("summary");
        assertTrue(!summaryNode.isMissingNode());
        return summaryNode;
    }

    private JsonNode runAndGetReport(SimConfig config) {
        ResponseEntity<ApiResponse<JsonNode>> response = controller.start(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertNotNull(response.getBody().getData());
        return response.getBody().getData();
    }

    private SimConfig baseConfig() {
        SimConfig config = new SimConfig();
        config.setDuration(1.0);
        config.setArrivalRate(60);
        config.setQueueLimit(10);
        config.setPackProbability(0.2);
        config.setSeed(42L);

        config.getBaseConfig().setWindowCount(4);
        config.getBaseConfig().setTotalSeats(40);
        config.getBaseConfig().setTotalStudents(0);

        config.getWeatherConfig().setWeatherImpactFactor(1.0);
        config.getRandomBounds().setArrivalInterval(0);
        config.getRandomBounds().setServiceRange(java.util.List.of(60, 180));
        config.getRandomBounds().setDiningRange(java.util.List.of(600, 1200));
        return config;
    }

    private SimConfig highLoadConfig() {
        SimConfig config = baseConfig();
        config.setDuration(0.5);
        config.setArrivalRate(420);
        config.getBaseConfig().setWindowCount(2);
        config.getBaseConfig().setTotalSeats(10);
        config.getRandomBounds().setServiceRange(java.util.List.of(120, 300));
        config.getRandomBounds().setDiningRange(java.util.List.of(900, 1500));
        config.setQueueLimit(6);
        config.setSeed(777L);
        return config;
    }
}
