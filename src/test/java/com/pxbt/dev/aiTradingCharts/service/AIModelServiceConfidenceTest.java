package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.BacktestStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIModelServiceConfidenceTest {

    private static BacktestStats stats(double lowerBound, double skill) {
        return new BacktestStats(1000, 0, 0, lowerBound, 0, 0, skill);
    }

    /** UT-CONF-001 (P0): no out-of-sample evidence means zero confidence. */
    @Test
    void noEvidenceGivesZero() {
        assertEquals(0.0, AIModelService.calculateConfidence(null), 1e-12);
        assertEquals(0.0, AIModelService.calculateConfidence(new BacktestStats(0, 0, 0, 0, 0, 0, 0)), 1e-12);
    }

    /** UT-CONF-002 (P0): coin-flip win rate and worse-than-baseline error give zero, not a floor. */
    @Test
    void noEdgeGivesZero() {
        assertEquals(0.0, AIModelService.calculateConfidence(stats(0.48, -0.05)), 1e-12);
    }

    /** UT-CONF-003 (P0): direction and error each contribute half, capped at full marks. */
    @Test
    void scoresDirectionAndErrorEqually() {
        assertEquals(0.5 * 0.5 + 0.5 * 0.4, AIModelService.calculateConfidence(stats(0.55, 0.04)), 1e-12);
        assertEquals(1.0, AIModelService.calculateConfidence(stats(0.70, 0.30)), 1e-12);
    }
}
