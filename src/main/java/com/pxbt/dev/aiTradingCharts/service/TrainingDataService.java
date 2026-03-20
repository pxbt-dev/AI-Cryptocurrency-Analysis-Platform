package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import com.pxbt.dev.aiTradingCharts.util.Ta4jConverter;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.aroon.AroonUpIndicator;
import org.ta4j.core.num.Num;

@Service
@Slf4j
public class TrainingDataService {

    @Lazy
    @Autowired
    private AIModelService aiModelService;

    @Lazy
    @Autowired
    private SymbolConfig symbolConfig;

    @Autowired
    private BinanceHistoricalService historicalDataService;

    @Autowired
    @Lazy
    private CryptoWebSocketHandler webSocketHandler;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("trainingTaskExecutor")
    private java.util.concurrent.Executor trainingExecutor;

    @Value("${app.training.enabled:true}")
    private boolean trainingEnabled;

    private boolean isTraining = false;
    private volatile boolean shuttingDown = false; // Flag to stop zombie threads
    private String trainingStatus = "Idle";
    private long lastTrainingTime = 0;

    @PostConstruct
    public void init() {
        if (!trainingEnabled) {
            log.info("🚫 ML training disabled by configuration");
            trainingStatus = "Disabled";
            return;
        }
        log.info("⚙️ ML Training Service initialized - triggering initial training...");
        trainingStatus = "Ready (Scheduled: Every 6 hours)";

        // Trigger initial training in background so we don't block startup
        forceRetrain();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("🏠 Shutting down TrainingDataService - stopping background ML tasks...");
        this.shuttingDown = true;
    }

    // Scheduled training
    @Scheduled(cron = "0 0 3,9,15,21 * * *") // Every 6 hours (offset from data update)
    public void scheduledTraining() {
        log.info("🔄 Daily ML retraining starting...");
        collectTrainingData();
    }

    /**
     * Comprehensive training data collection for all symbols and timeframes
     */
    public void collectTrainingData() {
        if (isTraining) {
            log.warn("⚠️ Training already in progress. Skipping request.");
            return;
        }

        isTraining = true;
        trainingStatus = "Training in progress...";
        log.info("📚 Starting comprehensive AI training data collection...");

        List<String> symbols = symbolConfig.getSymbols();
        String[] timeframes = { "1d", "1w", "1m" };

        int totalTrained = 0;

        try {
            for (String symbol : symbols) {
                if (shuttingDown) break; // IMMEDIATELY STOP if app is shutting down

                for (String timeframe : timeframes) {
                    if (shuttingDown) break; // IMMEDIATELY STOP if app is shutting down

                    try {
                        trainingStatus = "Training " + symbol + " (" + timeframe + ")...";
                        log.info("🤖 Starting staggered training for {} {}...", symbol, timeframe);

                        boolean trained = collectSymbolTrainingData(symbol, timeframe);
                        if (trained) {
                            totalTrained++;
                            webSocketHandler.broadcastEvent("ML", "Optimized " + symbol + " " + timeframe);
                        }

                        log.info("🤖 Training finished for {} {}. Resting for 15s...", symbol, timeframe);
                        
                        // Check flag DURING sleep to avoid hanging the app context shutdown
                        for (int i = 0; i < 15 && !shuttingDown; i++) {
                            Thread.sleep(1000);
                        }

                    } catch (Exception e) {
                        if (shuttingDown) break;
                        log.error("❌ Training failed for {} {}: {}", symbol, timeframe, e.getMessage());
                    }
                }
                // GC hint after each full symbol (3 timeframes)
                System.gc();
                log.info("🧹 Post-symbol GC requested for {}.", symbol);
            }
            lastTrainingTime = System.currentTimeMillis();
            trainingStatus = shuttingDown ? "Aborted" : "Completed: " + totalTrained + " models updated";
            log.info("🎯 Training status: {}", trainingStatus);

        } finally {
            isTraining = false;
        }
    }

    public void forceRetrain() {
        trainingExecutor.execute(this::collectTrainingData);
    }

    public boolean isTraining() {
        return isTraining;
    }

    public String getTrainingStatus() {
        return trainingStatus;
    }

    public long getLastTrainingTime() {
        return lastTrainingTime;
    }

