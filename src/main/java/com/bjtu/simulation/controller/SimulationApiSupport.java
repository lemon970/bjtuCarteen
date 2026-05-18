package com.bjtu.simulation.controller;

import com.bjtu.simulation.config.AppBeansConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SimulationApiSupport {
    private SimulationApiSupport() {
    }

    static ObjectMapper createReportMapper() {
        return AppBeansConfig.createReportObjectMapper();
    }
}
