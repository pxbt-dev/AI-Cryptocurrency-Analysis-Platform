package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradingAnalysisService {

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private PricePredictionService pricePredictionService;

    private final Random random = new Random();

    public AIAnalysisResult analyzeMarketData(String symbol, double currentPrice) {
        log.info("🔄 Starting ENHANCED analysis for {} - Price: ${}", symbol, currentPrice);

        // GET ENHANCED HISTORICAL DATA (7 days + real-time)
        List<PriceUpdate> historicalData = marketDataService.getHistoricalData(symbol, 200);
        int dataPoints = historicalData.size();

        double daysCovered = calculateDaysCovered(historicalData);
        log.info("📊 Using {} data points for {} ({} days of data)",
                dataPoints, symbol, String.format("%.1f", daysCovered));

        // MULTI-TIMEFRAME ANALYSIS (Using AI Prediction Service)
        Map<String, PricePrediction> timeframePredictions = pricePredictionService
                .predictMultipleTimeframes(symbol, currentPrice);

        List<ChartPattern> chartPatterns = detectLongTermPatterns(symbol, currentPrice, historicalData);
        List<FibonacciTimeZone> fibonacciTimeZones = calculateWeeklyFibonacci(symbol, historicalData);

        // CREATE RESULT
        AIAnalysisResult result = new AIAnalysisResult();
        result.setSymbol(symbol);
        result.setCurrentPrice(currentPrice);
        result.setTimeframePredictions(timeframePredictions);
        result.setChartPatterns(chartPatterns);
        result.setFibonacciTimeZones(fibonacciTimeZones);
        result.setTimestamp(System.currentTimeMillis());

        log.info("✅ AI-READY Analysis - Signal: {}, Confidence: {}%, Data Coverage: {} days",
                result.getTradingSignal(), result.getConfidence(), String.format("%.1f", daysCovered));

        // Collect logs for the result
        result.getAnalysisLogs().add(String.format("📊 Data Points: %d (%s days cover)", dataPoints, String.format("%.1f", daysCovered)));
        
        // Log AI findings from the actual prediction service
        timeframePredictions.forEach((tf, p) -> {
            result.getAnalysisLogs().add(String.format("🔍 %s Predict [%s] - Signal: %s, Conf: %.1f%% => $%s",
                tf.toUpperCase(), p.getModelName(), p.getTrend(), p.getConfidence() * 100, 
                String.format("%.2f", p.getPredictedPrice())));
        });

        return result;
    }

    /**
     * Analyze price data for specific timeframes with detailed logging
     */
    public AIAnalysisResult analyzePriceData(List<CryptoPrice> prices, String timeframe) {
        if (prices == null || prices.isEmpty()) {
            log.warn("No price data available for timeframe analysis: {}", timeframe);
            return createEmptyAnalysis("BTC", timeframe);
        }

        logAnalysisProcess(prices, timeframe);

        // Convert CryptoPrice to PriceUpdate for compatibility with existing methods
        List<PriceUpdate> priceUpdates = convertToPriceUpdates(prices);

        // Use existing analysis logic but with timeframe context
        return analyzeMarketDataWithTimeframe(prices.get(0).getSymbol(),
                prices.get(prices.size() - 1).getPrice(),
                priceUpdates,
                timeframe);
    }

    /**
     * Enhanced analysis with timeframe context
     */
    private AIAnalysisResult analyzeMarketDataWithTimeframe(String symbol, double currentPrice,
            List<PriceUpdate> historicalData, String timeframe) {

        debugHistoricalData(symbol, historicalData);

        log.info("🔄 Starting TIMEFRAME analysis for {} - Timeframe: {}, Price: ${}",
                symbol, timeframe, currentPrice);

        int dataPoints = historicalData.size();
        log.info("📊 Using {} data points for {} timeframe {}", dataPoints, symbol, timeframe);

        // MULTI-TIMEFRAME ANALYSIS with enhanced logging
        Map<String, PricePrediction> timeframePredictions = calculateMultiTimeframePredictions(
                symbol, currentPrice, historicalData);

        List<ChartPattern> chartPatterns = detectLongTermPatterns(symbol, currentPrice, historicalData);
        List<FibonacciTimeZone> fibonacciTimeZones = calculateWeeklyFibonacci(symbol, historicalData);

        // Log AI reasoning process
        logAIAnalysisReasoning(timeframePredictions, chartPatterns, fibonacciTimeZones, timeframe);

        // CREATE RESULT
        AIAnalysisResult result = new AIAnalysisResult();
        result.setSymbol(symbol);
        result.setCurrentPrice(currentPrice);
        result.setTimeframePredictions(timeframePredictions);
        result.setChartPatterns(chartPatterns);
        result.setFibonacciTimeZones(fibonacciTimeZones);
        result.setTimestamp(System.currentTimeMillis());
        result.setTimeframe(timeframe);

        log.info("✅ TIMEFRAME Analysis Complete - Symbol: {}, Timeframe: {}, Signal: {}, Confidence: {}%",
                symbol, timeframe, result.getTradingSignal(),
                String.format("%.1f", result.getConfidence() * 100));

        return result;
    }

    /**
     * Log detailed AI reasoning process
     */
    private void logAIAnalysisReasoning(Map<String, PricePrediction> predictions,
            List<ChartPattern> patterns,
            List<FibonacciTimeZone> fibZones,
            String timeframe) {
        List<String> reasoning = new ArrayList<>();

        // Analyze predictions
        if (predictions != null && !predictions.isEmpty()) {
            PricePrediction mainPred = predictions.get("1day");
            if (mainPred != null) {
                reasoning.add(String.format("Main prediction: %s with %.1f%% confidence",
                        mainPred.getTrend(), mainPred.getConfidence() * 100));
            }
        }

        // Analyze patterns
        if (patterns != null && !patterns.isEmpty()) {
            patterns.stream()
                    .filter(p -> p.getConfidence() > 0.7)
                    .forEach(p -> reasoning.add(String.format("Pattern: %s (%.0f%%)",
                            p.getPatternType(), p.getConfidence() * 100)));
        }

        // Analyze Fibonacci zones
        if (fibZones != null && !fibZones.isEmpty()) {
            reasoning.add(String.format("Fibonacci zones detected: %d", fibZones.size()));
        }

        // Log the reasoning
        if (!reasoning.isEmpty()) {
            log.info("🧠 AI Reasoning for {} timeframe: {}", timeframe, String.join("; ", reasoning));
        }
    }

    /**
     * Log the analysis process with timeframe context
     */
    private void logAnalysisProcess(List<CryptoPrice> prices, String timeframe) {
        log.info("🤖 AI Analysis started - Timeframe: {}, Data points: {}", timeframe, prices.size());

        if (!prices.isEmpty()) {
            CryptoPrice first = prices.get(0);
            CryptoPrice last = prices.get(prices.size() - 1);
            double change = ((last.getPrice() - first.getPrice()) / first.getPrice()) * 100;

            log.info("📊 Price analysis - First: ${}, Last: ${}, Change: {}% over {} period",
                    first.getPrice(), last.getPrice(), String.format("%.2f", change), timeframe);
        }
    }

    /**
     * Convert CryptoPrice to PriceUpdate for compatibility
     */
    private List<PriceUpdate> convertToPriceUpdates(List<CryptoPrice> cryptoPrices) {
        return cryptoPrices.stream()
                .map(cp -> new PriceUpdate(
                        cp.getSymbol(),
                        cp.getPrice(), // price
                        cp.getVolume(), // volume
                        cp.getTimestamp(), // timestamp
                        cp.getPrice(), // open (use price if not available)
                        cp.getPrice(), // high (use price if not available)
                        cp.getPrice(), // low (use price if not available)
                        cp.getPrice() // close (use price if not available)
                ))
                .collect(Collectors.toList());
    }

    /**
     * Create empty analysis for error cases
     */
    private AIAnalysisResult createEmptyAnalysis(String symbol, String timeframe) {
        AIAnalysisResult result = new AIAnalysisResult();
        result.setSymbol(symbol);
        result.setCurrentPrice(0.0);
        result.setTimeframePredictions(createConservativePredictions(symbol, 0.0));
        result.setChartPatterns(new ArrayList<>());
        result.setFibonacciTimeZones(new ArrayList<>());
        result.setTimestamp(System.currentTimeMillis());

        log.warn("⚠️ Created empty analysis for {} - {}", symbol, timeframe);
        return result;
    }

    private Map<String, PricePrediction> calculateMultiTimeframePredictions(
            String symbol, double currentPrice, List<PriceUpdate> historicalData) {
        
        // Unified path: Always use the PredictionService which uses the AI Models
        return pricePredictionService.predictMultipleTimeframes(symbol, currentPrice);
    }

    private void debugTechnicalIndicators(List<PriceUpdate> data, String timeframe) {
        if (data.isEmpty()) {
            log.info("⚠️ No data for {} technical indicators", timeframe);
            return;
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        log.info("📊 {} Technical Indicators - Trend: {}, Volatility: {}, Momentum: {}, RSI: {}, Data Points: {}",
                timeframe, trend, volatility, momentum, rsi, data.size());

        // Also log price range
        double minPrice = data.stream().mapToDouble(PriceUpdate::getPrice).min().orElse(0);
        double maxPrice = data.stream().mapToDouble(PriceUpdate::getPrice).max().orElse(0);
        log.info("📊 {} Price Range - Min: ${}, Max: ${}, Current: ${}",
                timeframe, minPrice, maxPrice, data.get(data.size() - 1).getPrice());
    }

    private void debugHistoricalData(String symbol, List<PriceUpdate> historicalData) {
        log.debug("📊 Historical Data for {}: {} total points", symbol, historicalData.size());
        if (!historicalData.isEmpty()) {
            long startTime = historicalData.get(0).getTimestamp();
            long endTime = historicalData.get(historicalData.size() - 1).getTimestamp();
            long days = (endTime - startTime) / (1000 * 60 * 60 * 24);
            log.debug("📊 Data range: {} days ({} to {})",
                    days,
                    new java.util.Date(startTime),
                    new java.util.Date(endTime));

            // Log first few and last few prices
            log.debug("📊 Sample prices - First: ${}, Last: ${}",
                    historicalData.get(0).getPrice(),
                    historicalData.get(historicalData.size() - 1).getPrice());
        }
    }

    private double calculateWeeklyTrend(List<PriceUpdate> data) {
        if (data.size() < 20)
            return 0.0;

        // Use first 25% vs last 25% for weekly trend analysis
        int sampleSize = Math.max(10, data.size() / 4);
        double earlyAverage = data.stream()
                .limit(sampleSize)
                .mapToDouble(PriceUpdate::getPrice)
                .average()
                .orElse(0.0);

        double recentAverage = data.stream()
                .skip(data.size() - sampleSize)
                .mapToDouble(PriceUpdate::getPrice)
                .average()
                .orElse(0.0);

        return (recentAverage - earlyAverage) / earlyAverage;
    }

    private double calculateWeeklyVolatility(List<PriceUpdate> data) {
        if (data.size() < 10)
            return 0.0;

        // Calculate weekly volatility (standard deviation of daily returns)
        List<Double> dailyReturns = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            double returnRate = (data.get(i).getPrice() - data.get(i - 1).getPrice()) / data.get(i - 1).getPrice();
            dailyReturns.add(returnRate);
        }

        double meanReturn = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = dailyReturns.stream()
                .mapToDouble(r -> Math.pow(r - meanReturn, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    private double findWeeklySupport(List<PriceUpdate> data) {
        if (data.isEmpty())
            return 0.0;

        // Find significant support level (lowest 10% of prices)
        List<Double> prices = data.stream()
                .map(PriceUpdate::getPrice)
                .sorted()
                .collect(Collectors.toList());

        int supportIndex = Math.max(0, prices.size() / 10); // 10th percentile
        return prices.get(supportIndex);
    }

    private double findWeeklyResistance(List<PriceUpdate> data) {
        if (data.isEmpty())
            return 0.0;

        // Find significant resistance level (highest 10% of prices)
        List<Double> prices = data.stream()
                .map(PriceUpdate::getPrice)
                .sorted()
                .collect(Collectors.toList());

        int resistanceIndex = Math.min(prices.size() - 1, prices.size() * 9 / 10); // 90th percentile
        return prices.get(resistanceIndex);
    }

    private double calculateDaysCovered(List<PriceUpdate> data) {
        if (data.size() < 2)
            return 0.0;

        long startTime = data.get(0).getTimestamp();
        long endTime = data.get(data.size() - 1).getTimestamp();
        long durationMs = endTime - startTime;

        return durationMs / (1000.0 * 60 * 60 * 24); // Convert to days
    }

    private PricePrediction calculate1DPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 10) {
            return new PricePrediction(symbol, currentPrice, 0.4, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);
        double volatility = calculateVolatility(data);

        // Add RSI mean reversion for 1D
        double rsiFactor = (50 - rsi) / 100; // RSI below 50 suggests bounce potential
        double prediction = currentPrice * (1 + (trend * 0.4 + momentum * 0.4 + rsiFactor * 0.2));

        // Cap extreme moves but allow more sensitivity than before
        prediction = Math.min(currentPrice * 1.03, Math.max(currentPrice * 0.97, prediction));

        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1D");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1D Prediction - trend: {}, momentum: {}, rsiFactor: {}, prediction: {}",
                trend, momentum, rsiFactor, prediction);
        PricePrediction pred = new PricePrediction(symbol, prediction, confidence, signal);
        pred.setTrendValue(trend);
        pred.setMomentum(momentum);
        pred.setRsiFactor(rsiFactor);
        return pred;
    }

    private PricePrediction calculate1WPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 20) {
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);
        double volatility = calculateVolatility(data);

        // 1W - check support/resistance levels
        double support = findSupportLevel(data);
        double resistance = findResistanceLevel(data);
        double distanceToSupport = (currentPrice - support) / currentPrice;
        double distanceToResistance = (resistance - currentPrice) / currentPrice;

        // If near support, less bearish; if near resistance, more bearish
        double levelFactor = distanceToSupport < 0.05 ? 0.3 : (distanceToResistance < 0.05 ? -0.3 : 0);

        double prediction = currentPrice * (1 + trend * 0.6 + momentum * 0.2 + levelFactor * 0.2);

        // Cap weekly moves
        prediction = Math.min(currentPrice * 1.10, Math.max(currentPrice * 0.85, prediction));

        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1W");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1W Prediction - trend: {}, support: {}, resistance: {}, prediction: {}",
                trend, support, resistance, prediction);
        PricePrediction pred = new PricePrediction(symbol, prediction, confidence, signal);
        pred.setTrendValue(trend);
        pred.setMomentum(momentum);
        pred.setRsiFactor((50 - rsi) / 100);
        return pred;
    }

    private double calculateDynamicConfidence(double trend, double volatility, int dataSize, String timeframe) {
        double baseConfidence = getBaseConfidence(timeframe);
        double trendStrength = Math.min(1.0, Math.abs(trend) * 10);
        double dataQuality = Math.min(1.0, dataSize / 50.0);
        double volatilityPenalty = Math.max(0.3, 1.0 - (volatility * 3));

        return Math.max(0.1,
                baseConfidence * (0.3 + trendStrength * 0.3 + dataQuality * 0.2 + volatilityPenalty * 0.2));
    }

    private double getBaseConfidence(String timeframe) {
        return switch (timeframe) {
            case "1D" -> 0.7;
            case "1W" -> 0.75;
            case "1M" -> 0.8;
            default -> 0.5;
        };
    }

    // Add helper methods
    private double findSupportLevel(List<PriceUpdate> data) {
        return data.stream()
                .mapToDouble(PriceUpdate::getPrice)
                .min()
                .orElse(data.get(data.size() - 1).getPrice());
    }

    private double findResistanceLevel(List<PriceUpdate> data) {
        return data.stream()
                .mapToDouble(PriceUpdate::getPrice)
                .max()
                .orElse(data.get(data.size() - 1).getPrice());
    }

    private PricePrediction calculate1MPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 30) {
            debugTechnicalIndicators(data, "1M");
            return new PricePrediction(symbol, currentPrice, 0.2, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        // 1M prediction - even more weight on trend, include mean reversion
        double meanPrice = data.stream().mapToDouble(PriceUpdate::getPrice).average().orElse(currentPrice);
        double meanReversion = (meanPrice - currentPrice) / currentPrice * 0.3;

        double prediction = currentPrice * (1 + trend * 1.2 + meanReversion);
        double confidence = Math.min(0.75, Math.abs(trend) * 3 + 0.15);
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1M Prediction - trend: {}, momentum: {}, prediction: {}", trend, momentum, prediction);
        PricePrediction pred = new PricePrediction(symbol, prediction, confidence, signal);
        pred.setTrendValue(trend);
        pred.setMomentum(momentum);
        pred.setRsiFactor((50 - rsi) / 100);
        return pred;
    }

    private List<PriceUpdate> filterRecentData(List<PriceUpdate> data, int hours) {
        long cutoffTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L);
        return data.stream()
                .filter(update -> update.getTimestamp() >= cutoffTime)
                .collect(Collectors.toList());
    }

    private List<ChartPattern> detectLongTermPatterns(String symbol, double currentPrice,
            List<PriceUpdate> historicalData) {
        List<ChartPattern> patterns = new ArrayList<>();

        if (historicalData.size() < 20) {
            patterns.add(new ChartPattern(symbol, "INSUFFICIENT_DATA", currentPrice, 0.1,
                    "Need more historical data for pattern detection", System.currentTimeMillis()));
            return patterns;
        }

        double weeklyTrend = calculateWeeklyTrend(historicalData);
        double volatility = calculateWeeklyVolatility(historicalData);
        double support = findWeeklySupport(historicalData);
        double resistance = findWeeklyResistance(historicalData);

        // Detect weekly patterns
        if (weeklyTrend > 0.03 && volatility < 0.08) {
            patterns.add(new ChartPattern(symbol, "WEEKLY_UPTREND", currentPrice, 0.8,
                    "Strong weekly bullish trend with controlled volatility", System.currentTimeMillis()));
        } else if (weeklyTrend < -0.03 && volatility < 0.08) {
            patterns.add(new ChartPattern(symbol, "WEEKLY_DOWNTREND", currentPrice, 0.8,
                    "Strong weekly bearish trend with controlled volatility", System.currentTimeMillis()));
        } else if (volatility > 0.12) {
            patterns.add(new ChartPattern(symbol, "HIGH_WEEKLY_VOLATILITY", currentPrice, 0.7,
                    "Elevated weekly volatility indicates uncertainty", System.currentTimeMillis()));
        } else {
            patterns.add(new ChartPattern(symbol, "WEEKLY_CONSOLIDATION", currentPrice, 0.6,
                    "Price consolidating within weekly range", System.currentTimeMillis()));
        }

        // Weekly support/resistance detection
        if (Math.abs(currentPrice - support) / currentPrice < 0.03) {
            patterns.add(new ChartPattern(symbol, "WEEKLY_SUPPORT", support, 0.85,
                    "Approaching significant weekly support level", System.currentTimeMillis()));
        }

        if (Math.abs(currentPrice - resistance) / currentPrice < 0.03) {
            patterns.add(new ChartPattern(symbol, "WEEKLY_RESISTANCE", resistance, 0.85,
                    "Approaching significant weekly resistance level", System.currentTimeMillis()));
        }

        return patterns;
    }

    private List<FibonacciTimeZone> calculateWeeklyFibonacci(String symbol, List<PriceUpdate> historicalData) {
        List<FibonacciTimeZone> zones = new ArrayList<>();

        if (historicalData.size() < 20) {
            return zones; // Not enough data for meaningful Fibonacci
        }

        long now = System.currentTimeMillis();
        long oneWeekMs = 7 * 24 * 60 * 60 * 1000L;

        double weeklyLow = historicalData.stream().mapToDouble(PriceUpdate::getPrice).min().orElse(0.0);
        double weeklyHigh = historicalData.stream().mapToDouble(PriceUpdate::getPrice).max().orElse(0.0);
        double weeklyRange = weeklyHigh - weeklyLow;

        // Extended Fibonacci levels for weekly analysis
        double[] fibLevels = { 0.146, 0.236, 0.382, 0.5, 0.618, 0.786, 0.886 };
        String[] fibNames = {
                "MINOR_RESISTANCE", "WEAK_RESISTANCE", "MODERATE_RESISTANCE",
                "STRONG_RESISTANCE", "MODERATE_SUPPORT", "WEAK_SUPPORT", "MINOR_SUPPORT"
        };

        for (int i = 0; i < fibLevels.length; i++) {
            double levelPrice = weeklyHigh - (weeklyRange * fibLevels[i]);
            double strength = 0.4 + (fibLevels[i] * 0.6);
            String bias = levelPrice > weeklyHigh - (weeklyRange * 0.5) ? "RESISTANCE" : "SUPPORT";

            zones.add(new FibonacciTimeZone(
                    symbol,
                    fibNames[i],
                    now,
                    now + oneWeekMs,
                    levelPrice,
                    levelPrice,
                    strength,
                    String.format("Weekly Fibonacci %.1f%%", fibLevels[i] * 100),
                    bias));
        }

        return zones;
    }

    private double calculatePriceTrend(List<PriceUpdate> data) {
        if (data.size() < 5)
            return 0.0;

        // Use exponential weighting - more recent prices have higher weight
        double totalWeight = 0;
        double weightedSum = 0;
        int size = data.size();

        for (int i = 0; i < size; i++) {
            // Exponential weight: recent = 1.0, oldest = 0.1
            double weight = Math.exp((i - size + 1) * 0.1);
            totalWeight += weight;
            weightedSum += data.get(i).getPrice() * weight;
        }

        double weightedAverage = weightedSum / totalWeight;
        double firstPrice = data.getFirst().getPrice();
        return (weightedAverage - firstPrice) / firstPrice;
    }

    private double calculateVolatility(List<PriceUpdate> data) {
        if (data.size() < 2)
            return 0.0;
        double sum = 0.0;
        double mean = data.stream().mapToDouble(PriceUpdate::getPrice).average().orElse(0.0);
        for (PriceUpdate update : data) {
            sum += Math.pow(update.getPrice() - mean, 2);
        }
        return Math.sqrt(sum / data.size()) / mean;
    }

    private double calculateMomentum(List<PriceUpdate> data) {
        if (data.size() < 3)
            return 0.0;
        int lookback = Math.min(10, data.size() - 1);
        double momentumSum = 0.0;
        for (int i = data.size() - lookback; i < data.size() - 1; i++) {
            double change = (data.get(i + 1).getPrice() - data.get(i).getPrice()) / data.get(i).getPrice();
            momentumSum += change;
        }
        return momentumSum / lookback;
    }

    private double calculateRSI(List<PriceUpdate> data) {
        if (data.size() < 14)
            return 50.0;
        double gains = 0.0;
        double losses = 0.0;
        for (int i = 1; i < Math.min(15, data.size()); i++) {
            double change = data.get(i).getPrice() - data.get(i - 1).getPrice();
            if (change > 0)
                gains += change;
            else
                losses -= change;
        }
        if (losses == 0)
            return 100.0;
        double rs = gains / losses;
        return 100.0 - (100.0 / (1 + rs));
    }

    private String getTrendDirection(double trend, double momentum, double rsi) {
        boolean strongBullish = trend > 0.03 && momentum > 0 && rsi > 60;
        boolean bullish = trend > 0 || (momentum > 0 && rsi > 50);
        boolean strongBearish = trend < -0.03 && momentum < 0 && rsi < 40;
        boolean bearish = trend < 0 || (momentum < 0 && rsi < 50);
        if (strongBullish)
            return "STRONG_BULLISH";
        if (bullish)
            return "BULLISH";
        if (strongBearish)
            return "STRONG_BEARISH";
        if (bearish)
            return "BEARISH";
        return "NEUTRAL";
    }

    private double randomChange(double maxChange) {
        return (random.nextDouble() * 2 * maxChange) - maxChange;
    }

    private Map<String, PricePrediction> createConservativePredictions(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new HashMap<>();
        double smallRandomChange = randomChange(0.01);
        predictions.put("1day",
                new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 2), 0.5, "NEUTRAL"));
        predictions.put("1week",
                new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 3), 0.4, "NEUTRAL"));
        predictions.put("1month",
                new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 5), 0.3, "NEUTRAL"));
        return predictions;
    }
}