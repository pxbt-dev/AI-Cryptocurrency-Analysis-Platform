package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.BacktestStats;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final BinanceHistoricalService historicalDataService;
    private final AccuracyPersistenceService accuracyPersistenceService;
    private final TradingMetricsService metricsService;
    private final AIModelService aiModelService;

    // Short symbols match the file names (BTC_1d.json, etc.)
    // USDT suffix is used for Binance API calls only
    private static final String[] SYMBOLS = {"BTC", "SOL", "TAO", "WIF"};
    private static final String[] TIMEFRAMES = {"1d", "1w", "1m"};

    /**
     * Run on startup to pre-populate Evidence Dashboard from existing disk data.
     * Runs in a background thread so it doesn't block startup.
     */
    @PostConstruct
    public void runStartupBacktest() {
        // Confidence works straight away from the last persisted walk-forward run
        seedStatsFromPersistedRecords();

        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait for app context to be fully ready
                log.info("🚀 Running multi-timeframe startup walk-forward backtest (1d, 1w, 1m)...");
                for (String symbol : SYMBOLS) {
                    for (String timeframe : TIMEFRAMES) {
                        runBacktest(symbol, timeframe);
                        Thread.sleep(1000);
                    }
                }
                log.info("✅ Startup backtest complete for all symbols.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setName("BacktestStartup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Run every 6 hours to refresh evidence with newest data.
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000, initialDelay = 6 * 60 * 60 * 1000)
    public void runScheduledBacktest() {
        log.info("🕒 Running scheduled background backtest (1d, 1w, 1m) for {} symbols", SYMBOLS.length);
        for (String s : SYMBOLS) {
            for (String timeframe : TIMEFRAMES) {
                runBacktest(s, timeframe);
            }
        }
    }

    /**
     * Walk-forward backtest: every historical prediction comes from a model trained only on data
     * that existed before that prediction (see WalkForwardBacktester). It scores the Weka model on
     * its own, without the live-only adjustments (Fear & Greed, funding rate), whose historical
     * values aren't available.
     */
    public int runBacktest(String symbol, String timeframe) {
        log.info("🧪 Starting walk-forward backtest for {} on {} timeframe", symbol, timeframe);
        long started = System.currentTimeMillis();

        try {
            boolean isMonthly = timeframe.equalsIgnoreCase("1m");
            boolean isWeekly  = timeframe.equalsIgnoreCase("1w");
            int pointsNeeded = timeframe.equalsIgnoreCase("1d") ? 3000 : (isWeekly ? 1000 : 500);
            List<CryptoPrice> allData = historicalDataService.getHistoricalData(symbol, timeframe, pointsNeeded);

            WalkForwardBacktester backtester = new WalkForwardBacktester(
                    aiModelService::fitStandalone, WalkForwardBacktester.Config.forTimeframe(timeframe));
            List<AccuracyRecord> batch = backtester.run(symbol, timeframe, allData);

            if (batch.isEmpty()) {
                log.warn("⚠️ Not enough history for a walk-forward backtest of {} {}: {} points",
                        symbol, timeframe, allData.size());
                // Don't keep showing records from the old in-sample backtest
                accuracyPersistenceService.clearBacktestRecords(symbol, timeframe);
                return 0;
            }

            // Replace old records for this symbol/timeframe only once the new run has succeeded
            accuracyPersistenceService.clearBacktestRecords(symbol, timeframe);
            accuracyPersistenceService.recordBatch(batch);

            BacktestStats stats = BacktestStats.from(batch);
            metricsService.recordBacktestRun(symbol, timeframe, batch.size(), stats.winRate() * 100);

            // Feed out-of-sample evidence back into model confidence and the AI-vs-technical choice
            aiModelService.updateBacktestStats(symbol, timeframe, stats);

            log.info("✅ Walk-forward backtest for {} {}: {} predictions, win {}%, skill vs no-change {}% ({}s)",
                    symbol, timeframe, batch.size(), String.format("%.1f", stats.winRate() * 100),
                    String.format("%+.1f", stats.skill() * 100),
                    (System.currentTimeMillis() - started) / 1000);
            return batch.size();

        } catch (Exception e) {
            log.error("❌ Backtest failed for {}: {}", symbol, e.getMessage(), e);
            return 0;
        }
    }

    private void seedStatsFromPersistedRecords() {
        for (String symbol : SYMBOLS) {
            for (String timeframe : TIMEFRAMES) {
                // Only trust records from the walk-forward backtest; older ones were scored in-sample
                List<AccuracyRecord> records = accuracyPersistenceService.getRecords(symbol, timeframe).stream()
                        .filter(r -> r.getModelName() != null
                                && r.getModelName().startsWith(WalkForwardBacktester.MODEL_PREFIX))
                        .toList();
                if (!records.isEmpty()) {
                    aiModelService.updateBacktestStats(symbol, timeframe, BacktestStats.from(records));
                }
            }
        }
    }
}
