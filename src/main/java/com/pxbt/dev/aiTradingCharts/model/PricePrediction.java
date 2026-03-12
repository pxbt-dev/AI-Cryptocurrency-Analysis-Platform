package com.pxbt.dev.aiTradingCharts.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;
import java.util.HashMap;

@Data
@NoArgsConstructor
public class PricePrediction {
    private String symbol;
    private double predictedPrice;
    private double confidence;
    private String trend;
    private Map<String, Double> priceTargets = new HashMap<>();
    private Map<String, String> timeHorizons = new HashMap<>();
    private String modelName;
    private double rScore;
    private double trendValue;
    private double momentum;
    private double rsiFactor;
    private int trainingSamplesCount;
    private long timestamp;

    public PricePrediction(String symbol, double predictedPrice, double confidence, String trend) {
        this.symbol = symbol;
        this.predictedPrice = predictedPrice;
        this.confidence = confidence;
        this.trend = trend;
        this.timestamp = System.currentTimeMillis();
    }

    public PricePrediction(String symbol, double predictedPrice, double confidence, String trend,
            Map<String, Double> priceTargets, Map<String, String> timeHorizons) {
        this.symbol = symbol;
        this.predictedPrice = predictedPrice;
        this.confidence = confidence;
        this.trend = trend;
        this.priceTargets = priceTargets != null ? priceTargets : new HashMap<>();
        this.timeHorizons = timeHorizons != null ? timeHorizons : new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public String getConfidenceLevel() {
        if (confidence > 0.8)
            return "HIGH";
        if (confidence > 0.6)
            return "MEDIUM";
        return "LOW";
    }

    // ✅ Add a simple safe method for display
    public String getDisplayPrice() {
        return String.format("$%.2f", predictedPrice);
    }
}