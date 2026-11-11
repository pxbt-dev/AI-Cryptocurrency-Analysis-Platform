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
    private double baselineError; // Mean absolute % move, i.e. the error of a "no change" forecast
    private double skillVsBaseline; // % by which the model's error beats the no-change forecast (negative = worse)
    private boolean hasEdge; // Beats a coin flip (95% lower bound) and the no-change forecast
}
