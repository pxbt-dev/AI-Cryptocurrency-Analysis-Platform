package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.*;
import com.pxbt.dev.aiTradingCharts.util.Ta4jConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.*;

@Slf4j
@Service
public class ChartPatternService {

    public List<ChartPattern> detectPatterns(String symbol, List<CryptoPrice> prices) {
        List<ChartPattern> patterns = new ArrayList<>();

        if (prices.size() < 20) {
            log.debug("Insufficient data for pattern detection: {} points", prices.size());
            return patterns;
        }

        try {
            // Convert to ta4j BarSeries for better pattern recognition sub-analysis
            BarSeries series = Ta4jConverter.toSeries(symbol, prices);
            double[] priceArray = prices.stream()
                    .mapToDouble(CryptoPrice::getPrice)
                    .toArray();

            // Detect various patterns
            patterns.addAll(detectSupportResistance(symbol, priceArray, prices));
            patterns.addAll(detectTrendLines(symbol, series, prices));
            patterns.addAll(detectChartPatterns(symbol, series, prices));
            patterns.addAll(detectCandlestickPatterns(symbol, prices));

            // Sort by confidence (highest first)
            patterns.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));

            log.debug("🧩 Detected {} patterns for {}", patterns.size(), symbol);

        } catch (Exception e) {
            log.error("❌ Pattern detection failed for {}: {}", symbol, e.getMessage());
        }

        return patterns;
    }

    private List<ChartPattern> detectSupportResistance(String symbol, double[] prices, List<CryptoPrice> priceObjects) {
        List<ChartPattern> patterns = new ArrayList<>();

        // Simple support/resistance detection using swing points
        List<Double> swingHighs = findSwingHighs(prices, 3);
        List<Double> swingLows = findSwingLows(prices, 3);

        // Cluster similar levels
        Map<Double, Integer> resistanceLevels = clusterLevels(swingHighs, 0.02); // 2% tolerance
        Map<Double, Integer> supportLevels = clusterLevels(swingLows, 0.02);

        // Add resistance levels
        for (Map.Entry<Double, Integer> entry : resistanceLevels.entrySet()) {
            if (entry.getValue() >= 2) { // At least 2 touches
                double currentPrice = prices[prices.length - 1];
                double distance = Math.abs(entry.getKey() - currentPrice) / currentPrice;

                patterns.add(new ChartPattern(
                        symbol,
                        "RESISTANCE",
                        entry.getKey(),
                        calculateSupportResistanceConfidence(entry.getValue(), distance),
                        "Price rejected " + entry.getValue() + " times",
                        getCurrentTimestamp()
                ));
            }
        }

        // Add support levels
        for (Map.Entry<Double, Integer> entry : supportLevels.entrySet()) {
            if (entry.getValue() >= 2) {
                double currentPrice = prices[prices.length - 1];
                double distance = Math.abs(entry.getKey() - currentPrice) / currentPrice;

                patterns.add(new ChartPattern(
                        symbol,
                        "SUPPORT",
                        entry.getKey(),
                        calculateSupportResistanceConfidence(entry.getValue(), distance),
                        "Price bounced " + entry.getValue() + " times",
                        getCurrentTimestamp()
                ));
            }
        }

        return patterns;
    }

    private List<ChartPattern> detectTrendLines(String symbol, BarSeries series, List<CryptoPrice> priceObjects) {
        List<ChartPattern> patterns = new ArrayList<>();
        double[] prices = priceObjects.stream().mapToDouble(CryptoPrice::getPrice).toArray();

        // Detect uptrend (higher highs and higher lows)
        boolean uptrend = detectUptrend(prices, 10);
        boolean downtrend = detectDowntrend(prices, 10);

        if (uptrend) {
            double trendStrength = calculateTrendStrength(series, true);
            patterns.add(new ChartPattern(
                    symbol,
                    "UPTREND",
                    prices[prices.length - 1],
                    trendStrength,
                    "Higher highs and higher lows",
                    getCurrentTimestamp()
            ));
        }

        if (downtrend) {
            double trendStrength = calculateTrendStrength(series, false);
            patterns.add(new ChartPattern(
                    symbol,
                    "DOWNTREND",
                    prices[prices.length - 1],
                    trendStrength,
                    "Lower highs and lower lows",
                    getCurrentTimestamp()
            ));
        }

        return patterns;
    }

    private List<ChartPattern> detectChartPatterns(String symbol, BarSeries series, List<CryptoPrice> priceObjects) {
        List<ChartPattern> patterns = new ArrayList<>();
        double[] prices = priceObjects.stream().mapToDouble(CryptoPrice::getPrice).toArray();

        // Head and Shoulders
        ChartPattern headShoulders = detectHeadAndShoulders(prices);
        if (headShoulders != null) patterns.add(headShoulders);

        // Double Top/Bottom
        ChartPattern doubleTop = detectDoubleTop(prices);
        if (doubleTop != null) patterns.add(doubleTop);

        ChartPattern doubleBottom = detectDoubleBottom(prices);
        if (doubleBottom != null) patterns.add(doubleBottom);

        // Triangle Patterns
        ChartPattern triangle = detectTriangle(symbol, series, prices);
        if (triangle != null) patterns.add(triangle);

        return patterns;
    }

    private List<ChartPattern> detectCandlestickPatterns(String symbol, List<CryptoPrice> prices) {
        List<ChartPattern> patterns = new ArrayList<>();

        if (prices.size() < 3) return patterns;

        // Get last few prices for candlestick analysis
        int end = prices.size();
        int start = Math.max(0, end - 5); // Last 5 candles

        List<CryptoPrice> recent = prices.subList(start, end);

        // Simple candlestick pattern detection
        if (isBullishEngulfing(recent)) {
            patterns.add(new ChartPattern(
                    symbol, "BULLISH_ENGULFING",
                    recent.get(recent.size()-1).getPrice(), 0.7,
                    "Bullish reversal pattern", getCurrentTimestamp()
            ));
        }

        if (isBearishEngulfing(recent)) {
            patterns.add(new ChartPattern(
                    symbol, "BEARISH_ENGULFING",
                    recent.get(recent.size()-1).getPrice(), 0.7,
                    "Bearish reversal pattern", getCurrentTimestamp()
            ));
        }

        if (isDoji(recent)) {
            patterns.add(new ChartPattern(
                    symbol, "DOJI",
                    recent.get(recent.size()-1).getPrice(), 0.6,
                    "Indecision pattern", getCurrentTimestamp()
            ));
        }

        return patterns;
    }

    // ===== PATTERN DETECTION ALGORITHMS =====

    private List<Double> findSwingHighs(double[] prices, int window) {
        List<Double> highs = new ArrayList<>();
        for (int i = window; i < prices.length - window; i++) {
            boolean isHigh = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && prices[j] >= prices[i]) {
                    isHigh = false;
                    break;
                }
            }
            if (isHigh) highs.add(prices[i]);
        }
        return highs;
    }

    private List<Double> findSwingLows(double[] prices, int window) {
        List<Double> lows = new ArrayList<>();
        for (int i = window; i < prices.length - window; i++) {
            boolean isLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && prices[j] <= prices[i]) {
                    isLow = false;
                    break;
                }
            }
            if (isLow) lows.add(prices[i]);
        }
        return lows;
    }

    private Map<Double, Integer> clusterLevels(List<Double> levels, double tolerance) {
        Map<Double, Integer> clusters = new HashMap<>();
        for (Double level : levels) {
            boolean foundCluster = false;
            for (Double cluster : clusters.keySet()) {
                if (Math.abs(level - cluster) / cluster <= tolerance) {
                    clusters.put(cluster, clusters.get(cluster) + 1);
                    foundCluster = true;
                    break;
                }
            }
            if (!foundCluster) {
                clusters.put(level, 1);
            }
        }
        return clusters;
    }

    private boolean detectUptrend(double[] prices, int period) {
        if (prices.length < period * 2) return false;

        // Check for higher highs
        boolean higherHighs = true;
        for (int i = prices.length - period; i < prices.length - 1; i++) {
            if (prices[i] <= prices[i - period]) {
                higherHighs = false;
                break;
            }
        }

        // Check for higher lows
        boolean higherLows = true;
        for (int i = prices.length - period + 1; i < prices.length; i++) {
            if (prices[i] <= prices[i - period + 1]) {
                higherLows = false;
                break;
            }
        }

        return higherHighs && higherLows;
    }

    private boolean detectDowntrend(double[] prices, int period) {
        if (prices.length < period * 2) return false;

        // Check for lower highs
        boolean lowerHighs = true;
        for (int i = prices.length - period; i < prices.length - 1; i++) {
            if (prices[i] >= prices[i - period]) {
                lowerHighs = false;
                break;
            }
        }

        // Check for lower lows
        boolean lowerLows = true;
        for (int i = prices.length - period + 1; i < prices.length; i++) {
            if (prices[i] >= prices[i - period + 1]) {
                lowerLows = false;
                break;
            }
        }

        return lowerHighs && lowerLows;
    }

    private double calculateTrendStrength(BarSeries series, boolean isUptrend) {
        if (series.getBarCount() < 10) return 0.5;

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator fast = new SMAIndicator(close, 5);
        SMAIndicator slow = new SMAIndicator(close, 20);
        
        int lastIdx = series.getEndIndex();
        double trend = (fast.getValue(lastIdx).doubleValue() - slow.getValue(lastIdx).doubleValue()) 
                        / slow.getValue(lastIdx).doubleValue();
        
        // Normalize to 0.5-0.9 based on deviation
        double normalized = 0.7 + (trend * 2.0);
        return Math.min(0.9, Math.max(0.5, normalized));
    }

    private ChartPattern detectHeadAndShoulders(double[] prices) {
        if (prices.length < 10) return null;

        // Simplified H&S detection
        int mid = prices.length / 2;
        double leftShoulder = Arrays.stream(prices, 0, mid/2).max().orElse(0);
        double head = Arrays.stream(prices, mid/2, mid*3/2).max().orElse(0);
        double rightShoulder = Arrays.stream(prices, mid*3/2, prices.length).max().orElse(0);

        // Head should be significantly higher than shoulders
        if (head > leftShoulder * 1.02 && head > rightShoulder * 1.02) {
            // Shoulders should be roughly equal
            if (Math.abs(leftShoulder - rightShoulder) / leftShoulder < 0.02) {
                return new ChartPattern(
                        "HEAD_SHOULDERS",
                        prices[prices.length - 1],
                        0.75,
                        "Classic reversal pattern",
                        getCurrentTimestamp()
                );
            }
        }

        return null;
    }

    private ChartPattern detectDoubleTop(double[] prices) {
        if (prices.length < 8) return null;

        int mid = prices.length / 2;
        double firstTop = Arrays.stream(prices, 0, mid).max().orElse(0);
        double secondTop = Arrays.stream(prices, mid, prices.length).max().orElse(0);

        // Tops should be roughly equal with a valley in between
        if (Math.abs(firstTop - secondTop) / firstTop < 0.02) {
            double valley = Arrays.stream(prices, mid/2, mid*3/2).min().orElse(0);
            if (valley < firstTop * 0.98) { // Significant valley
                return new ChartPattern(
                        "DOUBLE_TOP",
                        prices[prices.length - 1],
                        0.7,
                        "Bearish reversal pattern",
                        getCurrentTimestamp()
                );
            }
        }

        return null;
    }

    private ChartPattern detectDoubleBottom(double[] prices) {
        if (prices.length < 8) return null;

        int mid = prices.length / 2;
        double firstBottom = Arrays.stream(prices, 0, mid).min().orElse(0);
        double secondBottom = Arrays.stream(prices, mid, prices.length).min().orElse(0);

        // Bottoms should be roughly equal with a peak in between
        if (Math.abs(firstBottom - secondBottom) / firstBottom < 0.02) {
            double peak = Arrays.stream(prices, mid/2, mid*3/2).max().orElse(0);
            if (peak > firstBottom * 1.02) { // Significant peak
                return new ChartPattern(
                        "DOUBLE_BOTTOM",
                        prices[prices.length - 1],
                        0.7,
                        "Bullish reversal pattern",
                        getCurrentTimestamp()
                );
            }
        }

        return null;
    }

    private ChartPattern detectTriangle(String symbol, BarSeries series, double[] prices) {
        if (series.getBarCount() < 15) return null;

        // Simple triangle detection using volatility contraction via ta4j
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        int total = series.getBarCount();
        
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(close, total / 3);
        
        double earlyVolatility = stdDev.getValue(total / 3).doubleValue() / prices[total / 3];
        double midVolatility = stdDev.getValue(total * 2 / 3).doubleValue() / prices[total * 2 / 3];
        double lateVolatility = stdDev.getValue(total - 1).doubleValue() / prices[total - 1];

        if (lateVolatility < midVolatility && midVolatility < earlyVolatility) {
            return new ChartPattern(
                    symbol,
                    "TRIANGLE",
                    prices[prices.length - 1],
                    0.65,
                    "Volatility contraction - breakout expected",
                    getCurrentTimestamp()
            );
        }

        return null;
    }

    // ===== CANDLESTICK PATTERNS =====

    private boolean isBullishEngulfing(List<CryptoPrice> candles) {
        if (candles.size() < 2) return false;

        CryptoPrice prev = candles.get(candles.size() - 2);
        CryptoPrice current = candles.get(candles.size() - 1);

        // Current candle opens below previous close and closes above previous open
        return current.getPrice() > prev.getPrice() &&
                current.getVolume() > prev.getVolume() * 0.8; // Volume confirmation
    }

    private boolean isBearishEngulfing(List<CryptoPrice> candles) {
        if (candles.size() < 2) return false;

        CryptoPrice prev = candles.get(candles.size() - 2);
        CryptoPrice current = candles.get(candles.size() - 1);

        // Current candle opens above previous close and closes below previous open
        return current.getPrice() < prev.getPrice() &&
                current.getVolume() > prev.getVolume() * 0.8;
    }

    private boolean isDoji(List<CryptoPrice> candles) {
        if (candles.isEmpty()) return false;

        CryptoPrice candle = candles.get(candles.size() - 1);
        // Simplified: small body relative to recent average
        double avgBody = candles.stream()
                .mapToDouble(CryptoPrice::getPrice)
                .average()
                .orElse(1.0);

        return Math.abs(candle.getPrice() - avgBody) / avgBody < 0.01; // Very small change
    }

    // ===== UTILITY METHODS =====

    private double calculateSupportResistanceConfidence(int touches, double distance) {
        double touchConfidence = Math.min(0.8, touches * 0.2);
        double distanceConfidence = Math.max(0.2, 1 - (distance * 10)); // Closer = more confident
        return (touchConfidence + distanceConfidence) / 2;
    }

    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }
}
