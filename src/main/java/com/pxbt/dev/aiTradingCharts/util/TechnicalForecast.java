package com.pxbt.dev.aiTradingCharts.util;

/**
 * Hand-written trend + momentum forecast used when no AI model has a proven edge.
 * Shared by the live prediction path and the walk-forward backtest so both score the same rule.
 */
public final class TechnicalForecast {

    private TechnicalForecast() {
    }

    public static double trend(FeatureExtractor.Indicators inds, int idx) {
        double sma20 = inds.sma20.getValue(idx).doubleValue();
        return (inds.sma5.getValue(idx).doubleValue() - sma20) / sma20;
    }

    /** Price change over the last 10 (daily) or 5 (weekly/monthly) candles, relative to the current price. */
    public static double momentum(FeatureExtractor.Indicators inds, int idx, String timeframe) {
        int momentumPeriod = timeframe.equalsIgnoreCase("1d") ? 10 : 5;
        int prevIdx = Math.max(0, idx - momentumPeriod);
        double current = inds.close.getValue(idx).doubleValue();
        return (current - inds.close.getValue(prevIdx).doubleValue()) / current;
    }

    public static double volatility(FeatureExtractor.Indicators inds, int idx) {
        return inds.stdDev20.getValue(idx).doubleValue() / inds.close.getValue(idx).doubleValue();
    }

    /** Predicted fractional change for the next candle. */
    public static double predict(FeatureExtractor.Indicators inds, int idx, String timeframe, boolean volatileAsset) {
        double tech = (trend(inds, idx) * 0.6) + (momentum(inds, idx, timeframe) * 0.4);

        // Timeframe scaling based on historical volatility
        double volatilityScale = timeframe.equalsIgnoreCase("1m") ? 3.0
                               : timeframe.equalsIgnoreCase("1w") ? 1.8 : 1.0;
        double predicted = tech * (1.0 + (volatility(inds, idx) * volatilityScale));

        // Cap based on asset type
        double maxMove = volatileAsset ? 0.25 : 0.15;
        return Math.max(-maxMove, Math.min(maxMove, predicted));
    }
}
