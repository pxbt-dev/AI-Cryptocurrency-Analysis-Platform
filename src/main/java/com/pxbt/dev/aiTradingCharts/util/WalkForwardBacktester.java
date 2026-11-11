package com.pxbt.dev.aiTradingCharts.util;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward (out-of-sample) backtest.
 *
 * At every test candle t the prediction comes from a model trained only on samples whose
 * outcome was already known at t (sample j uses features at j and the change j -> j+1, so it
 * is usable once j + 1 <= t). The model is refitted every {@code retrainEvery} candles on a
 * rolling window of the most recent {@code maxTrainSamples} samples, mirroring how the live
 * model is trained on recent history.
 *
 * Indicators are built once over the whole series; ta4j indicators are causal (the value at
 * index i only reads bars 0..i), so this does not leak future prices into features.
 */
public class WalkForwardBacktester {

    public static final String MODEL_PREFIX = "WF:";

    /** A trained model that predicts the next-candle fractional price change. */
    public interface FittedModel {
        String name();
        double predict(double[] features);
    }

    @FunctionalInterface
    public interface ModelFitter {
        /** Returns null when no model could be fitted. */
        FittedModel fit(List<double[]> features, List<Double> targets, String timeframe);
    }

    public record Config(int warmup, int minTrainSamples, int maxTrainSamples,
                         int retrainEvery, double maxTrainChange) {

        public static Config forTimeframe(String timeframe) {
            // warmup and maxTrainChange match TrainingDataService (window size, max change filter)
            return switch (timeframe.toLowerCase()) {
                case "1w" -> new Config(40, 52, 300, 4, 0.5);
                case "1m" -> new Config(24, 15, 60, 1, 0.8);
                default -> new Config(50, 200, 1500, 30, 0.3);
            };
        }
    }

    /** Features for candle idx; may only read bars 0..idx. */
    @FunctionalInterface
    public interface FeatureFn {
        double[] extract(int idx, FeatureExtractor.Indicators inds, List<CryptoPrice> data);
    }

    public static final FeatureFn STANDARD_FEATURES = (idx, inds, data) -> FeatureExtractor.extractFeatures(idx, inds);

    private final ModelFitter fitter;
    private final Config config;
    private final FeatureFn featureFn;

    public WalkForwardBacktester(ModelFitter fitter, Config config) {
        this(fitter, config, STANDARD_FEATURES);
    }

    public WalkForwardBacktester(ModelFitter fitter, Config config, FeatureFn featureFn) {
        this.fitter = fitter;
        this.config = config;
        this.featureFn = featureFn;
    }

    public List<AccuracyRecord> run(String symbol, String timeframe, List<CryptoPrice> data) {
        List<AccuracyRecord> records = new ArrayList<>();
        int n = data.size();
        if (n < config.warmup() + config.minTrainSamples() + 2) {
            return records;
        }

        BarSeries series = Ta4jConverter.toSeries(symbol, data);
        FeatureExtractor.Indicators inds = new FeatureExtractor.Indicators(series, timeframe);

        // features[j] / targets[j]: features at candle j and the realised change j -> j+1
        double[][] features = new double[n][];
        double[] targets = new double[n];
        for (int j = config.warmup(); j < n - 1; j++) {
            features[j] = featureFn.extract(j, inds, data);
            targets[j] = change(data.get(j).getClose(), data.get(j + 1).getClose());
        }

        FittedModel model = null;
        int stepsSinceFit = 0;

        for (int t = config.warmup(); t < n - 1; t++) {
            // Samples j in [warmup, t-1] have known outcomes at time t
            int available = t - config.warmup();
            if (available < config.minTrainSamples()) continue;

            if (model == null || stepsSinceFit >= config.retrainEvery()) {
                FittedModel refit = fitter.fit(trainFeatures(features, targets, t),
                        trainTargets(targets, t), timeframe);
                if (refit != null) {
                    model = refit;
                    stepsSinceFit = 0;
                }
            }
            if (model == null) continue;
            stepsSinceFit++;

            double current = data.get(t).getClose();
            double actual = data.get(t + 1).getClose();
            double predictedChange = model.predict(features[t]);
            double actualChange = change(current, actual);

            records.add(AccuracyRecord.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .predictionTime(data.get(t).getTimestamp())
                    .targetTime(data.get(t + 1).getTimestamp())
                    .currentPrice(current)
                    .predictedPrice(current * (1 + predictedChange))
                    .actualPrice(actual)
                    .predictedChange(predictedChange)
                    .actualChange(actualChange)
                    .isDirectionMatch((predictedChange > 0 && actualChange > 0) || (predictedChange < 0 && actualChange < 0))
                    .modelName(MODEL_PREFIX + model.name())
                    .isEvaluated(true)
                    .build());
        }
        return records;
    }

    private int trainStart(int t) {
        return Math.max(config.warmup(), t - config.maxTrainSamples());
    }

    private List<double[]> trainFeatures(double[][] features, double[] targets, int t) {
        List<double[]> out = new ArrayList<>();
        for (int j = trainStart(t); j < t; j++) {
            if (Math.abs(targets[j]) < config.maxTrainChange()) out.add(features[j]);
        }
        return out;
    }

    private List<Double> trainTargets(double[] targets, int t) {
        List<Double> out = new ArrayList<>();
        for (int j = trainStart(t); j < t; j++) {
            if (Math.abs(targets[j]) < config.maxTrainChange()) out.add(targets[j]);
        }
        return out;
    }

    private static double change(double from, double to) {
        return (to - from) / from;
    }
}
