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
    private TrainingDataService trainingDataService;

    @Autowired
    private MarketDataService marketDataService;

    private final Random random = new Random();

    public void trainModels() {
        log.info("🤖 Starting AI model training...");
        trainingDataService.collectSymbolTrainingData("BTCUSDT", "1h");
        trainingDataService.collectSymbolTrainingData("ETHUSDT", "1h");
        trainingDataService.collectSymbolTrainingData("SOLUSDT", "1h");
        log.info("✅ AI model training completed");
    }

    public AIAnalysisResult analyzeMarketData(String symbol, double currentPrice) {

        // GET ENHANCED HISTORICAL DATA (7 days + real-time)
        List<PriceUpdate> historicalData = marketDataService.getHistoricalData(symbol, 200);

        double daysCovered = calculateDaysCovered(historicalData);

        // MULTI-TIMEFRAME ANALYSIS
        Map<String, PricePrediction> timeframePredictions = calculateMultiTimeframePredictions(
                symbol, currentPrice, historicalData
        );

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
                symbol, currentPrice, historicalData
        );

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
                        cp.getPrice(),     // price
                        cp.getVolume(),    // volume
                        cp.getTimestamp(), // timestamp
                        cp.getPrice(),     // open (use price if not available)
                        cp.getPrice(),     // high (use price if not available)
                        cp.getPrice(),     // low (use price if not available)
                        cp.getPrice()      // close (use price if not available)
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

        Map<String, PricePrediction> predictions = new HashMap<>();

        // Focus on timeframes that actually work well
        PricePrediction mediumTerm1D = calculate1DPrediction(symbol, currentPrice,
                filterRecentData(historicalData, 168)); // Last 7 days - enough for 1D analysis

        PricePrediction longTerm1W = calculate1WPrediction(symbol, currentPrice,
                filterRecentData(historicalData, 720)); // Last 30 days - enough for 1W analysis

        PricePrediction longTerm1M = calculate1MPrediction(symbol, currentPrice,
                historicalData); // Use ALL historical data for monthly

        predictions.put("1day", mediumTerm1D);
        predictions.put("1week", longTerm1W);
        predictions.put("1month", longTerm1M);

        log.debug("🤖 Generated predictions for {}: 1D=${}, 1W=${}, 1M=${}",
                symbol,
                mediumTerm1D.getPredictedPrice(),
                longTerm1W.getPredictedPrice(),
                longTerm1M.getPredictedPrice());

        return predictions;
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
                timeframe, minPrice, maxPrice, data.get(data.size()-1).getPrice());
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





    private PricePrediction calculateShortTermPrediction(String symbol, double currentPrice,
                                                         List<PriceUpdate> recentData, String timeframe) {
        if (recentData.size() < 5) {
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        double prediction;
        double confidence;
        String signal;

        switch(timeframe) {
            case "1H":
                prediction = calculate1HPrediction(currentPrice, recentData);
                confidence = 0.7;
                signal = "NEUTRAL";
                break;
            case "4H":
                prediction = calculate4HPrediction(currentPrice, recentData);
                confidence = 0.65;
                signal = "NEUTRAL";
                break;
            case "1D":
                prediction = calculate1DPrediction(currentPrice, recentData);
                confidence = 0.6;
                signal = "NEUTRAL";
                break;
            default:
                prediction = currentPrice; // No change as fallback
                confidence = 0.3;
                signal = "NEUTRAL";
        }

        log.info("📊 {} prediction: current=${} predicted=${}",
                timeframe, currentPrice, prediction);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private double calculate1HPrediction(double currentPrice, List<PriceUpdate> data) {
        double trend = calculatePriceTrend(data);
        double momentum = calculateMomentum(data);
        // 1H: Very short-term, mostly momentum-based
        return currentPrice * (1 + (momentum * 0.5));
    }

    private double calculate4HPrediction(double currentPrice, List<PriceUpdate> data) {
        double trend = calculatePriceTrend(data);
        double momentum = calculateMomentum(data);
        // 4H: Balance of momentum and trend
        return currentPrice * (1 + (trend * 0.3 + momentum * 0.2));
    }

    private double calculate1DPrediction(double currentPrice, List<PriceUpdate> data) {
        double trend = calculatePriceTrend(data);
        // 1D: More trend-focused, less momentum
        return currentPrice * (1 + (trend * 0.5));
    }

    private PricePrediction calculateMediumTermPrediction(String symbol, double currentPrice, List<PriceUpdate> mediumTermData) {
        if (mediumTermData.size() < 10) {
            return new PricePrediction(symbol, currentPrice, 0.4, "NEUTRAL");
        }

        double trend = calculatePriceTrend(mediumTermData);
        double volatility = calculateVolatility(mediumTermData);
        double momentum = calculateMomentum(mediumTermData);
        double rsi = calculateRSI(mediumTermData);

        double prediction = calculateDailyPrediction(currentPrice, trend, volatility, momentum, rsi);
        double confidence = adjustConfidenceForTimeframe(0.7, 0.95, volatility);
        String signal = getTrendDirection(trend, momentum, rsi);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private PricePrediction calculateLongTermPrediction(String symbol, double currentPrice, List<PriceUpdate> fullDataset) {
        if (fullDataset.size() < 30) {  // Increased minimum data requirement
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        // Weekly analysis using full dataset
        double weeklyTrend = calculateWeeklyTrend(fullDataset);
        double support = findWeeklySupport(fullDataset);
        double resistance = findWeeklyResistance(fullDataset);
        double volatility = calculateWeeklyVolatility(fullDataset);


        double trendAdjusted = weeklyTrend * 0.8; // Reduce trend impact
        double volatilityAdjustment = 1.0 - (volatility * 2); // Reduce prediction during high volatility

        double predictedPrice = currentPrice * (1 + Math.max(-0.15, Math.min(0.15, trendAdjusted * volatilityAdjustment)));
        double confidence = calculateLongTermConfidence(weeklyTrend, volatility, fullDataset.size());
        String trend = getWeeklyTrendDirection(weeklyTrend, support, resistance, currentPrice);

        return new PricePrediction(symbol, predictedPrice, confidence, trend);
    }

    private PricePrediction calculateOneMonthPrediction(String symbol, double currentPrice, List<PriceUpdate> historicalData) {
        if (historicalData.size() < 60) { // Need at least 60 days for monthly prediction
            return new PricePrediction(symbol, currentPrice, 0.2, "NEUTRAL");
        }

        try {
            // Use monthly trend analysis
            double monthlyTrend = calculateMonthlyTrend(historicalData);
            double volatility = calculateMonthlyVolatility(historicalData);

            // Very conservative monthly prediction
            double monthlyChange = Math.max(-0.25, Math.min(0.25, monthlyTrend * 0.5));
            double predictedPrice = currentPrice * (1 + monthlyChange);

            // Lower confidence for monthly predictions
            double confidence = Math.max(0.1, 0.4 - (volatility * 2));
            String trend = monthlyChange > 0.02 ? "BULLISH" : monthlyChange < -0.02 ? "BEARISH" : "NEUTRAL";

            return new PricePrediction(symbol, predictedPrice, confidence, trend);

        } catch (Exception e) {
            log.warn("❌ Monthly prediction failed for {}: {}", symbol, e.getMessage());
            return new PricePrediction(symbol, currentPrice, 0.1, "NEUTRAL");
        }
    }

    private double calculateMonthlyTrend(List<PriceUpdate> data) {
        if (data.size() < 60) return 0.0;

        // Compare first month vs last month
        int monthlyPoints = Math.max(30, data.size() / 2);
        double earlyAverage = data.stream()
                .limit(monthlyPoints)
                .mapToDouble(PriceUpdate::getPrice)
                .average()
                .orElse(0.0);

        double recentAverage = data.stream()
                .skip(data.size() - monthlyPoints)
                .mapToDouble(PriceUpdate::getPrice)
                .average()
                .orElse(0.0);

        return (recentAverage - earlyAverage) / earlyAverage;
    }

    private double calculateMonthlyVolatility(List<PriceUpdate> data) {
        if (data.size() < 30) return 0.0;

        // Calculate monthly volatility (standard deviation of weekly returns)
        List<Double> weeklyReturns = new ArrayList<>();
        for (int i = 7; i < data.size(); i += 7) {
            if (i < data.size()) {
                double returnRate = (data.get(i).getPrice() - data.get(i-7).getPrice()) / data.get(i-7).getPrice();
                weeklyReturns.add(returnRate);
            }
        }

        if (weeklyReturns.isEmpty()) return 0.0;

        double meanReturn = weeklyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = weeklyReturns.stream()
                .mapToDouble(r -> Math.pow(r - meanReturn, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    private double calculateWeeklyTrend(List<PriceUpdate> data) {
        if (data.size() < 20) return 0.0;

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
        if (data.size() < 10) return 0.0;

        // Calculate weekly volatility (standard deviation of daily returns)
        List<Double> dailyReturns = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            double returnRate = (data.get(i).getPrice() - data.get(i-1).getPrice()) / data.get(i-1).getPrice();
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
        if (data.isEmpty()) return 0.0;

        // Find significant support level (lowest 10% of prices)
        List<Double> prices = data.stream()
                .map(PriceUpdate::getPrice)
                .sorted()
                .collect(Collectors.toList());

        int supportIndex = Math.max(0, prices.size() / 10); // 10th percentile
        return prices.get(supportIndex);
    }

    private double findWeeklyResistance(List<PriceUpdate> data) {
        if (data.isEmpty()) return 0.0;

        // Find significant resistance level (highest 10% of prices)
        List<Double> prices = data.stream()
                .map(PriceUpdate::getPrice)
                .sorted()
                .collect(Collectors.toList());

        int resistanceIndex = Math.min(prices.size() - 1, prices.size() * 9 / 10); // 90th percentile
        return prices.get(resistanceIndex);
    }

    private double calculateLongTermConfidence(double weeklyTrend, double volatility, int dataPoints) {
        double trendStrength = Math.min(1.0, Math.abs(weeklyTrend) * 10);
        double dataQuality = Math.min(1.0, dataPoints / 50.0);
        double volatilityPenalty = Math.max(0.3, 1.0 - (volatility * 3));

        return 0.4 + (trendStrength * 0.3) + (dataQuality * 0.2) + (volatilityPenalty * 0.1);
    }

    private String getWeeklyTrendDirection(double weeklyTrend, double support, double resistance, double currentPrice) {
        if (weeklyTrend > 0.05 && currentPrice > resistance * 0.95) {
            return "STRONG_BULLISH";
        } else if (weeklyTrend > 0.02) {
            return "BULLISH";
        } else if (weeklyTrend < -0.05 && currentPrice < support * 1.05) {
            return "STRONG_BEARISH";
        } else if (weeklyTrend < -0.02) {
            return "BEARISH";
        } else {
            return "NEUTRAL";
        }
    }

    private double calculateDaysCovered(List<PriceUpdate> data) {
        if (data.size() < 2) return 0.0;

        long startTime = data.get(0).getTimestamp();
        long endTime = data.get(data.size() - 1).getTimestamp();
        long durationMs = endTime - startTime;

        return durationMs / (1000.0 * 60 * 60 * 24); // Convert to days
    }

    private PricePrediction calculate1HPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 5) {
            debugTechnicalIndicators(data, "1H");
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        log.info("🔍 1H Inputs - trend: {}, momentum: {}, volatility: {}, rsi: {}, dataSize: {}",
                trend, momentum, volatility, rsi, data.size());

        double prediction = calculateDynamicPrediction(currentPrice, trend, momentum, volatility, "1H");
        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1H");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1H Output - prediction: {}, confidence: {}, signal: {}", prediction, confidence, signal);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private PricePrediction calculate4HPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 5) {
            debugTechnicalIndicators(data, "4H");
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        double prediction = calculateDynamicPrediction(currentPrice, trend, momentum, volatility, "4H");
        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "4H");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 4H Prediction - trend: {}, momentum: {}, volatility: {}, prediction: {}",
                trend, momentum, volatility, prediction);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private PricePrediction calculate1DPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 10) {
            debugTechnicalIndicators(data, "1D");
            return new PricePrediction(symbol, currentPrice, 0.4, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        double prediction = calculateDynamicPrediction(currentPrice, trend, momentum, volatility, "1D");
        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1D");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1D Prediction - trend: {}, momentum: {}, volatility: {}, prediction: {}",
                trend, momentum, volatility, prediction);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private PricePrediction calculate1WPrediction(String symbol, double currentPrice, List<PriceUpdate> data) {
        if (data.size() < 20) {
            debugTechnicalIndicators(data, "1W");
            return new PricePrediction(symbol, currentPrice, 0.3, "NEUTRAL");
        }

        double trend = calculatePriceTrend(data);
        double volatility = calculateVolatility(data);
        double momentum = calculateMomentum(data);
        double rsi = calculateRSI(data);

        double prediction = calculateDynamicPrediction(currentPrice, trend, momentum, volatility, "1W");
        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1W");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1W Prediction - trend: {}, momentum: {}, volatility: {}, prediction: {}",
                trend, momentum, volatility, prediction);

        return new PricePrediction(symbol, prediction, confidence, signal);
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

        double prediction = calculateDynamicPrediction(currentPrice, trend, momentum, volatility, "1M");
        double confidence = calculateDynamicConfidence(trend, volatility, data.size(), "1M");
        String signal = getTrendDirection(trend, momentum, rsi);

        log.info("🔍 1M Prediction - trend: {}, momentum: {}, volatility: {}, prediction: {}",
                trend, momentum, volatility, prediction);

        return new PricePrediction(symbol, prediction, confidence, signal);
    }

    private double calculateDynamicPrediction(double currentPrice, double trend, double momentum,
                                              double volatility, String timeframe) {


        switch(timeframe) {
            case "1H":
                // 1H: Add small random noise + focus on very recent momentum
                double hourlyNoise = (random.nextDouble() - 0.5) * 0.002; // ±0.1% noise
                double hourlyChange = (momentum * 0.2 + hourlyNoise);
                return currentPrice * (1 + Math.max(-0.02, Math.min(0.02, hourlyChange)));

            case "4H":
                // 4H: Different calculation approach with trend emphasis
                double fourHourNoise = (random.nextDouble() - 0.5) * 0.003; // ±0.15% noise
                double fourHourChange = (trend * 0.3 + momentum * 0.1 + fourHourNoise);
                return currentPrice * (1 + Math.max(-0.03, Math.min(0.03, fourHourChange)));

            case "1D":
                // 1D: More trend-focused with different bounds
                double dailyNoise = (random.nextDouble() - 0.5) * 0.004; // ±0.2% noise
                double dailyChange = (trend * 0.5 + dailyNoise);
                return currentPrice * (1 + Math.max(-0.05, Math.min(0.05, dailyChange)));

            case "1W":
                // 1W: Even more trend-focused with wider bounds
                double weeklyNoise = (random.nextDouble() - 0.5) * 0.005; // ±0.25% noise
                double weeklyChange = (trend * 0.8 + weeklyNoise);
                return currentPrice * (1 + Math.max(-0.08, Math.min(0.08, weeklyChange)));

            case "1M":
                // 1M: Keep your existing monthly logic
                double monthlyChange = calculateMonthlyTrendAdjustment(trend, volatility);
                return currentPrice * (1 + Math.max(-0.3, Math.min(0.3, monthlyChange)));

            default:
                return currentPrice;
        }
    }

    private double calculateMonthlyTrendAdjustment(double trend, double volatility) {
        // Monthly predictions should be more conservative and different from others
        double adjustedTrend = trend * 0.8; // Reduce trend impact
        double volatilityImpact = Math.max(-0.3, Math.min(0.3, volatility * -2)); // Negative correlation with volatility
        return adjustedTrend + volatilityImpact;
    }

    private List<PriceUpdate> filterRecentData(List<PriceUpdate> data, int hours) {
        long cutoffTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L);
        return data.stream()
                .filter(update -> update.getTimestamp() >= cutoffTime)
                .collect(Collectors.toList());
    }

    private double calculateDynamicConfidence(double trend, double volatility, int dataSize, String timeframe) {
        double baseConfidence = getBaseConfidence(timeframe);
        double trendStrength = Math.min(1.0, Math.abs(trend) * 10);
        double dataQuality = Math.min(1.0, dataSize / 50.0);
        double volatilityPenalty = Math.max(0.3, 1.0 - (volatility * 3));

        return Math.max(0.1, baseConfidence * (0.3 + trendStrength * 0.3 + dataQuality * 0.2 + volatilityPenalty * 0.2));
    }

    private double getTimeframeMultiplier(String timeframe) {
        switch(timeframe) {
            case "1H": return 0.8;   // Less aggressive for short term
            case "4H": return 1.0;   // Standard
            case "1D": return 1.2;   // More aggressive for daily
            case "1W": return 1.5;   // Even more for weekly
            case "1M": return 2.0;   // Most aggressive for monthly
            default: return 1.0;
        }
    }

    /**
     * NEW: Base confidence per timeframe
     */
    private double getBaseConfidence(String timeframe) {
        switch(timeframe) {
            case "1H": return 0.6;   // Lower for very short term
            case "4H": return 0.65;
            case "1D": return 0.7;
            case "1W": return 0.75;  // Higher for longer term
            case "1M": return 0.8;   // Highest for monthly (more data)
            default: return 0.5;
        }
    }

// Use different time windows for different predictions:
// 1H: filterRecentData(historicalData, 24)  // 24 hours
// 4H: filterRecentData(historicalData, 48)  // 48 hours
// 1D: filterRecentData(historicalData, 168) // 7 days
// 1W: filterRecentData(historicalData, 720) // 30 days
// 1M: use full dataset or 90 days

    private List<ChartPattern> detectLongTermPatterns(String symbol, double currentPrice, List<PriceUpdate> historicalData) {
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
        double[] fibLevels = {0.146, 0.236, 0.382, 0.5, 0.618, 0.786, 0.886};
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
                    bias
            ));
        }

        return zones;
    }

    // KEEP ALL YOUR EXISTING HELPER METHODS - they're still used for short-term analysis
    private double calculatePriceTrend(List<PriceUpdate> data) {
        if (data.size() < 2) return 0.0;
        double totalWeight = 0;
        double weightedSum = 0;
        for (int i = 0; i < data.size(); i++) {
            double weight = (i + 1) / (double) data.size();
            totalWeight += weight;
            weightedSum += data.get(i).getPrice() * weight;
        }
        double weightedAverage = weightedSum / totalWeight;
        double firstPrice = data.get(0).getPrice();
        return (weightedAverage - firstPrice) / firstPrice;
    }

    private double calculateVolatility(List<PriceUpdate> data) {
        if (data.size() < 2) return 0.0;
        double sum = 0.0;
        double mean = data.stream().mapToDouble(PriceUpdate::getPrice).average().orElse(0.0);
        for (PriceUpdate update : data) {
            sum += Math.pow(update.getPrice() - mean, 2);
        }
        return Math.sqrt(sum / data.size()) / mean;
    }

    private double calculateMomentum(List<PriceUpdate> data) {
        if (data.size() < 3) return 0.0;
        int lookback = Math.min(10, data.size() - 1);
        double momentumSum = 0.0;
        for (int i = data.size() - lookback; i < data.size() - 1; i++) {
            double change = (data.get(i + 1).getPrice() - data.get(i).getPrice()) / data.get(i).getPrice();
            momentumSum += change;
        }
        return momentumSum / lookback;
    }

    private double calculateRSI(List<PriceUpdate> data) {
        if (data.size() < 14) return 50.0;
        double gains = 0.0;
        double losses = 0.0;
        for (int i = 1; i < Math.min(15, data.size()); i++) {
            double change = data.get(i).getPrice() - data.get(i-1).getPrice();
            if (change > 0) gains += change;
            else losses -= change;
        }
        if (losses == 0) return 100.0;
        double rs = gains / losses;
        return 100.0 - (100.0 / (1 + rs));
    }

    private double calculateHourlyPrediction(double currentPrice, double trend, double volatility, double momentum, double rsi) {
        double baseChange = trend * 0.3 + momentum * 2.0;
        double volatilityEffect = randomChange(volatility * 0.5);
        double rsiEffect = (rsi - 50) * 0.001;
        return currentPrice * (1 + baseChange + volatilityEffect + rsiEffect);
    }

    private double calculateDailyPrediction(double currentPrice, double trend, double volatility, double momentum, double rsi) {
        double baseChange = trend * 1.2 + momentum * 0.8;
        double volatilityEffect = randomChange(volatility * 0.2);
        double rsiEffect = (rsi - 50) * 0.003;
        return currentPrice * (1 + baseChange + volatilityEffect + rsiEffect);
    }

    private double adjustConfidenceForTimeframe(double baseConfidence, double timeframeFactor, double volatility) {
        double volatilityAdjustment = 1.0 - (volatility * 0.5);
        return Math.max(0.1, Math.min(0.95, baseConfidence * timeframeFactor * volatilityAdjustment));
    }

    private String getTrendDirection(double trend, double momentum, double rsi) {
        boolean strongBullish = trend > 0.03 && momentum > 0 && rsi > 60;
        boolean bullish = trend > 0 || (momentum > 0 && rsi > 50);
        boolean strongBearish = trend < -0.03 && momentum < 0 && rsi < 40;
        boolean bearish = trend < 0 || (momentum < 0 && rsi < 50);
        if (strongBullish) return "STRONG_BULLISH";
        if (bullish) return "BULLISH";
        if (strongBearish) return "STRONG_BEARISH";
        if (bearish) return "BEARISH";
        return "NEUTRAL";
    }

    private double randomChange(double maxChange) {
        return (random.nextDouble() * 2 * maxChange) - maxChange;
    }

    private Map<String, PricePrediction> createConservativePredictions(String symbol, double currentPrice) {
        Map<String, PricePrediction> predictions = new HashMap<>();
        double smallRandomChange = randomChange(0.01);
        predictions.put("1hour", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange), 0.3, "NEUTRAL"));
        predictions.put("4hour", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 1.5), 0.4, "NEUTRAL"));
        predictions.put("1day", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 2), 0.5, "NEUTRAL"));
        predictions.put("1week", new PricePrediction(symbol, currentPrice * (1 + smallRandomChange * 3), 0.4, "NEUTRAL"));
        return predictions;
    }
}