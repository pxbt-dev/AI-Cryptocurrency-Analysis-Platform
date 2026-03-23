package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.WyckoffResult;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import com.pxbt.dev.aiTradingCharts.util.Ta4jConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class WyckoffAnalysisService {

    private final Map<String, CachedResult> resultsCache = new ConcurrentHashMap<>();

    private static class CachedResult {
        final WyckoffResult result;
        final long timestamp;

        CachedResult(WyckoffResult result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public boolean isCacheFresh(String symbol, String tf) {
        String cacheKey = symbol + "_" + tf;
        CachedResult cached = resultsCache.get(cacheKey);
        if (cached == null) return false;
        
        long cacheDuration = (tf.equalsIgnoreCase("1w") || tf.equalsIgnoreCase("1m")) 
            ? TimeUnit.HOURS.toMillis(1) 
            : TimeUnit.MINUTES.toMillis(15);
            
        return (System.currentTimeMillis() - cached.timestamp) < cacheDuration;
    }

    public Map<String, WyckoffResult> analyzeMultiTimeframe(String symbol, Map<String, List<PriceUpdate>> timeframeData) {
        Map<String, WyckoffResult> results = new HashMap<>();
        long now = System.currentTimeMillis();
        
        String[] possibleTimeframes = {"1d", "1w", "1m"};
        for (String tf : possibleTimeframes) {
            String cacheKey = symbol + "_" + tf;
            CachedResult cached = resultsCache.get(cacheKey);
            
            long cacheDuration = (tf.equalsIgnoreCase("1w") || tf.equalsIgnoreCase("1m")) 
                ? TimeUnit.HOURS.toMillis(1) 
                : TimeUnit.MINUTES.toMillis(15);

            if (cached != null && (now - cached.timestamp) < cacheDuration) {
                log.info("🔥 Wyckoff Cache HIT for {} {}", symbol, tf);
                results.put(tf, cached.result);
            } else if (timeframeData.containsKey(tf)) {
                log.info("📡 Wyckoff Cache MISS / FRESH for {} {}", symbol, tf);
                WyckoffResult fresh = analyze(symbol, timeframeData.get(tf));
                resultsCache.put(cacheKey, new CachedResult(fresh));
                results.put(tf, fresh);
            }
        }
        
        return results;
    }

    public WyckoffResult analyze(String symbol, List<PriceUpdate> data) {
        if (data == null || data.size() < 20) {
            return new WyckoffResult("ANALYZING", "Insufficient data for market structure analysis.", 0.0, 0.0, 0.0, new java.util.ArrayList<>());
        }

        try {
            BarSeries series = Ta4jConverter.priceUpdateToSeries(symbol, data);
            int lastIdx = series.getEndIndex();

            ClosePriceIndicator close = new ClosePriceIndicator(series);
            VolumeIndicator volume = new VolumeIndicator(series);
            VWAPIndicator vwap = new VWAPIndicator(series, Math.min(series.getBarCount(), 20));
            SMAIndicator sma20 = new SMAIndicator(close, 20);
            SMAIndicator sma50 = new SMAIndicator(close, Math.min(series.getBarCount(), 50));

            double currentPrice = close.getValue(lastIdx).doubleValue();
            double vwapVal = vwap.getValue(lastIdx).doubleValue();
            double sma20Val = sma20.getValue(lastIdx).doubleValue();
            double sma50Val = sma50.getValue(lastIdx).doubleValue();

            // Law of Effort vs Result
            double effortVsResult = calculateEffortVsResult(series, lastIdx);
            
            // Indicators
            double volTrend = calculateVolumeTrend(series, 10);
            double volatility = calculateVolatility(series, 20);
            double moneyFlow = calculateMoneyFlow(series, 20);

            // Phase Detection logic
            String phase;
            String details;
            double score;

            boolean isAboveVWAP = currentPrice > vwapVal;
            boolean isAboveSMA20 = currentPrice > sma20Val;
            boolean isAboveSMA50 = currentPrice > sma50Val;

            if (isAboveSMA50 && isAboveSMA20 && isAboveVWAP) {
                if (volTrend > 0.1) {
                    phase = "MARKUP";
                    details = "Strong bullish momentum with volume expansion.";
                    score = 1.0;
                } else {
                    phase = "ACCUM_COMPLETE";
                    details = "Exiting accumulation phase. Price above key benchmarks.";
                    score = 0.7;
                }
            } else if (!isAboveSMA50 && !isAboveSMA20 && !isAboveVWAP) {
                if (volTrend > 0.1) {
                    phase = "MARKDOWN";
                    details = "Strong bearish pressure with volume backing.";
                    score = -1.0;
                } else {
                    phase = "DISTRIBUTION_STARTED";
                    details = "Potential transition to markdown. Supply increasing.";
                    score = -0.7;
                }
            } else if (currentPrice < sma50Val && effortVsResult < -0.5) {
                phase = "ACCUMULATION";
                details = "Absorbing supply near lows (Effort vs Result divergence).";
                score = 0.3;
            } else if (currentPrice > sma50Val && effortVsResult > 0.5) {
                phase = "DISTRIBUTION";
                details = "Heavy supply entering at highs. High volume, low progress.";
                score = -0.3;
            } else {
                phase = "CONSOLIDATION";
                details = "Market is in range. No clear Wyckoff phase identified.";
                score = 0.0;
            }

            WyckoffResult result = new WyckoffResult(phase, details, score, volatility, moneyFlow, new java.util.ArrayList<>());
            
            // Detect specific events
            detectEvents(series, result, symbol);

            return result;

        } catch (Exception e) {
            log.error("❌ Wyckoff analysis failed: {}", e.getMessage());
            return new WyckoffResult("ERROR", "Analysis failed: " + e.getMessage(), 0.0, 0.0, 0.0, new java.util.ArrayList<>());
        }
    }

    private void detectEvents(BarSeries series, WyckoffResult result, String symbol) {
        int lastIdx = series.getEndIndex();
        if (lastIdx < 20) return;

        double currentPrice = series.getBar(lastIdx).getClosePrice().doubleValue();
        double prevPrice = series.getBar(lastIdx - 1).getClosePrice().doubleValue();
        double volume = series.getBar(lastIdx).getVolume().doubleValue();
        
        // Find local range
        double localLow = Double.MAX_VALUE;
        double localHigh = Double.MIN_VALUE;
        double avgVol = 0;
        
        for (int i = 1; i <= 10; i++) {
            double low = series.getBar(lastIdx - i).getLowPrice().doubleValue();
            double high = series.getBar(lastIdx - i).getHighPrice().doubleValue();
            localLow = Math.min(localLow, low);
            localHigh = Math.max(localHigh, high);
            avgVol += series.getBar(lastIdx - i).getVolume().doubleValue();
        }
        avgVol /= 10;

        // SPRING detection: Price breaks BELOW local low but closes ABOVE it
        if (series.getBar(lastIdx).getLowPrice().doubleValue() < localLow && currentPrice > localLow) {
            result.getEvents().add("SPRING detected: Shakeout successful.");
            log.info("📢 {} WYCKOFF EVENT: SPRING (Potential Accumulation)", symbol);
        }

        // UPTHRUST detection: Price breaks ABOVE local high but closes BELOW it
        if (series.getBar(lastIdx).getHighPrice().doubleValue() > localHigh && currentPrice < localHigh) {
            result.getEvents().add("UPTHRUST detected: False breakout.");
            log.info("📢 {} WYCKOFF EVENT: UPTHRUST (Potential Distribution)", symbol);
        }

        // SOS (Sign of Strength): Strong up move on high volume
        if (currentPrice > prevPrice * 1.02 && volume > avgVol * 1.5) {
            result.getEvents().add("SOS (Sign of Strength): Demand dominant.");
            log.info("📢 {} WYCKOFF EVENT: SOS", symbol);
        }

        // LPS (Last Point of Support): Pullback to EMA20/SMA50 that holds
        double sma20 = new SMAIndicator(new ClosePriceIndicator(series), 20).getValue(lastIdx).doubleValue();
        if (prevPrice < currentPrice && prevPrice <= sma20 * 1.01 && prevPrice >= sma20 * 0.99) {
            result.getEvents().add("LPS (Last Point of Support): Support validated.");
            log.info("📢 {} WYCKOFF EVENT: LPS/BU", symbol);
        }
    }

    private double calculateVolatility(BarSeries series, int period) {
        if (series.getBarCount() < period) return 0.0;
        
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(close, period);
        
        double sumSqDiff = 0;
        double mean = sma.getValue(series.getEndIndex()).doubleValue();
        
        for (int i = 0; i < period; i++) {
            double price = series.getBar(series.getEndIndex() - i).getClosePrice().doubleValue();
            sumSqDiff += Math.pow(price - mean, 2);
        }
        
        double stdDev = Math.sqrt(sumSqDiff / period);
        return (stdDev / mean) * 100; // Percentage volatility
    }

    private double calculateMoneyFlow(BarSeries series, int period) {
        if (series.getBarCount() < period) return 0.0;
        
        double mfvSum = 0;
        double volSum = 0;
        
        for (int i = 0; i < period; i++) {
            int idx = series.getEndIndex() - i;
            double high = series.getBar(idx).getHighPrice().doubleValue();
            double low = series.getBar(idx).getLowPrice().doubleValue();
            double close = series.getBar(idx).getClosePrice().doubleValue();
            double volume = series.getBar(idx).getVolume().doubleValue();
            
            double range = high - low;
            if (range > 0) {
                double mfm = ((close - low) - (high - close)) / range;
                mfvSum += mfm * volume;
                volSum += volume;
            }
        }
        
        return volSum > 0 ? mfvSum / volSum : 0.0;
    }

    private double calculateEffortVsResult(BarSeries series, int index) {
        if (index < 5) return 0;
        
        double avgVol = 0;
        for (int i = 0; i < 5; i++) {
            avgVol += series.getBar(index - i).getVolume().doubleValue();
        }
        avgVol /= 5;
        
        double currentVol = series.getBar(index).getVolume().doubleValue();
        double effort = (currentVol / avgVol) - 1.0;
        
        double currentClose = series.getBar(index).getClosePrice().doubleValue();
        double prevClose = series.getBar(index - 1).getClosePrice().doubleValue();
        double result = (currentClose - prevClose) / prevClose;
        
        if (Math.abs(effort) > 0.2 && Math.abs(result) < 0.005) {
            return (result >= 0) ? 1.0 : -1.0;
        }
        
        return 0;
    }

    private double calculateVolumeTrend(BarSeries series, int period) {
        int count = series.getBarCount();
        if (count < period * 2) return 0;
        
        double recentVol = 0;
        for (int i = 0; i < period; i++) {
            recentVol += series.getBar(count - 1 - i).getVolume().doubleValue();
        }
        
        double olderVol = 0;
        for (int i = 0; i < period; i++) {
            olderVol += series.getBar(count - 1 - period - i).getVolume().doubleValue();
        }
        
        return (recentVol - olderVol) / olderVol;
    }
}
