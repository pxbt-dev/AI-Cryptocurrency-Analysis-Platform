package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.util.FeatureExtractor;
import com.pxbt.dev.aiTradingCharts.util.Ta4jConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@SpringBootTest
class BacktestRunner {

    @Autowired
    private BinanceHistoricalService historicalDataService;

    @Autowired
    private TrainingDataService trainingDataService;

    @Autowired
    private AIModelService aiModelService;

    @Autowired
    private AccuracyPersistenceService accuracyPersistenceService;

    @Test
    void runMultiTimeframeBacktest() {
        String symbol = "BTC";
        String[] timeframes = {"1d", "1w", "1m"};
        
        System.out.println("\n🚀 STARTING MULTI-TIMEFRAME BACKTEST CASE STUDY: " + symbol);
        
        // Ensure models are trained
        System.out.println("🤖 Training all models...");
        trainingDataService.scheduledTraining();
        
        for (String timeframe : timeframes) {
            runBacktestForTimeframe(symbol, timeframe);
        }
    }

    private void runBacktestForTimeframe(String symbol, String timeframe) {
        int pointsNeeded = timeframe.equals("1d") ? 2500 : (timeframe.equals("1w") ? 500 : 200);
        int testWindow = timeframe.equals("1d") ? 30 : 20; // 30 days, 20 weeks, or 20 months
        
        System.out.println("\n--- TIMEFRAME: " + timeframe.toUpperCase() + " ---");
        
        List<CryptoPrice> fullData = historicalDataService.getHistoricalData(symbol, timeframe, pointsNeeded);
        if (fullData.size() < 100 && !timeframe.equals("1m")) {
            System.err.println("❌ Not enough data: " + fullData.size());
            return;
        }

        BarSeries series = Ta4jConverter.toSeries(symbol, fullData);
        FeatureExtractor.Indicators inds = new FeatureExtractor.Indicators(series);
        
        System.out.println("\n| Date | Current Price | Pred. Price | Act. Price | Change % | Match? | Error |");
        System.out.println("|------|---------------|-------------|------------|----------|--------|-------|");
        
        int total = fullData.size();
        int backtestStart = total - testWindow - 1; 
        
        int wins = 0;
        double totalError = 0;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

        for (int i = backtestStart; i < total - 1; i++) {
            double[] features = FeatureExtractor.extractFeatures(i, inds);
            double prediction = aiModelService.predictPriceChange(symbol, features, timeframe);
            
            double currentPrice = fullData.get(i).getPrice();
            double nextPrice = fullData.get(i + 1).getPrice();
            double actualChange = (nextPrice - currentPrice) / currentPrice;
            double predictedPrice = currentPrice * (1 + prediction);
            
            boolean correctDir = (prediction >= 0 && actualChange >= 0) || (prediction < 0 && actualChange < 0);
            if (correctDir) wins++;
            totalError += Math.abs(prediction - actualChange);
            
            String date = dtf.format(Instant.ofEpochMilli(fullData.get(i+1).getTimestamp()));
            
            // Record in audit log
            accuracyPersistenceService.recordPrediction(com.pxbt.dev.aiTradingCharts.model.AccuracyRecord.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .predictionTime(fullData.get(i).getTimestamp())
                    .targetTime(fullData.get(i+1).getTimestamp())
                    .currentPrice(currentPrice)
                    .predictedPrice(predictedPrice)
                    .actualPrice(nextPrice)
                    .predictedChange(prediction)
                    .actualChange(actualChange)
                    .isDirectionMatch(correctDir)
                    .modelName("BACKTEST")
                    .isEvaluated(true)
                    .build());

            System.out.printf("| %s | $%,.0f | $%,.0f | $%,.0f | %+.1f%% | %s | %.1f%% |\n",
                    date, currentPrice, predictedPrice, nextPrice, 
                    actualChange * 100,
                    correctDir ? "✅" : "❌",
                    Math.abs(prediction - actualChange) * 100);
        }
        
        System.out.println("\n--- " + timeframe.toUpperCase() + " STATS ---");
        System.out.println("Win Rate (Directional): " + String.format("%.1f%%", (wins / (double)testWindow) * 100));
        System.out.println("Average Abs Error: " + String.format("%.2f%%", (totalError / (double)testWindow) * 100));
        System.out.println("-------------------\n");
    }
}
