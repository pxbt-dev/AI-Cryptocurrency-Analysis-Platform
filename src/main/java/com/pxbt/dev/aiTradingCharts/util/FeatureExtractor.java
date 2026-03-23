package com.pxbt.dev.aiTradingCharts.util;

import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import java.util.List;

public class FeatureExtractor {

    public static final int FEATURE_SIZE = 20;

    /**
     * Container for pre-initialized indicators to avoid object explosion during training
     */
    public static class Indicators {
        public final int barCount;
        public final ClosePriceIndicator close;
        public final VolumeIndicator volume;
        public final SMAIndicator sma5, sma20, sma50, sma100, avgVol, totalAvg;
        public final EMAIndicator ema12, ema200;
        public final RSIIndicator rsi14;
        public final MACDIndicator macd;
        public final StandardDeviationIndicator stdDev20, stdDev50;
        public final ROCIndicator roc5, roc10, roc50;
        public final BollingerBandsUpperIndicator bbUpper;
        public final BollingerBandsLowerIndicator bbLower;

        public Indicators(BarSeries series) {
            this.barCount = series.getBarCount();
            this.close = new ClosePriceIndicator(series);
            this.volume = new VolumeIndicator(series);
            this.sma5 = new SMAIndicator(close, 5);
            this.sma20 = new SMAIndicator(close, 20);
            this.sma50 = new SMAIndicator(close, barCount < 50 ? barCount : 50);
            this.sma100 = new SMAIndicator(close, barCount < 100 ? barCount : 100);
            this.ema12 = new EMAIndicator(close, 12);
            this.ema200 = new EMAIndicator(close, barCount < 200 ? barCount : 200);
            this.rsi14 = new RSIIndicator(close, 14);
            this.macd = new MACDIndicator(close, 12, 26);
            this.stdDev20 = new StandardDeviationIndicator(close, 20);
            this.stdDev50 = new StandardDeviationIndicator(close, barCount < 50 ? barCount : 50);
            this.roc5 = new ROCIndicator(close, 5);
            this.roc10 = new ROCIndicator(close, 10);
            this.roc50 = new ROCIndicator(close, barCount < 50 ? barCount : 50);
            this.avgVol = new SMAIndicator(volume, barCount < 20 ? barCount : 20);
            this.totalAvg = new SMAIndicator(close, barCount);
            
            BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
            this.bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev20);
            this.bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev20);
        }
    }

    /**
     * Legacy entry point - creates a new series and indicators (Higher memory)
     */
    public static double[] extractFeatures(List<CryptoPrice> windowData) {
        // Convert window to ta4j BarSeries
        BarSeries series = Ta4jConverter.toSeries("TEMP", windowData);
        return extractFeatures(series.getEndIndex(), new Indicators(series));
    }

    /**
     * High-performance extraction using pre-initialized indicators
     */
    public static double[] extractFeatures(int index, Indicators inds) {
        double[] features = new double[FEATURE_SIZE];
        // 3. Extract and Normalize
        double current = inds.close.getValue(index).doubleValue();

        // Standard Trend Features
        features[0] = (current - inds.sma5.getValue(index).doubleValue()) / current;
        features[1] = (current - inds.sma20.getValue(index).doubleValue()) / current;
        features[2] = (current - inds.ema12.getValue(index).doubleValue()) / current;
        
        // Momentum Features
        features[3] = (inds.rsi14.getValue(index).doubleValue() - 50.0) / 50.0;
        features[4] = inds.macd.getValue(index).doubleValue() / current;
        features[5] = inds.stdDev20.getValue(index).doubleValue() / current;
        features[6] = inds.roc10.getValue(index).doubleValue() / 100.0;
        
        // Volume Features
        double avgVolVal = inds.avgVol.getValue(index).doubleValue();
        features[7] = (Math.abs(avgVolVal) < 0.000001) ? 0 : 
                       (inds.volume.getValue(index).doubleValue() / avgVolVal) - 1.0;
        
        // Volatility & Relative Strength
        double stdDevVal = inds.stdDev20.getValue(index).doubleValue();
        features[8] = (Math.abs(stdDevVal) < 0.000001) ? 0 :
                       (current - inds.sma20.getValue(index).doubleValue()) / stdDevVal / 3.0;
        
        // Trend Strength
        double sma50Val = inds.sma50.getValue(index).doubleValue();
        features[9] = (inds.sma20.getValue(index).doubleValue() - sma50Val) / (Math.abs(sma50Val) < 0.000001 ? 1 : sma50Val);
        
        // Support/Resistance proxy
        double totalAvgVal = inds.totalAvg.getValue(index).doubleValue();
        features[10] = (current - totalAvgVal) / (Math.abs(totalAvgVal) < 0.000001 ? 1 : totalAvgVal);
        
        // Bollinger %B
        double upper = inds.bbUpper.getValue(index).doubleValue();
        double lower = inds.bbLower.getValue(index).doubleValue();
        features[11] = (upper - lower) == 0 ? 0 : (current - lower) / (upper - lower) - 0.5;
        
        // Acceleration
        double roc5Now = inds.roc5.getValue(index).doubleValue();
        double roc5Prev = inds.roc5.getValue(Math.max(0, index - 1)).doubleValue();
        features[12] = roc5Now - roc5Prev;
        
        // Volume-Price relationship
        features[13] = (inds.roc10.getValue(index).doubleValue() * (Math.abs(avgVolVal) < 0.00001 ? 1 : inds.volume.getValue(index).doubleValue() / avgVolVal));

        // --- MARKET CYCLE FEATURES ---
        double ema200Val = inds.ema200.getValue(index).doubleValue();
        // 14. Distance to 200 EMA (Long-term trend/Market Cycle)
        features[14] = (current - ema200Val) / (Math.abs(ema200Val) < 0.000001 ? 1 : current);
        
        // 15. Golden/Death Cross Proxy (SMA50 vs SMA200)
        features[15] = (sma50Val - ema200Val) / (Math.abs(ema200Val) < 0.000001 ? 1 : ema200Val);
        
        // 16. Medium-term Trend (SMA100)
        double sma100Val = inds.sma100.getValue(index).doubleValue();
        features[16] = (current - sma100Val) / (Math.abs(sma100Val) < 0.000001 ? 1 : current);
        
        // 17. Volatility Cycle (Standard Deviation ratio)
        features[17] = inds.stdDev50.getValue(index).doubleValue() / current;
        
        // 18. ROC Cycle (10 vs 50)
        features[18] = inds.roc50.getValue(index).doubleValue() / 100.0;
        
        // 19. Combined Momentum Cycle
        features[19] = (features[3] + (inds.macd.getValue(index).doubleValue() / current)) / 2.0;

        return features;
    }
}
