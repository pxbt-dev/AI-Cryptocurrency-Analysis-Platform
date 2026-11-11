package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.BacktestStats;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.util.FeatureExtractor;
import com.pxbt.dev.aiTradingCharts.util.TechnicalForecast;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester.FeatureFn;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester.FittedModel;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester.ModelFitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import weka.classifiers.functions.LinearRegression;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.io.File;
import java.util.*;

/**
 * Manual report: runs walk-forward backtests over the local historical_data files without
 * starting Spring, for the live model and for experimental variants.
 *
 *   -Dwf.report=all | BTC_1d,SOL_1w        which symbol/timeframe files
 *   -Dwf.variants=current,tech,...          which variants (default: all)
 */
@EnabledIfSystemProperty(named = "wf.report", matches = ".+")
class WalkForwardReport {

    private static final List<String> ALL_KEYS = List.of("BTC_1d", "BTC_1w", "BTC_1m", "SOL_1d", "SOL_1w", "SOL_1m",
            "TAO_1d", "TAO_1w", "TAO_1m", "WIF_1d", "WIF_1w", "WIF_1m");

    private final AIModelService ai = new AIModelService();

    private record Variant(String name, ModelFitter fitter, FeatureFn features) {
    }

    private List<Variant> variants(String symbol, String timeframe) {
        boolean volatileAsset = !symbol.equals("BTC"); // app.volatile-symbols=SOL,TAO,WIF
        FeatureFn tech = (i, inds, d) -> new double[]{TechnicalForecast.predict(inds, i, timeframe, volatileAsset)};
        FeatureFn lastReturn = (i, inds, d) -> new double[]{ret(d, i, 1)};
        return List.of(
                // Live Weka pipeline (best of LR / RF / M5P by holdout R2)
                new Variant("current", ai::fitStandalone, WalkForwardBacktester.STANDARD_FEATURES),
                // The hand-written technical formula the site falls back to
                new Variant("tech", rule(f -> f[0]), tech),
                // Predict the average move of the training window
                new Variant("drift", (x, y, tf) -> constant(y.stream().mapToDouble(Double::doubleValue).average().orElse(0)), WalkForwardBacktester.STANDARD_FEATURES),
                // B1: train on log returns
                new Variant("logret", logReturns(ai::fitStandalone), WalkForwardBacktester.STANDARD_FEATURES),
                // Live model with predictions scaled down 4x (same directions, smaller moves)
                new Variant("shrink25", scaled(ai::fitStandalone, 0.25), WalkForwardBacktester.STANDARD_FEATURES),
                // Plain linear regression only, no model selection
                new Variant("lr", WalkForwardReport::fitLinear, WalkForwardBacktester.STANDARD_FEATURES),
                // B3: standard features + return lags, volatility regime, volume surprise
                new Variant("b3", ai::fitStandalone, WalkForwardReport::extendedFeatures),
                // Simple momentum / mean-reversion rules on the last candle's move
                new Variant("momentum", rule(f -> 0.1 * f[0]), lastReturn),
                new Variant("reversion", rule(f -> -0.1 * f[0]), lastReturn));
    }

    @Test
    void report() throws Exception {
        String arg = System.getProperty("wf.report");
        List<String> keys = arg.equals("all") ? ALL_KEYS : List.of(arg.split(","));
        Set<String> wanted = new HashSet<>(Arrays.asList(System.getProperty("wf.variants", "").split(",")));
        wanted.remove("");
        ObjectMapper om = new ObjectMapper();

        // Pooled records per variant and timeframe, to judge consistency across coins
        Map<String, List<AccuracyRecord>> pooled = new TreeMap<>();

        System.out.println("\nkey      variant       n   win%  lower%  maeModel  maeNoChg   skill  edge");
        for (String key : keys) {
            String[] p = key.split("_");
            List<CryptoPrice> data = om.readValue(new File("historical_data/" + key + ".json"), new TypeReference<>() {});
            data.sort(Comparator.comparingLong(CryptoPrice::getTimestamp));
            if (data.size() > 3000) data = data.subList(data.size() - 3000, data.size());

            for (Variant v : variants(p[0], p[1])) {
                if (!wanted.isEmpty() && !wanted.contains(v.name())) continue;
                List<AccuracyRecord> recs = new WalkForwardBacktester(v.fitter(),
                        WalkForwardBacktester.Config.forTimeframe(p[1]), v.features()).run(p[0], p[1], data);
                BacktestStats s = BacktestStats.from(recs);
                if (s.samples() == 0) continue;
                pooled.computeIfAbsent(v.name() + " " + p[1], k -> new ArrayList<>()).addAll(recs);
                System.out.printf("%-8s %-10s %5d %6.1f %7.1f %8.2f%% %8.2f%% %+6.1f%% %5s%n",
                        key, v.name(), s.samples(), s.winRate() * 100, s.winRateLowerBound() * 100,
                        s.maeModel() * 100, s.maeBaseline() * 100, s.skill() * 100, s.hasEdge() ? "YES" : "-");
            }
        }

        System.out.println("\nPOOLED across coins (variant tf)      n   win%  lower%   skill  edge");
        pooled.forEach((k, recs) -> {
            BacktestStats s = BacktestStats.from(recs);
            System.out.printf("%-30s %6d %6.1f %7.1f %+6.1f%% %5s%n", k, s.samples(), s.winRate() * 100,
                    s.winRateLowerBound() * 100, s.skill() * 100, s.hasEdge() ? "YES" : "-");
        });
    }

