package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIAnalysisResult {
    private String symbol;
    private String timeframe;
    private double currentPrice;
    private Map<String, PricePrediction> timeframePredictions;
    private List<ChartPattern> chartPatterns;
    private List<FibonacciTimeZone> fibonacciTimeZones;
    private long timestamp;

    // CONSTRUCTOR for backward compatibility
    public AIAnalysisResult(String symbol, double currentPrice, Map<String, PricePrediction> timeframePredictions,
                            List<ChartPattern> chartPatterns, List<FibonacciTimeZone> fibonacciTimeZones, long timestamp) {
        this.symbol = symbol;
        this.currentPrice = currentPrice;
        this.timeframePredictions = timeframePredictions;
        this.chartPatterns = chartPatterns;
        this.fibonacciTimeZones = fibonacciTimeZones;
        this.timestamp = timestamp;
        this.timeframe = "1d"; // Default timeframe
    }

    // Existing custom methods
    public PricePrediction getMainPrediction() {
        // 🆕 Use the current timeframe for main prediction
        String mainTimeframe = timeframe != null ? timeframe : "1d";

        // Map frontend timeframe keys to backend keys if needed
        String predictionKey = mapTimeframeToPredictionKey(mainTimeframe);

        return timeframePredictions != null ? timeframePredictions.get(predictionKey) : null;
    }

    public double getPredictedPrice() {
        PricePrediction main = getMainPrediction();
        return main != null ? main.getPredictedPrice() : currentPrice;
    }

    public double getConfidence() {
        PricePrediction main = getMainPrediction();
        return main != null ? main.getConfidence() : 0.1;
    }

    public String getTradingSignal() {
        PricePrediction main = getMainPrediction();
        return main != null ? generateTradingSignal(main) : "HOLD";
    }

    private String generateTradingSignal(PricePrediction prediction) {
        double changePercent = ((prediction.getPredictedPrice() - currentPrice) / currentPrice) * 100;
        double confidence = prediction.getConfidence();

        if (changePercent > 2.0 && confidence > 0.7) return "STRONG_BUY";
        if (changePercent > 0.5 && confidence > 0.6) return "BUY";
        if (changePercent < -2.0 && confidence > 0.7) return "STRONG_SELL";
        if (changePercent < -0.5 && confidence > 0.6) return "SELL";
        return "HOLD";
    }

    private String mapTimeframeToPredictionKey(String timeframe) {
        // Map frontend timeframe to prediction keys
        switch (timeframe) {
            case "1h": return "1hour";
            case "4h": return "4hour";
            case "1d": return "1day";
            case "1w": return "1week";
            case "1m": return "1month";
            default: return "1day";
        }
    }
}