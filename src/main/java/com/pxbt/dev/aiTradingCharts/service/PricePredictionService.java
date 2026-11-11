package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.model.ModelPerformance;
import com.pxbt.dev.aiTradingCharts.model.PricePrediction;
import com.pxbt.dev.aiTradingCharts.util.Ta4jConverter;
import com.pxbt.dev.aiTradingCharts.util.TechnicalForecast;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;

@Slf4j
@Service
public class PricePredictionService {

    @Autowired
    private BinanceHistoricalService historicalDataService;

    @Autowired
    private SymbolConfig symbolConfig;

    @Autowired
    private AccuracyPersistenceService accuracyPersistenceService;

    @Autowired
    private TradingMetricsService metricsService;

    @Autowired
    private AIModelService aiModelService;

    @Autowired
    private FearGreedService fearGreedService;

    @Autowired
    private FundingRateService fundingRateService;

    /**
     * AI-based prediction for multiple timeframes
     */
    public Map<String, PricePrediction> predictMultipleTimeframes(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new LinkedHashMap<>();

        try {
            // Generate predictions for different timeframes
            String[][] timeframeConfigs = {
                    { "1d", "1day" },
                    { "1w", "1week" },
                    { "1m", "1month" }
            };

            for (String[] config : timeframeConfigs) {
                String tfCode = config[0];
                String tfUI = config[1];

                // Fetch timeframe-specific data (e.g., weekly candles for weekly prediction)
                int pointsNeeded = tfCode.equals("1d") ? 2500 : 200;
                List<CryptoPrice> timeframeData = historicalDataService.getHistoricalData(symbol, tfCode, pointsNeeded);

                if (timeframeData.size() >= 10) {
                    PricePrediction prediction = generateAIPrediction(symbol, currentPrice, timeframeData, tfCode);
                    predictions.put(tfUI, prediction);
                } else {
                    log.debug("Insufficient {} data for {}, skipping", tfCode, symbol);
                }
            }

        } catch (Exception e) {
            log.error("❌ AI prediction failed for {}: {}", symbol, e.getMessage(), e);
            return createConservativePredictions(symbol, currentPrice);
        }

        return predictions;
    }

    public PricePrediction generateAIPrediction(String symbol, double currentPrice,
            List<CryptoPrice> recentData, String timeframe) {
        try {
            // 1. Convert to ta4j BarSeries and initialize indicators once (timeframe-aware periods)
            BarSeries series = Ta4jConverter.toSeries(symbol, recentData);
            int lastIdx = series.getEndIndex();
            com.pxbt.dev.aiTradingCharts.util.FeatureExtractor.Indicators inds =
                new com.pxbt.dev.aiTradingCharts.util.FeatureExtractor.Indicators(series, timeframe);

            // 2. Extract features for AI prediction using the pre-initialized indicators
            double[] features = com.pxbt.dev.aiTradingCharts.util.FeatureExtractor.extractFeatures(lastIdx, inds);

            // Get AI result
            Map<String, Object> aiResult = aiModelService.predictWithConfidence(symbol, features, timeframe);
            double predictedChange = (double) aiResult.get("prediction");
            double confidence = (double) aiResult.get("confidence");
            String modelType = (String) aiResult.get("model");
            boolean aiTrained = !modelType.equals("none") && !modelType.equals("error");
            boolean aiReliable = aiTrained && (boolean) aiResult.getOrDefault("isReliable", false);

            // 3. Technical Indicators (REUSE pre-initialized indicators from 'inds' container)
            double trendValue = TechnicalForecast.trend(inds, lastIdx);
            double momentum = TechnicalForecast.momentum(inds, lastIdx, timeframe);

            if (!aiReliable) {
                predictedChange = TechnicalForecast.predict(inds, lastIdx, timeframe, symbolConfig.isVolatile(symbol));

                if (aiTrained) {
                    double aiVal = (double) aiResult.get("prediction");
                    predictedChange = (predictedChange * 0.6) + (aiVal * 0.4);
                    modelType = "AI+TECH";
                } else {
                    confidence = 0.0; // The technical formula has no backtested evidence
                    modelType = "TECHNICAL_TREND";
                }
            }

            // External mean reversion signals (Fear & Greed + funding rate).
            // These are market-wide / leverage signals independent of price history.
            double fgSignal      = fearGreedService.getMeanReversionSignal();   // +1=extreme fear, -1=extreme greed
            double fundingSignal = fundingRateService.getMeanReversionSignal(symbol); // +1=shorts crowded, -1=longs crowded
            double mrSignal      = (fgSignal * 0.6) + (fundingSignal * 0.4);   // combined [-1, +1]

            // Only dampen when signals push against the predicted direction.
            // Max 40% dampening when signal is at full opposition.
            if ((predictedChange > 0 && mrSignal < 0) || (predictedChange < 0 && mrSignal > 0)) {
                double dampening = Math.abs(mrSignal) * 0.4;
                predictedChange *= (1.0 - dampening);
                log.debug("↩️ Mean reversion dampening for {} {}: mrSignal={}, dampening={}",
                        symbol, timeframe, String.format("%.2f", mrSignal), String.format("%.2f", dampening));
            }

            // Confidence comes only from out-of-sample backtest evidence (AIModelService.calculateConfidence);
            // no untested bonuses are added here.
            double predictedPrice = currentPrice * (1 + predictedChange);
            String trend = determineTrend(predictedChange);

            // Calculate price targets based on confidence
            Map<String, Double> priceTargets = calculatePriceTargets(predictedPrice, confidence);
            Map<String, String> timeHorizons = Map.of(
                    "timeframe", getTimeframeDisplay(timeframe),
                    "type", getTimeframeType(timeframe),
                    "ai_model", modelType);

            // Create enhanced prediction
            PricePrediction prediction = new PricePrediction(
                    symbol, predictedPrice, confidence, trend, priceTargets, timeHorizons);
            prediction.setModelName(modelType);
            prediction.setRScore(aiResult.containsKey("rScore") ? (double) aiResult.get("rScore") : 0.0);

            // Always set sample count
            ModelPerformance performance = aiModelService.getModelPerformance(symbol, timeframe);
            if (performance != null && performance.getTrainingSampleSize() > 0) {
                prediction.setTrainingSamplesCount(performance.getTrainingSampleSize());
            } else {
                prediction.setTrainingSamplesCount(recentData.size());
            }

            // Populate granular indicator stats for display (Still reusing 'inds')
            prediction.setTrendValue(trendValue);
            prediction.setMomentum(momentum);
            prediction.setRsiFactor((50.0 - inds.rsi14.getValue(lastIdx).doubleValue()) / 50.0);
            prediction.setFearGreedScore(fearGreedService.getScore());
            prediction.setFearGreedLabel(fearGreedService.getClassification());
            prediction.setFundingRate(fundingRateService.getFundingRate(symbol));
            prediction.setMeanReversionSignal(mrSignal);

            log.debug("🎯 {} Prediction - {} {} ({}): {}% | Conf: {}%",
                    modelType, symbol, timeframe,
                    aiReliable ? "AI" : "TECH",
                    predictedChange * 100, confidence * 100);

            return prediction;

        } catch (Exception e) {
            log.error("❌ AI prediction failed for {} {}: {}", symbol, timeframe, e.getMessage());
            return createFallbackPrediction(symbol, currentPrice, timeframe);
        }
    }

