package com.pxbt.dev.aiTradingCharts.model;

import java.util.List;

/**
 * Out-of-sample backtest statistics for one symbol/timeframe.
 *
 * Skill is measured against a "no change" forecast (predict the price stays where it is),
 * which is the honest baseline for short-horizon price prediction: a model that cannot beat
 * it has no demonstrated edge, whatever its win rate looks like.
 */
public record BacktestStats(
        int samples,
        int wins,
        double winRate,          // fraction of predictions with the right direction
        double winRateLowerBound, // one-sided 95% lower bound on winRate
        double maeModel,         // mean absolute error of predicted vs actual change
        double maeBaseline,      // mean absolute error of the "no change" forecast
        double skill) {          // 1 - maeModel / maeBaseline; > 0 means better than no-change

    private static final double Z_95_ONE_SIDED = 1.645;

    public static BacktestStats from(List<AccuracyRecord> records) {
        int n = 0, wins = 0;
        double absErrModel = 0, absErrBaseline = 0;
        for (AccuracyRecord r : records) {
            if (!r.isEvaluated()) continue;
            n++;
            if (r.isDirectionMatch()) wins++;
            absErrModel += Math.abs(r.getPredictedChange() - r.getActualChange());
            absErrBaseline += Math.abs(r.getActualChange());
        }
        if (n == 0) {
            return new BacktestStats(0, 0, 0, 0, 0, 0, 0);
        }
        double winRate = (double) wins / n;
        double lowerBound = winRate - Z_95_ONE_SIDED * Math.sqrt(0.25 / n);
        double maeModel = absErrModel / n;
        double maeBaseline = absErrBaseline / n;
        double skill = maeBaseline > 0 ? 1.0 - maeModel / maeBaseline : 0.0;
        return new BacktestStats(n, wins, winRate, lowerBound, maeModel, maeBaseline, skill);
    }

    /** True when the model beats a coin flip with 95% confidence AND beats the no-change forecast. */
    public boolean hasEdge() {
        return samples > 0 && winRateLowerBound > 0.5 && skill > 0;
    }
}
