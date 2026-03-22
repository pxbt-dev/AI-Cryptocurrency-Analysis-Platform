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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WyckoffAnalysisService {

    public Map<String, WyckoffResult> analyzeMultiTimeframe(String symbol, Map<String, List<PriceUpdate>> timeframeData) {
        Map<String, WyckoffResult> results = new HashMap<>();
        
        timeframeData.forEach((tf, data) -> {
            results.put(tf, analyze(symbol, data));
        });
        
        return results;
    }

    public WyckoffResult analyze(String symbol, List<PriceUpdate> data) {
        if (data == null || data.size() < 20) {
            return new WyckoffResult("ANALYZING", "Insufficient data for market structure analysis.", 0.0);
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
            
            // Volume Trend
            double volTrend = calculateVolumeTrend(series, 10);

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
                    phase = "ACCUMULATION_COMPLETED";
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

            return new WyckoffResult(phase, details, score);

        } catch (Exception e) {
            log.error("❌ Wyckoff analysis failed: {}", e.getMessage());
            return new WyckoffResult("ERROR", "Analysis failed: " + e.getMessage(), 0.0);
        }
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
