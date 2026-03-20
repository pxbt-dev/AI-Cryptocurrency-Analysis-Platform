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
     * Unified feature extraction for both Training and Prediction
     */
    public static double[] extractFeatures(List<CryptoPrice> windowData) {
        double[] features = new double[FEATURE_SIZE];

        // 1. Convert window to ta4j BarSeries
        BarSeries series = Ta4jConverter.toSeries("TEMP", windowData);
        int lastIdx = series.getEndIndex();
        int barCount = series.getBarCount();
        
        // 2. Setup standard indicators (professional grade)
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        
        SMAIndicator sma5 = new SMAIndicator(close, 5);
        SMAIndicator sma20 = new SMAIndicator(close, 20);
        EMAIndicator ema12 = new EMAIndicator(close, 12);
        RSIIndicator rsi14 = new RSIIndicator(close, 14);
        
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(close, 20);
        
        ROCIndicator roc10 = new ROCIndicator(close, 10);
        
        // Bollinger
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev20);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev20);
        
        // Market Cycle Indicators (Longer term)
        SMAIndicator sma100 = new SMAIndicator(close, barCount < 100 ? barCount : 100);
        EMAIndicator ema200 = new EMAIndicator(close, barCount < 200 ? barCount : 200);
        
        // 3. Extract and Normalize
        double current = close.getValue(lastIdx).doubleValue();

        // Standard Trend Features
        features[0] = (current - sma5.getValue(lastIdx).doubleValue()) / current;
        features[1] = (current - sma20.getValue(lastIdx).doubleValue()) / current;
        features[2] = (current - ema12.getValue(lastIdx).doubleValue()) / current;
        
        // Momentum Features
        features[3] = (rsi14.getValue(lastIdx).doubleValue() - 50.0) / 50.0;
        features[4] = macd.getValue(lastIdx).doubleValue() / current;
        features[5] = stdDev20.getValue(lastIdx).doubleValue() / current;
        features[6] = roc10.getValue(lastIdx).doubleValue() / 100.0;
        
        // Volume Features
        SMAIndicator avgVol = new SMAIndicator(volume, barCount < 20 ? barCount : 20);
        double avgVolVal = avgVol.getValue(lastIdx).doubleValue();
        features[7] = (Math.abs(avgVolVal) < 0.000001) ? 0 : 
                       (volume.getValue(lastIdx).doubleValue() / avgVolVal) - 1.0;
        
        // Volatility & Relative Strength
        double stdDevVal = stdDev20.getValue(lastIdx).doubleValue();
        features[8] = (Math.abs(stdDevVal) < 0.000001) ? 0 :
                       (current - sma20.getValue(lastIdx).doubleValue()) / stdDevVal / 3.0;
        
        // Trend Strength
        SMAIndicator sma50 = new SMAIndicator(close, barCount < 50 ? barCount : 50);
        double sma50Val = sma50.getValue(lastIdx).doubleValue();
        features[9] = (sma20.getValue(lastIdx).doubleValue() - sma50Val) / (Math.abs(sma50Val) < 0.000001 ? 1 : sma50Val);
        
        // Support/Resistance proxy
        double totalAvg = new SMAIndicator(close, barCount).getValue(lastIdx).doubleValue();
        features[10] = (current - totalAvg) / (Math.abs(totalAvg) < 0.000001 ? 1 : totalAvg);
        
        // Bollinger %B
        double upper = bbUpper.getValue(lastIdx).doubleValue();
        double lower = bbLower.getValue(lastIdx).doubleValue();
        features[11] = (upper - lower) == 0 ? 0 : (current - lower) / (upper - lower) - 0.5;
        
        // Acceleration
        ROCIndicator roc5 = new ROCIndicator(close, 5);
        double roc5Now = roc5.getValue(lastIdx).doubleValue();
        double roc5Prev = roc5.getValue(Math.max(0, lastIdx - 1)).doubleValue();
        features[12] = roc5Now - roc5Prev;
        
        // Volume-Price relationship
        features[13] = (roc10.getValue(lastIdx).doubleValue() * (Math.abs(avgVolVal) < 0.00001 ? 1 : volume.getValue(lastIdx).doubleValue() / avgVolVal));

        // --- MARKET CYCLE FEATURES ---
        double ema200Val = ema200.getValue(lastIdx).doubleValue();
        // 14. Distance to 200 EMA (Long-term trend/Market Cycle)
        features[14] = (current - ema200Val) / (Math.abs(ema200Val) < 0.000001 ? 1 : current);
        
        // 15. Golden/Death Cross Proxy (SMA50 vs SMA200)
        features[15] = (sma50Val - ema200Val) / (Math.abs(ema200Val) < 0.000001 ? 1 : ema200Val);
        
        // 16. Medium-term Trend (SMA100)
        double sma100Val = sma100.getValue(lastIdx).doubleValue();
        features[16] = (current - sma100Val) / (Math.abs(sma100Val) < 0.000001 ? 1 : current);
        
        // 17. Volatility Cycle (Standard Deviation ratio)
        StandardDeviationIndicator stdDev50 = new StandardDeviationIndicator(close, barCount < 50 ? barCount : 50);
        features[17] = stdDev50.getValue(lastIdx).doubleValue() / current;
        
        // 18. ROC Cycle (10 vs 50)
        ROCIndicator roc50 = new ROCIndicator(close, barCount < 50 ? barCount : 50);
        features[18] = roc50.getValue(lastIdx).doubleValue() / 100.0;
        
        // 19. Combined Momentum Cycle
        features[19] = (features[3] + (macd.getValue(lastIdx).doubleValue() / current)) / 2.0;

        return features;
    }
}
