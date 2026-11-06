package com.pxbt.dev.aiTradingCharts.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operator endpoints need the X-Admin-Token header; the actuator no longer exposes env.
 */
@SpringBootTest(properties = "ADMIN_TOKEN=test-admin-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void debugEndpointRejectsMissingToken() throws Exception {
        mvc.perform(get("/api/debug/jvm")).andExpect(status().isUnauthorized());
    }

    @Test
    void debugEndpointRejectsWrongToken() throws Exception {
        mvc.perform(get("/api/debug/jvm").header("X-Admin-Token", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void debugEndpointAcceptsCorrectToken() throws Exception {
        mvc.perform(get("/api/debug/jvm").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void trainAndManualBacktestRejectMissingToken() throws Exception {
        mvc.perform(post("/api/system/train")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/backtest/run").param("symbol", "BTC")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsStayOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/system/stats")).andExpect(status().isOk());
    }

    @Test
    void actuatorEnvIsNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
