package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.model.PricePrediction;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final HistoricalDataFileService fileService;
    private final BinanceHistoricalService historicalDataService;
    private final PricePredictionService predictionService;
    private final AccuracyPersistenceService accuracyPersistenceService;
    private final TradingMetricsService metricsService;

    // Short symbols match the file names (BTC_1d.json, etc.)
    // USDT suffix is used for Binance API calls only
    private static final String[] SYMBOLS = {"BTC", "SOL", "TAO", "WIF"};

    /**
     * Run on startup to pre-populate Evidence Dashboard from existing disk data.
     * Runs in a background thread so it doesn't block startup.
     */
    @PostConstruct
    public void runStartupBacktest() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait for app context to be fully ready
                log.info("🚀 Running multi-timeframe startup backtest (1d, 1w, 1m)...");
                for (String symbol : SYMBOLS) {
                    runBacktest(symbol, "1d");
                    Thread.sleep(1000);
                    runBacktest(symbol, "1w");
                    Thread.sleep(1000);
                    runBacktest(symbol, "1m");
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
            runBacktest(s, "1d");
            runBacktest(s, "1w");
            runBacktest(s, "1m");
        }
    }

    public int runBacktest(String symbol, String timeframe) {
        log.info("🧪 Starting historical backtest for {} on {} timeframe (Fixed Point)", symbol, timeframe);

        try {
            // Use BinanceHistoricalService to ensure we have deep data (3000 points)
            int pointsNeeded = timeframe.equalsIgnoreCase("1d") ? 3000 : (timeframe.equalsIgnoreCase("1w") ? 1000 : 500);
            List<CryptoPrice> allData = historicalDataService.getHistoricalData(symbol, timeframe, pointsNeeded);

            if (allData.size() < 300) {
                log.warn("⚠️ Insufficient file data for backtest of {}: found only {} points", symbol, allData.size());
                return 0;
            }

            // Clear old backtest records for this symbol/timeframe to avoid duplicates
            accuracyPersistenceService.clearBacktestRecords(symbol, timeframe);

            int scanStart = 200; // Need enough history for indicator calculation
            int scanEnd = allData.size() - 2; 
            int count = 0;
            int step = 1; // Full granularity

            List<AccuracyRecord> batch = new ArrayList<>();

            for (int i = scanStart; i < scanEnd; i += step) {
                List<CryptoPrice> historicalSlice = allData.subList(0, i + 1);
                CryptoPrice currentPoint = allData.get(i);
                CryptoPrice futurePoint = allData.get(i + 1);

                PricePrediction pred = predictionService.generateAIPrediction(
                        symbol, currentPoint.getClose(), historicalSlice, timeframe);

                double predictedMove = (pred.getPredictedPrice() - currentPoint.getClose()) / currentPoint.getClose();
                double actualMove = (futurePoint.getClose() - currentPoint.getClose()) / currentPoint.getClose();

                AccuracyRecord record = AccuracyRecord.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .predictionTime(currentPoint.getTimestamp())
                        .targetTime(futurePoint.getTimestamp())
                        .currentPrice(currentPoint.getClose())
                        .predictedPrice(pred.getPredictedPrice())
                        .actualPrice(futurePoint.getClose())
                        .predictedChange(predictedMove)
                        .actualChange(actualMove)
                        .isDirectionMatch((predictedMove > 0 && actualMove > 0) || (predictedMove < 0 && actualMove < 0))
                        .modelName(pred.getModelName())
                        .isEvaluated(true)
                        .build();

                batch.add(record);
                count++;
            }

            accuracyPersistenceService.recordBatch(batch);

            // Calculate accuracy for metrics
            long matches = batch.stream().filter(AccuracyRecord::isDirectionMatch).count();
            double accuracy = batch.isEmpty() ? 0 : (matches * 100.0 / batch.size());
            metricsService.recordBacktestRun(symbol, timeframe, count, accuracy);

            log.info("✅ Backtest for {}: Generated {} historical accuracy records (accuracy: {}%)", symbol, count, String.format("%.1f", accuracy));
            return count;

        } catch (Exception e) {
            log.error("❌ Backtest failed for {}: {}", symbol, e.getMessage(), e);
            return 0;
        }
    }
}
