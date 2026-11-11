package com.pxbt.dev.aiTradingCharts.util;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WalkForwardBacktesterTest {

    private static final long DAY = 86_400_000L;

    // Zig-zag price path so every candle has a non-zero, known move
    private static List<CryptoPrice> prices(int n) {
        List<CryptoPrice> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double p = 100.0 + i * 0.1 + (i % 2 == 0 ? 1.0 : -1.0);
            out.add(new CryptoPrice("TEST", p, 1000.0 + i, i * DAY, p, p + 0.5, p - 0.5, p));
        }
        return out;
    }

    /**
     * UT-BACKTEST-001 (P0): a model that "cheats" by returning the next move it was trained on
     * can only ever have seen targets whose outcome is known at the prediction candle.
     * Each fit records the targets it received; the target of sample j is the move j -> j+1,
     * so every target handed to a fit made at candle t must come from j <= t-1.
     */
    @Test
    void neverTrainsOnOutcomesAfterThePredictionCandle() {
        List<CryptoPrice> data = prices(120);
        WalkForwardBacktester.Config config = new WalkForwardBacktester.Config(10, 20, 50, 5, 0.3);

        // Map each realised move back to its sample index j so we can see which samples a fit used
        List<Double> moveByIndex = new ArrayList<>();
        for (int j = 0; j < data.size() - 1; j++) {
            moveByIndex.add((data.get(j + 1).getClose() - data.get(j).getClose()) / data.get(j).getClose());
        }

        List<Integer> maxSampleIndexPerFit = new ArrayList<>();
        WalkForwardBacktester.ModelFitter fitter = (features, targets, tf) -> {
            int maxJ = -1;
            for (double target : targets) {
                int j = moveByIndex.indexOf(target);
                maxJ = Math.max(maxJ, j);
            }
            maxSampleIndexPerFit.add(maxJ);
            return new WalkForwardBacktester.FittedModel() {
                public String name() { return "Spy"; }
                public double predict(double[] f) { return 0.01; }
            };
        };

        List<AccuracyRecord> records = new WalkForwardBacktester(fitter, config).run("TEST", "1d", data);

        // First prediction at t = warmup + minTrainSamples = 30; a fit happens every 5 candles
        assertEquals(30 * DAY, records.get(0).getPredictionTime());
        assertEquals(data.size() - 1 - 30, records.size());
        for (int fit = 0; fit < maxSampleIndexPerFit.size(); fit++) {
            int t = 30 + fit * 5;
            assertEquals(t - 1, maxSampleIndexPerFit.get(fit),
                    "fit #" + fit + " at candle " + t + " used a sample whose outcome is after the candle");
        }
    }

    /** UT-BACKTEST-002 (P0): the rolling window never exceeds maxTrainSamples. */
    @Test
    void trainingWindowIsCappedAtMaxTrainSamples() {
        List<CryptoPrice> data = prices(200);
        WalkForwardBacktester.Config config = new WalkForwardBacktester.Config(10, 20, 50, 5, 0.3);
        List<Integer> sizes = new ArrayList<>();
        WalkForwardBacktester.ModelFitter fitter = (features, targets, tf) -> {
            sizes.add(targets.size());
            return new WalkForwardBacktester.FittedModel() {
                public String name() { return "Spy"; }
                public double predict(double[] f) { return 0.0; }
            };
        };

        new WalkForwardBacktester(fitter, config).run("TEST", "1d", data);

        assertEquals(20, sizes.get(0));
        assertEquals(50, sizes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /** UT-BACKTEST-003 (P1): records carry the walk-forward prefix and correct direction scoring. */
    @Test
    void recordsAreScoredAgainstTheNextCandle() {
        List<CryptoPrice> data = prices(60);
        WalkForwardBacktester.Config config = new WalkForwardBacktester.Config(10, 20, 50, 5, 0.3);
        WalkForwardBacktester.ModelFitter alwaysUp = (features, targets, tf) -> new WalkForwardBacktester.FittedModel() {
            public String name() { return "Up"; }
            public double predict(double[] f) { return 0.02; }
        };

        List<AccuracyRecord> records = new WalkForwardBacktester(alwaysUp, config).run("TEST", "1d", data);

        AccuracyRecord first = records.get(0); // t = 30 (even): price goes 104.0 -> 102.1, i.e. down
        assertEquals("WF:Up", first.getModelName());
        assertEquals(104.0, first.getCurrentPrice(), 1e-9);
        assertEquals(102.1, first.getActualPrice(), 1e-9);
        assertEquals(104.0 * 1.02, first.getPredictedPrice(), 1e-9);
        assertFalse(first.isDirectionMatch());
        assertTrue(records.get(1).isDirectionMatch()); // t = 31 (odd): 102.1 -> 104.2, up

        Set<Long> times = new HashSet<>();
        records.forEach(r -> assertTrue(times.add(r.getPredictionTime()), "duplicate prediction time"));
    }

    /** UT-BACKTEST-004 (P1): too little history gives no records instead of failing. */
    @Test
    void tooLittleHistoryReturnsNoRecords() {
        WalkForwardBacktester.Config config = new WalkForwardBacktester.Config(10, 20, 50, 5, 0.3);
        WalkForwardBacktester.ModelFitter fitter = (f, t, tf) -> fail("must not fit");
        assertEquals(0, new WalkForwardBacktester(fitter, config).run("TEST", "1d", prices(31)).size());
    }
}
