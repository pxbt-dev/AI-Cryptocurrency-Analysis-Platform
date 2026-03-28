package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestSummary {
    private String symbol;
    private String timeframe;
    private int totalEvaluated;
    private int totalPending;
    private double winRate; // Percentage
    private double avgError; // Absolute percentage error
    private double latestActualPrice;
}
