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

        // Default constructor — uses daily periods
        public Indicators(BarSeries series) {
            this(series, "1d");
        }

        // Timeframe-aware constructor — scales indicator periods to match candle resolution
        public Indicators(BarSeries series, String timeframe) {
            this.barCount = series.getBarCount();
            this.close = new ClosePriceIndicator(series);
            this.volume = new VolumeIndicator(series);

            // Period config per timeframe
            // Field names (sma5, sma20 etc.) represent semantic slots, not literal periods
            int p1, p2, p3, p4, emaShort, emaLong, macdFast, macdSlow,
                stdShort, stdLong, rocShort, rocMid, rocLong, volPeriod;

            if ("1w".equalsIgnoreCase(timeframe)) {
                // Weekly: ~4w=1mo, 10w=2.5mo, 20w=5mo, 40w=10mo
                p1=4;  p2=10; p3=20; p4=40;
                emaShort=12; emaLong=40;
                macdFast=8;  macdSlow=17;
                stdShort=10; stdLong=20;
                rocShort=4;  rocMid=8;   rocLong=20;
                volPeriod=10;
            } else if ("1m".equalsIgnoreCase(timeframe)) {
                // Monthly: 3mo=quarter, 6mo=half-year, 12mo=1yr, 24mo=2yr
                p1=3;  p2=6;  p3=12; p4=24;
                emaShort=6;  emaLong=18;
                macdFast=6;  macdSlow=13;
                stdShort=6;  stdLong=12;
                rocShort=3;  rocMid=6;   rocLong=12;
                volPeriod=6;
            } else {
                // Daily defaults (original values)
                p1=5;   p2=20;  p3=50;  p4=100;
                emaShort=12; emaLong=200;
                macdFast=12; macdSlow=26;
                stdShort=20; stdLong=50;
                rocShort=5;  rocMid=10;  rocLong=50;
                volPeriod=20;
            }

            // Cap all periods to available bar count to avoid ta4j exceptions
            int bc = barCount;
            this.sma5   = new SMAIndicator(close, Math.min(p1, bc));
            this.sma20  = new SMAIndicator(close, Math.min(p2, bc));
            this.sma50  = new SMAIndicator(close, Math.min(p3, bc));
            this.sma100 = new SMAIndicator(close, Math.min(p4, bc));
            this.ema12  = new EMAIndicator(close, Math.min(emaShort, bc));
            this.ema200 = new EMAIndicator(close, Math.min(emaLong, bc));
            this.rsi14  = new RSIIndicator(close, Math.min(14, bc));
            this.macd   = new MACDIndicator(close, Math.min(macdFast, bc), Math.min(macdSlow, bc));
            this.stdDev20 = new StandardDeviationIndicator(close, Math.min(stdShort, bc));
            this.stdDev50 = new StandardDeviationIndicator(close, Math.min(stdLong, bc));
            this.roc5   = new ROCIndicator(close, Math.min(rocShort, bc));
            this.roc10  = new ROCIndicator(close, Math.min(rocMid, bc));
            this.roc50  = new ROCIndicator(close, Math.min(rocLong, bc));
            this.avgVol = new SMAIndicator(volume, Math.min(volPeriod, bc));
            this.totalAvg = new SMAIndicator(close, bc);

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
