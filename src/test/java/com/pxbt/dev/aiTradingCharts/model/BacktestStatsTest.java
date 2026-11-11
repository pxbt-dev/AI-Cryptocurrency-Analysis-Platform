package com.pxbt.dev.aiTradingCharts.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestStatsTest {

    private static AccuracyRecord rec(double predicted, double actual) {
        return AccuracyRecord.builder()
                .predictedChange(predicted)
                .actualChange(actual)
                .isDirectionMatch((predicted > 0 && actual > 0) || (predicted < 0 && actual < 0))
                .isEvaluated(true)
                .build();
    }

    /** UT-STATS-001 (P0): error and skill are measured against the no-change forecast. */
    @Test
    void skillComparesModelErrorToNoChangeError() {
        // model errors 0.01 and 0.01 -> MAE 0.01; no-change errors 0.02 and 0.04 -> MAE 0.03
        BacktestStats s = BacktestStats.from(List.of(rec(0.01, 0.02), rec(-0.03, -0.04)));
        assertEquals(2, s.samples());
        assertEquals(1.0, s.winRate(), 1e-12);
        assertEquals(0.01, s.maeModel(), 1e-12);
        assertEquals(0.03, s.maeBaseline(), 1e-12);
        assertEquals(1.0 - 0.01 / 0.03, s.skill(), 1e-12);
    }

    /** UT-STATS-002 (P0): a 55% win rate over 100 samples is not proof of an edge (lower bound < 50%). */
    @Test
    void smallSampleWinRateIsNotAnEdge() {
        List<AccuracyRecord> rs = new ArrayList<>();
        for (int i = 0; i < 55; i++) rs.add(rec(0.001, 0.002));
        for (int i = 0; i < 45; i++) rs.add(rec(0.001, -0.002));
        BacktestStats s = BacktestStats.from(rs);
        assertEquals(0.55, s.winRate(), 1e-12);
        assertEquals(0.55 - 1.645 * 0.05, s.winRateLowerBound(), 1e-12); // 0.46775
        assertFalse(s.hasEdge());
    }

    /** UT-STATS-003 (P1): unevaluated records are ignored; empty input gives zero samples. */
    @Test
    void ignoresUnevaluatedRecords() {
        AccuracyRecord pending = rec(0.01, 0.01);
        pending.setEvaluated(false);
        BacktestStats s = BacktestStats.from(List.of(pending));
        assertEquals(0, s.samples());
        assertFalse(s.hasEdge());
    }
}