    // ---- variant helpers ----

    private static double ret(List<CryptoPrice> d, int i, int lag) {
        int from = Math.max(0, i - lag);
        return (d.get(i).getClose() - d.get(from).getClose()) / d.get(from).getClose();
    }

    private static ModelFitter rule(java.util.function.ToDoubleFunction<double[]> f) {
        return (x, y, tf) -> new FittedModel() {
            public String name() { return "Rule"; }
            public double predict(double[] features) { return f.applyAsDouble(features); }
        };
    }

    private static FittedModel constant(double value) {
        return new FittedModel() {
            public String name() { return "Const"; }
            public double predict(double[] features) { return value; }
        };
    }

    private static ModelFitter logReturns(ModelFitter inner) {
        return (x, y, tf) -> {
            List<Double> logY = new ArrayList<>();
            for (double v : y) logY.add(Math.log1p(v));
            FittedModel m = inner.fit(new ArrayList<>(x), logY, tf);
            if (m == null) return null;
            return new FittedModel() {
                public String name() { return "Log" + m.name(); }
                public double predict(double[] f) { return Math.expm1(m.predict(f)); }
            };
        };
    }

    private static ModelFitter scaled(ModelFitter inner, double k) {
        return (x, y, tf) -> {
            FittedModel m = inner.fit(new ArrayList<>(x), new ArrayList<>(y), tf);
            if (m == null) return null;
            return new FittedModel() {
                public String name() { return m.name(); }
                public double predict(double[] f) { return k * m.predict(f); }
            };
        };
    }

    private static FittedModel fitLinear(List<double[]> x, List<Double> y, String tf) {
        try {
            ArrayList<Attribute> attrs = new ArrayList<>();
            for (int i = 0; i < x.get(0).length; i++) attrs.add(new Attribute("f" + i));
            attrs.add(new Attribute("y"));
            Instances ds = new Instances("lr", attrs, x.size());
            ds.setClassIndex(attrs.size() - 1);
            for (int r = 0; r < x.size(); r++) {
                double[] v = Arrays.copyOf(x.get(r), x.get(r).length + 1);
                v[v.length - 1] = y.get(r);
                ds.add(new DenseInstance(1.0, v));
            }
            LinearRegression lr = new LinearRegression();
            lr.buildClassifier(ds);
            Instances header = new Instances(ds, 0);
            return new FittedModel() {
                public String name() { return "LinearRegression"; }
                public double predict(double[] f) {
                    try {
                        double[] v = Arrays.copyOf(f, f.length + 1);
                        v[v.length - 1] = weka.core.Utils.missingValue();
                        DenseInstance inst = new DenseInstance(1.0, v);
                        inst.setDataset(header);
                        return Math.max(-0.2, Math.min(0.2, lr.classifyInstance(inst)));
                    } catch (Exception e) {
                        return 0;
                    }
                }
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Standard features plus return lags, short/long realised-volatility ratio and volume surprise. */
    private static double[] extendedFeatures(int i, FeatureExtractor.Indicators inds, List<CryptoPrice> d) {
        double[] base = FeatureExtractor.extractFeatures(i, inds);
        double[] out = Arrays.copyOf(base, base.length + 5);
        out[base.length] = ret(d, i, 1);
        out[base.length + 1] = ret(d, Math.max(0, i - 1), 1);
        out[base.length + 2] = ret(d, Math.max(0, i - 2), 1);
        double volShort = realisedVol(d, i, 7), volLong = realisedVol(d, i, 30);
        out[base.length + 3] = volLong > 0 ? volShort / volLong - 1.0 : 0;
        double avgVol = 0;
        int n = 0;
        for (int j = Math.max(0, i - 20); j < i; j++) { avgVol += d.get(j).getVolume(); n++; }
        out[base.length + 4] = (n > 0 && avgVol > 0) ? Math.log(d.get(i).getVolume() / (avgVol / n) + 1e-9) : 0;
        return out;
    }

    private static double realisedVol(List<CryptoPrice> d, int i, int window) {
        double sum = 0, sumSq = 0;
        int n = 0;
        for (int j = Math.max(1, i - window + 1); j <= i; j++) {
            double r = ret(d, j, 1);
            sum += r;
            sumSq += r * r;
            n++;
        }
        if (n < 2) return 0;
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sumSq / n - mean * mean));
    }
}