    /**
     * Collect training data for specific symbol and timeframe
     * 
     * @return true if training was successful, false if insufficient data
     */
    public boolean collectSymbolTrainingData(String symbol, String timeframe) {
        // Prune file to 5,000 points (Plenty for the ~3000 max required for 1D)
        historicalDataService.pruneFileIfNeeded(symbol, timeframe, 5000);

        // Use the new method that ensures sufficient ML training data
        List<CryptoPrice> fullData = historicalDataService.getMLTrainingData(symbol, timeframe);

        if (fullData == null || fullData.size() < getMinDataPoints(timeframe)) {
            log.debug("❌ Insufficient data for {} {}: only {} points (need {}+)",
                    symbol, timeframe,
                    fullData != null ? fullData.size() : 0,
                    getMinDataPoints(timeframe));
            return false;
        }

        List<double[]> featuresList = new ArrayList<>();
        List<Double> targetChanges = new ArrayList<>();

        log.info("🤖 Processing {} data points for {} - {} ML training",
                fullData.size(), symbol, timeframe);

        // Different window sizes based on timeframe
        int windowSize = getWindowSize(timeframe);
        int futureOffset = getFutureOffset(timeframe);

        // Slide window through historical data
        int trainingSamples = 0;
        for (int i = windowSize; i < fullData.size() - futureOffset; i++) {
            List<CryptoPrice> windowData = fullData.subList(i - windowSize, i);

            if (windowData.isEmpty()) {
                log.error("🛑 EMPTY windowData for {} {} at index {}. windowSize={}, fullData.size={}, futureOffset={}",
                        symbol, timeframe, i, windowSize, fullData.size(), futureOffset);
                continue;
            }

            // Extract features and calculate actual future change
            double[] features = extractFeaturesForTraining(windowData, timeframe);
            double actualChange = calculateActualChange(fullData, i, timeframe);

            // Only include meaningful samples (filter out noise)
            if (Math.abs(actualChange) < getMaxChangeFilter(timeframe)) {
                featuresList.add(features);
                targetChanges.add(actualChange);
                trainingSamples++;
            }
        }

        // Train the model with collected data
        int minSamples = getMinTrainingSamples(timeframe);
        if (trainingSamples >= minSamples) {
            aiModelService.trainModel(symbol, timeframe, featuresList, targetChanges);

            // CRITICAL: Immediately clear references to help GC
            featuresList.clear();
            targetChanges.clear();
            log.info("✅ Trained {} model for {} with {} samples", timeframe, symbol, trainingSamples);
            return true;
        } else {
            log.warn("⚠️ Insufficient quality samples for {} {}: {} (need {}+)",
                    symbol, timeframe, trainingSamples, minSamples);
            return false;
        }
    }

    /**
     * Helper methods for different timeframes
     */
    private int getMinDataPoints(String timeframe) {
        return switch (timeframe) {
            case "1d" -> 150; // ~5 months daily
            case "1W", "1w" -> 52; // 1 year weekly
            case "1m", "1M" -> 24; // 2 years monthly
            default -> 50;
        };
    }

    private int getWindowSize(String timeframe) {
        return switch (timeframe) {
            case "1d" -> 50; // 50 days
            case "1W", "1w" -> 40; // 40 weeks (~9 months)
            case "1m", "1M" -> 30; // 30 months (~2.5 years)
            default -> 50;
        };
    }

    private int getFutureOffset(String timeframe) {
        return switch (timeframe) {
            case "1d" -> 1; // 1 day ahead
            case "1w", "1W" -> 1; // 1 week ahead
            case "1m", "1M" -> 1; // 1 month ahead
            default -> 1;
        };
    }

    private double getMaxChangeFilter(String timeframe) {
        return switch (timeframe) {
            case "1d" -> 0.3; // Filter >30% daily changes
            case "1W", "1w" -> 0.5; // Filter >50% weekly changes
            case "1m", "1M" -> 0.8; // Filter >80% monthly changes
            default -> 0.5;
        };
    }

    private int getMinTrainingSamples(String timeframe) {
        return switch (timeframe) {
            case "1d" -> 30;
            case "1W", "1w" -> 15;
            case "1m", "1M" -> 10;
            default -> 20;
        };
    }

    /**
     * Enhanced feature extraction with 15 technical indicators
     */
    /**
     * Enhanced feature extraction with 15 professional technical indicators via ta4j
     */
    private double[] extractFeaturesForTraining(List<CryptoPrice> windowData, String timeframe) {
        return com.pxbt.dev.aiTradingCharts.util.FeatureExtractor.extractFeatures(windowData);
    }

    /**
     * Calculate actual price change for training targets
     */
    private double calculateActualChange(List<CryptoPrice> data, int currentIndex, String timeframe) {
        int futureIndex = currentIndex + getFutureOffset(timeframe);
        if (futureIndex >= data.size())
            return 0.0;

        double currentPrice = data.get(currentIndex).getPrice();
        double futurePrice = data.get(futureIndex).getPrice();

        return (futurePrice - currentPrice) / currentPrice;
    }

    private double calculateVolumePriceTrend(double[] volumes, double[] prices) {
        if (prices.length < 2)
            return 0;

        double volumeSum = 0;
        double priceChangeSum = 0;

        for (int i = 1; i < prices.length; i++) {
            double priceChange = (prices[i] - prices[i - 1]) / prices[i - 1];
            volumeSum += volumes[i];
            priceChangeSum += priceChange * volumes[i];
        }

        return volumeSum == 0 ? 0 : priceChangeSum / volumeSum;
    }
}