    /**
     * Enhanced feature extraction for AI predictions
     */
    private double[] extractAdvancedFeatures(List<CryptoPrice> data, String timeframeType) {
        return com.pxbt.dev.aiTradingCharts.util.FeatureExtractor.extractFeatures(data);
    }

    // ===== HELPER METHODS =====

    private String determineTrend(double predictedChange) {
        double changePercent = predictedChange * 100;

        if (changePercent > 5.0)
            return "STRONG_BULLISH";
        if (changePercent > 1.5)
            return "BULLISH";
        if (changePercent > -1.5)
            return "NEUTRAL";
        if (changePercent > -5.0)
            return "BEARISH";
        return "STRONG_BEARISH";
    }

    private Map<String, Double> calculatePriceTargets(double predictedPrice, double confidence) {
        Map<String, Double> targets = new HashMap<>();
        double conservativeFactor = 0.7 + (0.3 * confidence);
        double optimisticFactor = 1.3 * confidence;

        targets.put("conservative", predictedPrice * conservativeFactor);
        targets.put("expected", predictedPrice);
        targets.put("optimistic", predictedPrice * optimisticFactor);
        return targets;
    }

    private String getTimeframeDisplay(String timeframe) {
        return switch (timeframe) {
            case "1d" -> "1 day";
            case "1w", "1W" -> "1 week";
            case "1M" -> "1 month";
            default -> timeframe;
        };
    }

    private String getTimeframeType(String timeframe) {
        return switch (timeframe) {
            case "1d" -> "MEDIUM_TERM";
            case "1w", "1W", "1M" -> "LONG_TERM";
            default -> "UNKNOWN";
        };
    }

    private PricePrediction createFallbackPrediction(String symbol, double currentPrice, String timeframe) {
        return new PricePrediction(symbol, currentPrice, 0.0, "NEUTRAL");
    }

    // On failure show "no change" with zero confidence rather than a made-up move
    private Map<String, PricePrediction> createConservativePredictions(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new HashMap<>();
        predictions.put("1day", new PricePrediction(symbol, currentPrice, 0.0, "NEUTRAL"));
        predictions.put("1week", new PricePrediction(symbol, currentPrice, 0.0, "NEUTRAL"));
        predictions.put("1month", new PricePrediction(symbol, currentPrice, 0.0, "NEUTRAL"));
        return predictions;
    }
    private long getMillisForTimeframe(String timeframe) {
        switch (timeframe.toLowerCase()) {
            case "1d": return 86400000L;
            case "1w": return 604800000L;
            case "1m": return 2592000000L;
            default: return 86400000L;
        }
    }
}
