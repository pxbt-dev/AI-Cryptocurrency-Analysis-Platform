package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccuracyRecord {
    private String symbol;
    private String timeframe;
    private long predictionTime;
    private long targetTime;
    private double currentPrice;
    private double predictedPrice;
    private double actualPrice;
    private double predictedChange; // as decimal percentage
    private double actualChange;
    private boolean isDirectionMatch;
    private String modelName;
    private boolean isEvaluated; // Set to true when ActualPrice is known and compared
}
