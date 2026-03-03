package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.Gateway.BinanceGateway;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableScheduling
public class BinanceHistoricalService {

    @Autowired
    private HistoricalDataFileService fileService;

    @Autowired
    private SmartCacheService smartCacheService;

    private final BinanceGateway binanceGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Small cache for active data
    private final Cache<String, List<CryptoPrice>> dataCache = Caffeine.newBuilder()
            .maximumSize(5)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .weakValues() // Allows GC to collect cached values
            .build();

    @PostConstruct
    public void init() {
        log.info("📚 BinanceHistoricalService initializing - file-based storage");
    }

    /**
     * Fetch deep historical data with multiple Binance API calls and rate limiting.
     */
    private List<CryptoPrice> fetchDeepHistory(String symbol, String timeframe, int totalPoints) {
        try {
            List<CryptoPrice> allData = new ArrayList<>();
            int remainingPoints = totalPoints;
            Long endTime = null;

            int batchNum = 1;
            int maxBatches = (int) Math.ceil(totalPoints / 1000.0);

            while (remainingPoints > 0 && batchNum <= maxBatches) {
                int batchSize = Math.min(remainingPoints, 1000);

                log.info("📡 Fetching batch {}/{}: {} {} for {}",
                        batchNum, maxBatches, batchSize, timeframe, symbol);

                String response = binanceGateway.getRawKlines(symbol,
                        convertTimeframeToBinanceInterval(timeframe),
                        batchSize, endTime)
                        .blockOptional()
                        .orElse("[]");

                List<CryptoPrice> batch = parseBinanceKlinesToCryptoPrice(response, symbol);
                if (batch.isEmpty())
                    break;

                // Add to list (will sort at the end)
                allData.addAll(batch);
                remainingPoints -= batch.size();

                // Set endTime for next batch
                endTime = batch.get(0).getTimestamp() - 1;

                batchNum++;
                if (remainingPoints > 0) {
                    Thread.sleep(1000); // Rate limiting
                }
            }

            return mergeAndSortData(allData);

        } catch (Exception e) {
            log.error("❌ Deep history fetch failed for {} {}: {}", symbol, timeframe, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Single Binance API call
     */
    private List<CryptoPrice> fetchBinanceData(String symbol, String timeframe, int limit) {
        try {
            String binanceInterval = convertTimeframeToBinanceInterval(timeframe);

            String response = binanceGateway.getRawKlines(symbol, binanceInterval, limit)
                    .blockOptional()
                    .orElse("[]");

            if (response == null || response.trim().isEmpty() || response.equals("[]")) {
                return new ArrayList<>();
            }

            return parseBinanceKlinesToCryptoPrice(response, symbol);

        } catch (Exception e) {
            log.error("❌ API call failed for {} {}: {}", symbol, timeframe, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Efficiently merge new and existing data, removing duplicates and sorting by
     * timestamp.
     */
    private List<CryptoPrice> mergeAndSortData(List<CryptoPrice> existing, List<CryptoPrice> newData) {
        if (existing == null || existing.isEmpty())
            return newData != null ? newData : new ArrayList<>();
        if (newData == null || newData.isEmpty())
            return existing;

        Map<Long, CryptoPrice> mergedMap = new TreeMap<>();
        for (CryptoPrice cp : existing)
            mergedMap.put(cp.getTimestamp(), cp);
        for (CryptoPrice cp : newData)
            mergedMap.put(cp.getTimestamp(), cp);

        return new ArrayList<>(mergedMap.values());
    }

    private List<CryptoPrice> mergeAndSortData(List<CryptoPrice> data) {
        return mergeAndSortData(data, null);
    }

    /**
     * Background update for ML retraining (daily)
     */
    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    public void dailyMLUpdate() {
        log.info("🤖 Starting daily ML data update");

        String[] symbols = { "BTC", "SOL", "TAO", "WIF" };
        String[] mlTimeframes = { "1h", "4h", "1d" };

        for (String symbol : symbols) {
            for (String timeframe : mlTimeframes) {
                try {
                    updateMLData(symbol, timeframe);
                    Thread.sleep(2000); // Rate limiting
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.info("✅ Daily ML data update complete");
    }

    private void updateMLData(String symbol, String timeframe) {
        try {
            // Fetch recent data only
            int recentPoints = timeframe.equals("1d") ? 30 : 168; // 30 days or 7 days

            List<CryptoPrice> newData = fetchBinanceData(symbol, timeframe, recentPoints);
            if (newData.isEmpty())
                return;

            // Load existing
            List<CryptoPrice> existing = fileService.loadHistoricalData(symbol, timeframe);

            // Merge (newest data wins)
            List<CryptoPrice> merged = mergeAndSortData(existing, newData);

            // Save
            fileService.saveHistoricalData(symbol, timeframe, merged);

            // Clear cache
            dataCache.invalidate(symbol + "_" + timeframe);

            log.info("📈 Updated {} {}: {} total points", symbol, timeframe, merged.size());

        } catch (Exception e) {
            log.error("❌ Update failed for {} {}: {}", symbol, timeframe, e.getMessage());
        }
    }

    public List<PriceUpdate> getHistoricalDataAsPriceUpdate(String symbol, String interval, int limit) {
        try {
            List<CryptoPrice> cryptoPrices = getHistoricalData(symbol, interval, limit);
            return convertToPriceUpdate(cryptoPrices, symbol);
        } catch (Exception e) {
            log.error("❌ Failed to get historical data for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<CryptoPrice> getHistoricalData(String symbol, String timeframe, int limit) {
        String cacheKey = symbol + "_" + timeframe;

        return dataCache.get(cacheKey, key -> {
            // 1. Try to load from "smart" cache/file first
            List<CryptoPrice> fileData = smartCacheService.getSmartData(symbol, timeframe, limit);

            // 2. Check if we need update
            int maxAgeHours = getMaxAgeForTimeframe(timeframe);
            if (!fileData.isEmpty() && !fileService.needsUpdate(symbol, timeframe, maxAgeHours)) {
                if (fileData.size() >= limit) {
                    return fileData; // smartCacheService already handles the limit
                }
            }

            // 3. Fetch fresh data (only what's missing or a fresh batch)
            List<CryptoPrice> freshData;
            if (timeframe.equals("1d") && limit > 1000) {
                freshData = fetchDeepHistory(symbol, timeframe, limit);
            } else {
                freshData = fetchBinanceData(symbol, timeframe, Math.min(limit, 1000));
            }

            // 4. Append to JSONL file (saveHistoricalData now appends)
            if (!freshData.isEmpty()) {
                fileService.saveHistoricalData(symbol, timeframe, freshData);

                // Return fresh data combined with what we had (simplified)
                // Return combined data (file + fresh)
                return mergeAndSortData(fileData, freshData);
            }

            return fileData;
        });
    }

    /**
     * Update file with new data (append/merge instead of replace)
     */
    private void updateHistoricalDataFile(String symbol, String timeframe, List<CryptoPrice> newData) {
        if (newData.isEmpty())
            return;

        // 1. Load existing data from file
        List<CryptoPrice> existingData = fileService.loadHistoricalData(symbol, timeframe);

        // 2. Merge: existing + new data
        List<CryptoPrice> mergedData = mergeAndSortData(existingData, newData);

        // 3. Save merged data back to file
        fileService.saveHistoricalData(symbol, timeframe, mergedData);

        log.info("📈 Updated {} {}: {} → {} points (added {})",
                symbol, timeframe, existingData.size(), mergedData.size(),
                mergedData.size() - existingData.size());
    }

    public List<CryptoPrice> getFullHistoricalData(String symbol) {
        // Get 5 years of daily data (~1825 points)
        return getHistoricalData(symbol, "1d", 1825);
    }

    public Mono<List<CryptoPrice>> getHistoricalDataReactive(String symbol, String timeframe, int limit) {
        return Mono.fromCallable(() -> getHistoricalData(symbol, timeframe, limit))
                .onErrorResume(e -> {
                    log.error("❌ Failed to load reactive data: {}", e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

    // ===== HELPER METHODS =====

    private List<PriceUpdate> convertToPriceUpdate(List<CryptoPrice> cryptoPrices, String symbol) {
        return cryptoPrices.stream()
                .map(cp -> new PriceUpdate(
                        symbol,
                        cp.getClose(),
                        cp.getVolume(),
                        cp.getTimestamp(),
                        cp.getOpen(),
                        cp.getHigh(),
                        cp.getLow(),
                        cp.getClose()))
                .collect(Collectors.toList());
    }

    private List<CryptoPrice> parseBinanceKlinesToCryptoPrice(String response, String symbol) {
        try {
            List<List<Object>> klines = objectMapper.readValue(response, new TypeReference<>() {
            });
            List<CryptoPrice> cryptoPrices = new ArrayList<>();

            for (List<Object> kline : klines) {
                long timestamp = Long.parseLong(kline.get(0).toString());
                double open = Double.parseDouble(kline.get(1).toString());
                double high = Double.parseDouble(kline.get(2).toString());
                double low = Double.parseDouble(kline.get(3).toString());
                double close = Double.parseDouble(kline.get(4).toString());
                double volume = Double.parseDouble(kline.get(5).toString());

                cryptoPrices.add(new CryptoPrice(
                        symbol, close, volume, timestamp, open, high, low, close));
            }

            return cryptoPrices;
        } catch (Exception e) {
            log.error("❌ Failed to parse Binance response: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get ML training data with timeframe-specific requirements
     */
    public List<CryptoPrice> getMLTrainingData(String symbol, String timeframe) {
        log.info("🤖 Getting ML training data for {} {}...", symbol, timeframe);

        // Determine how much data we need for this timeframe
        int requiredPoints = getRequiredPointsForTimeframe(timeframe);

        // Check if we have enough data in file
        List<CryptoPrice> trainingData = fileService.loadHistoricalData(symbol, timeframe);

        if (trainingData.size() >= requiredPoints) {
            log.info("✅ Using {} existing data points for {} ML training",
                    trainingData.size(), timeframe);
            return trainingData;
        }

        // Need to fetch fresh data
        log.info("🔄 Fetching {} data for {} (target: {} points)",
                timeframe, symbol, requiredPoints);

        List<CryptoPrice> freshData = fetchDeepHistory(symbol, timeframe, requiredPoints);

        // Save for future training
        fileService.saveHistoricalData(symbol, timeframe, freshData);

        // Invalidate cache
        dataCache.invalidate(symbol + "_" + timeframe);

        log.info("✅ Fetched {} fresh data points for {} ML training",
                freshData.size(), timeframe);
        return freshData;
    }

    private int getRequiredPointsForTimeframe(String timeframe) {
        return switch (timeframe) {
            case "1h" -> 2000; // ~3 months of hourly
            case "4h" -> 1000; // ~5.5 months of 4h
            case "1d" -> 1460; // 4 years daily
            case "1W" -> 208; // 4 years weekly (208 weeks)
            case "1M" -> 48; // 4 years monthly (48 months)
            default -> 100;
        };
    }

    private String convertTimeframeToBinanceInterval(String timeframe) {
        return switch (timeframe) {
            case "1m" -> "1m";
            case "1h" -> "1h";
            case "4h" -> "4h";
            case "1d" -> "1d";
            case "1w" -> "1w";
            default -> "1h";
        };
    }

    private int getMaxAgeForTimeframe(String timeframe) {
        return switch (timeframe) {
            case "1m" -> 1; // 1 hour
            case "1h" -> 6; // 6 hours
            case "4h" -> 24; // 1 day
            case "1d" -> 24; // 1 day
            case "1w" -> 168; // 1 week
            default -> 24;
        };
    }
}