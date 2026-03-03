package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.model.PricePrediction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class PricePredictionService {

    @Autowired
    private BinanceHistoricalService historicalDataService;

    @Autowired
    private AIModelService aiModelService;

    /**
     * AI-based prediction for multiple timeframes
     */
    public Map<String, PricePrediction> predictMultipleTimeframes(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new LinkedHashMap<>();

        try {
            // Get historical data for feature extraction
            List<CryptoPrice> historicalData = historicalDataService.getFullHistoricalData(symbol);

            if (historicalData.size() < 100) {
                log.debug("Insufficient data for AI prediction: {} points", historicalData.size());
                return createConservativePredictions(symbol, currentPrice);
            }

            // Extract latest features for prediction
            List<CryptoPrice> recentData = historicalData.subList(
                    historicalData.size() - 50, historicalData.size());

            // Generate predictions for different timeframes
            String[] timeframes = {"1h", "4h", "1d", "1w"};
            for (String timeframe : timeframes) {
                PricePrediction prediction = generateAIPrediction(symbol, currentPrice, recentData, timeframe);
                predictions.put(timeframe, prediction);
            }

        } catch (Exception e) {
            log.error("❌ AI prediction failed for {}: {}", symbol, e.getMessage(), e);
            return createConservativePredictions(symbol, currentPrice);
        }

        return predictions;
    }

    private PricePrediction generateAIPrediction(String symbol, double currentPrice,
                                                 List<CryptoPrice> recentData, String timeframe) {
        try {
            // Extract features for prediction
            double[] features = extractAdvancedFeatures(recentData, timeframe);

            // Get AI prediction with confidence
            Map<String, Object> aiResult = aiModelService.predictWithConfidence(features, timeframe);
            double predictedChange = (double) aiResult.get("prediction");
            double confidence = (double) aiResult.get("confidence");
            String modelType = (String) aiResult.get("model");

            double predictedPrice = currentPrice * (1 + predictedChange);
            String trend = determineTrend(predictedChange);

            // Calculate price targets based on confidence
            Map<String, Double> priceTargets = calculatePriceTargets(predictedPrice, confidence);
            Map<String, String> timeHorizons = Map.of(
                    "timeframe", getTimeframeDisplay(timeframe),
                    "type", getTimeframeType(timeframe),
                    "ai_model", modelType
            );

            // Create enhanced prediction with AI metadata
            PricePrediction prediction = new PricePrediction(
                    symbol, predictedPrice, confidence, trend, priceTargets, timeHorizons
            );

            log.debug("🎯 AI Prediction - {} {}: {:.2f}% change (confidence: {:.1f}%, model: {})",
                    symbol, timeframe, predictedChange * 100, confidence * 100, modelType);

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
        double[] prices = data.stream().mapToDouble(CryptoPrice::getPrice).toArray();
        double[] volumes = data.stream().mapToDouble(CryptoPrice::getVolume).toArray();

        if (timeframeType.equals("1h") || timeframeType.equals("4h")) {
            // SHORT-TERM features
            return new double[] {
                    calculateSMA(prices, 5), calculateSMA(prices, 20),
                    calculateEMA(prices, 12), calculateRSI(prices, 14),
                    calculateMACD(prices), calculateVolatility(prices, 10),
                    calculateMomentum(prices, 5), calculateVolumeTrend(volumes),
                    calculatePriceAcceleration(prices), calculateZScore(prices),
                    calculateBollingerPosition(prices), calculateVolumePriceTrend(volumes, prices),
                    calculateSupportResistance(prices), calculateTrendStrength(prices),
                    calculatePriceRateOfChange(prices, 5)
            };
        } else if (timeframeType.equals("1d")) {
            // MEDIUM-TERM features
            return new double[] {
                    calculateSMA(prices, 20), calculateSMA(prices, 50),
                    calculateEMA(prices, 26), calculateRSI(prices, 21),
                    calculateVolatility(prices, 20), calculateTrendStrength(prices),
                    calculateSupportResistance(prices), calculateSeasonality(data),
                    calculateMarketCycle(data), calculateVolumeStrength(volumes),
                    calculatePriceRateOfChange(prices, 10), calculateMomentum(prices, 15),
                    calculateZScore(prices), calculateBollingerPosition(prices),
                    calculateVolumePriceTrend(volumes, prices)
            };
        } else {
            // LONG-TERM features (1w)
            return new double[] {
                    calculateSMA(prices, 50), calculateSMA(prices, 200),
                    calculateVolatility(prices, 50), calculateLongTermTrend(prices),
                    calculateMarketMaturity(data), calculateSupportResistance(prices),
                    calculateTrendStrength(prices), calculateSeasonality(data),
                    calculateMarketCycle(data), calculateVolumeStrength(volumes),
                    calculatePriceRateOfChange(prices, 20), calculateZScore(prices),
                    calculateBollingerPosition(prices), calculateVolumePriceTrend(volumes, prices),
                    calculateAdoptionMetrics(data)
            };
        }
    }

    // ===== TECHNICAL INDICATORS =====

    private double calculateSMA(double[] prices, int period) {
        if (prices.length < period) return prices[prices.length-1];
        double sum = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            sum += prices[i];
        }
        return sum / period;
    }

    private double calculateEMA(double[] prices, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = prices[0];
        for (int i = 1; i < prices.length; i++) {
            ema = (prices[i] * multiplier) + (ema * (1 - multiplier));
        }
        return ema;
    }

    private double calculateRSI(double[] prices, int period) {
        if (prices.length < period + 1) return 50.0;

        double gains = 0.0;
        double losses = 0.0;

        for (int i = prices.length - period; i < prices.length - 1; i++) {
            double change = prices[i + 1] - prices[i];
            if (change > 0) {
                gains += change;
            } else {
                losses -= change;
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1 + rs));
    }

    private double calculateMACD(double[] prices) {
        double ema12 = calculateEMA(prices, 12);
        double ema26 = calculateEMA(prices, 26);
        return ema12 - ema26;
    }

    private double calculateVolatility(double[] prices, int period) {
        if (prices.length < period) return 0.0;

        double mean = calculateSMA(prices, period);
        double sum = 0.0;
        int start = Math.max(0, prices.length - period);
        int count = prices.length - start;

        for (int i = start; i < prices.length; i++) {
            sum += Math.pow(prices[i] - mean, 2);
        }

        return Math.sqrt(sum / count);
    }

    private double calculateMomentum(double[] prices, int period) {
        if (prices.length < period) return 0.0;
        return prices[prices.length-1] - prices[prices.length-period];
    }

    private double calculatePriceRateOfChange(double[] prices, int period) {
        if (prices.length < period) return 0.0;
        return ((prices[prices.length-1] - prices[prices.length-period]) / prices[prices.length-period]) * 100;
    }

    private double calculateVolumeTrend(double[] volumes) {
        if (volumes.length < 5) return 0.5;
        double currentVolume = volumes[volumes.length-1];
        double avgVolume = 0.0;
        for (int i = 0; i < volumes.length - 1; i++) {
            avgVolume += volumes[i];
        }
        avgVolume /= (volumes.length - 1);
        return currentVolume / avgVolume;
    }

    private double calculatePriceAcceleration(double[] prices) {
        if (prices.length < 3) return 0;
        double change1 = (prices[prices.length-1] - prices[prices.length-2]) / prices[prices.length-2];
        double change2 = (prices[prices.length-2] - prices[prices.length-3]) / prices[prices.length-3];
        return change1 - change2;
    }

    private double calculateZScore(double[] prices) {
        if (prices.length < 2) return 0.0;
        double mean = calculateSMA(prices, prices.length);
        double stdDev = calculateVolatility(prices, prices.length);
        return stdDev == 0 ? 0.0 : (prices[prices.length-1] - mean) / stdDev;
    }

    private double calculateBollingerPosition(double[] prices) {
        if (prices.length < 20) return 0.5;
        double sma20 = calculateSMA(prices, 20);
        double stdDev = calculateVolatility(prices, 20);
        double upperBand = sma20 + (2 * stdDev);
        double lowerBand = sma20 - (2 * stdDev);
        double currentPrice = prices[prices.length - 1];

        return (currentPrice - lowerBand) / (upperBand - lowerBand);
    }

    private double calculateVolumePriceTrend(double[] volumes, double[] prices) {
        if (prices.length < 2) return 0;

        double volumeSum = 0;
        double priceChangeSum = 0;

        for (int i = 1; i < prices.length; i++) {
            double priceChange = (prices[i] - prices[i-1]) / prices[i-1];
            volumeSum += volumes[i];
            priceChangeSum += priceChange * volumes[i];
        }

        return volumeSum == 0 ? 0 : priceChangeSum / volumeSum;
    }

    private double calculateSupportResistance(double[] prices) {
        if (prices.length < 10) return 0.0;
        double current = prices[prices.length - 1];
        double avg = calculateSMA(prices, prices.length);
        return (current - avg) / avg;
    }

    private double calculateTrendStrength(double[] prices) {
        if (prices.length < 20) return 0.0;
        double sma20 = calculateSMA(prices, Math.min(20, prices.length));
        double sma50 = calculateSMA(prices, Math.min(50, prices.length));
        return (sma20 - sma50) / sma50;
    }

    private double calculateSeasonality(List<CryptoPrice> data) {
        if (data.size() < 7) return 0;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(data.get(data.size() - 1).getTimestamp());
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        return (dayOfWeek >= 2 && dayOfWeek <= 5) ? 0.1 : -0.1;
    }

    private double calculateMarketCycle(List<CryptoPrice> data) {
        if (data.size() < 30) return 0;
        double[] prices = data.stream().mapToDouble(CryptoPrice::getPrice).toArray();
        double momentum30 = calculateMomentum(prices, 30);
        double momentum10 = calculateMomentum(prices, 10);
        return (momentum30 - momentum10) / Math.abs(momentum30);
    }

    private double calculateLongTermTrend(double[] prices) {
        if (prices.length < 100) return 0;
        // Simple linear regression slope
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = prices.length;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += prices[i];
            sumXY += i * prices[i];
            sumX2 += i * i;
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope / prices[0]; // Normalized slope
    }

    private double calculateMarketMaturity(List<CryptoPrice> data) {
        if (data.size() < 60) return 0.1;
        double volatility = calculateVolatility(
                data.stream().mapToDouble(CryptoPrice::getPrice).toArray(),
                Math.min(60, data.size())
        );
        return Math.max(0, 1 - volatility * 10);
    }

    private double calculateAdoptionMetrics(List<CryptoPrice> data) {
        return data.size() > 180 ? 0.05 : 0.02;
    }

    private double calculateVolumeStrength(double[] volumes) {
        if (volumes.length < 2) return 0.5;
        double currentVolume = volumes[volumes.length-1];
        double avgVolume = 0.0;
        for (int i = 0; i < volumes.length - 1; i++) {
            avgVolume += volumes[i];
        }
        avgVolume /= (volumes.length - 1);
        return currentVolume / avgVolume;
    }

      // ===== HELPER METHODS =====

    private String determineTrend(double predictedChange) {
        double changePercent = predictedChange * 100;

        if (changePercent > 5.0) return "STRONG_BULLISH";
        if (changePercent > 1.5) return "BULLISH";
        if (changePercent > -1.5) return "NEUTRAL";
        if (changePercent > -5.0) return "BEARISH";
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
        return switch(timeframe) {
            case "1h" -> "1 hour";
            case "4h" -> "4 hours";
            case "1d" -> "1 day";
            case "1w" -> "1 week";
            default -> timeframe;
        };
    }

    private String getTimeframeType(String timeframe) {
        return switch(timeframe) {
            case "1h", "4h" -> "SHORT_TERM";
            case "1d" -> "MEDIUM_TERM";
            case "1w" -> "LONG_TERM";
            default -> "UNKNOWN";
        };
    }

    private PricePrediction createFallbackPrediction(String symbol, double currentPrice, String timeframe) {
        return new PricePrediction(
                symbol,
                currentPrice,
                0.1, // Low confidence
                "NEUTRAL"
        );
    }

    private Map<String, PricePrediction> createConservativePredictions(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new HashMap<>();
        Random random = new Random();
        double smallRandomChange = (random.nextDouble() * 0.02) - 0.01; // -1% to +1%

        predictions.put("1h", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange), 0.3, "NEUTRAL"));
        predictions.put("4h", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 1.5), 0.4, "NEUTRAL"));
        predictions.put("1d", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 2), 0.5, "NEUTRAL"));
        predictions.put("1w", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 3), 0.4, "NEUTRAL"));

        return predictions;
    }
}