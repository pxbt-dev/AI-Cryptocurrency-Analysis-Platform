package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.Gateway.BinanceGateway;
import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceHistoricalService {


    /**
     * Training data cutoff - November 1, 2022 (start of current market cycle)
     * This ensures models train on relevant market regime (post-FTX, ETF era)
     */
    private static final long TRAINING_DATA_CUTOFF = LocalDateTime.of(2022, 11, 1, 0, 0)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli();

    /**
     * Maximum allowed price change per candle (to catch corrupted data)
     * Different limits for different timeframes
     */
    private static final double MAX_DAILY_CHANGE_PCT = 0.15;    // 15% max daily change
    private static final double MAX_WEEKLY_CHANGE_PCT = 0.30;   // 30% max weekly change
    private static final double MAX_MONTHLY_CHANGE_PCT = 0.50;  // 50% max monthly change
    @Autowired
    private SymbolConfig symbolConfig;

    @Autowired
    @Lazy
    private CryptoWebSocketHandler webSocketHandler;

    @Autowired
    @Lazy
    private HistoricalDataFileService fileService;

    @Autowired
    private SmartCacheService smartCacheService;

    private final Map<String, Long> lastDeepFetchTime = new ConcurrentHashMap<>();
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
     * Fetch deep historical data with multiple Binance API calls
     */
    private List<CryptoPrice> fetchDeepHistoricalData(String symbol, String timeframe, int totalPoints) {
        try {
            List<CryptoPrice> allData = new ArrayList<>();
            int remainingPoints = totalPoints;
            Long endTime = null; // Start with most recent

            int batchNum = 1;
            while (remainingPoints > 0) {
                int batchSize = Math.min(remainingPoints, 1000);

                log.info("📡 Batch {}: {} {} for {} (endTime: {})",
                        batchNum, batchSize, timeframe, symbol,
                        endTime != null ? new Date(endTime) : "latest");
                webSocketHandler.broadcastEvent("NETWORK",
                        String.format("Fetching %s %s batch %d...", symbol, timeframe, batchNum));

                // Use the overloaded method with endTime!
                String response = binanceGateway.getRawKlines(symbol,
                        convertTimeframeToBinanceInterval(timeframe),
                        batchSize, endTime)
                        .timeout(Duration.ofSeconds(30))
                        .blockOptional()
                        .orElse("[]");

                List<CryptoPrice> batch = parseBinanceKlinesToCryptoPrice(response, symbol);
                if (batch.isEmpty())
                    break;

                // Add to beginning (oldest first)
                allData.addAll(0, batch);
                remainingPoints -= batch.size();

                // Set endTime for next batch (go further back)
                if (!batch.isEmpty()) {
                    endTime = batch.get(0).getTimestamp() - 1; // 1 ms before oldest
                }

                batchNum++;
                Thread.sleep(1000); // Rate limiting
            }

            allData = mergeAndSortData(allData);
            log.info("✅ Total {} points for {} {} (back to {})",
                    allData.size(), symbol, timeframe,
                    !allData.isEmpty() ? new Date(allData.get(0).getTimestamp()) : "N/A");

            return allData;

        } catch (Exception e) {
            log.error("❌ Deep fetch failed: {}", e.getMessage());
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
                    .timeout(Duration.ofSeconds(30))
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
     * Merge and remove duplicates
     */
    private List<CryptoPrice> mergeAndSortData(List<CryptoPrice> data) {
        Map<Long, CryptoPrice> uniqueMap = new HashMap<>();
        for (CryptoPrice cp : data) {
            uniqueMap.put(cp.getTimestamp(), cp);
        }

        List<CryptoPrice> merged = new ArrayList<>(uniqueMap.values());
        merged.sort(Comparator.comparing(CryptoPrice::getTimestamp));

        return merged;
    }

    /**
     * Background update for ML retraining (every 6 hours)
     */
    @Scheduled(cron = "0 0 2,8,14,20 * * *")
    public void dailyMLUpdate() {
        log.info("🤖 Starting daily ML data update");

        List<String> symbols = symbolConfig.getSymbols();
        String[] mlTimeframes = { "1d", "1w", "1m" };

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
            int recentPoints = timeframe.equalsIgnoreCase("1d") ? 30 : 168; 

            List<CryptoPrice> newData = fetchBinanceData(symbol, timeframe, recentPoints);
            if (newData.isEmpty())
                return;

            // Load existing
            List<CryptoPrice> existing = fileService.loadHistoricalData(symbol, timeframe);

            // Merge
            List<CryptoPrice> merged = mergeAndSortData(existing);
            merged.addAll(newData);
            merged = mergeAndSortData(merged); // Remove duplicates

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
            // 1. Check file first
            // List<CryptoPrice> fileData = fileService.loadHistoricalData(symbol,
            // timeframe);
            List<CryptoPrice> fileData = smartCacheService.getSmartData(symbol, timeframe, limit);

            // 2. Check if we need update
            int maxAgeHours = getMaxAgeForTimeframe(timeframe);
            if (!fileData.isEmpty() && !fileService.needsUpdate(symbol, timeframe, maxAgeHours)) {
                // Return subset if we have enough data
                if (fileData.size() >= limit) {
                    int start = Math.max(0, fileData.size() - limit);
                    return new ArrayList<>(fileData.subList(start, fileData.size()));
                }
            }

            // 3. Fetch fresh data
            List<CryptoPrice> freshData;
            if (timeframe.equalsIgnoreCase("1d") && limit > 1000) {
                freshData = fetchDeepHistoricalData(symbol, timeframe, limit);
            } else {
                freshData = fetchBinanceData(symbol, timeframe, Math.min(limit, 1000));
            }

            // 4. Update file (append/merge, not replace)
            if (!freshData.isEmpty()) {
                updateHistoricalDataFile(symbol, timeframe, freshData);

                // Reload full dataset from file
                fileData = fileService.loadHistoricalData(symbol, timeframe);

                // Return requested amount
                if (fileData.size() >= limit) {
                    int start = Math.max(0, fileData.size() - limit);
                    return new ArrayList<>(fileData.subList(start, fileData.size()));
                }
            }

            return freshData;
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
        List<CryptoPrice> mergedData = mergeData(existingData, newData);

        // 3. Save merged data back to file
        fileService.saveHistoricalData(symbol, timeframe, mergedData);

        log.info("📈 Updated {} {}: {} → {} points (added {})",
                symbol, timeframe, existingData.size(), mergedData.size(),
                mergedData.size() - existingData.size());
    }

    /**
     * Merge new data with existing, remove duplicates
     */
    private List<CryptoPrice> mergeData(List<CryptoPrice> existing, List<CryptoPrice> newData) {
        if (existing.isEmpty())
            return newData;
        if (newData.isEmpty())
            return existing;

        // Use map to remove duplicates by timestamp (newer data wins)
        Map<Long, CryptoPrice> mergedMap = new TreeMap<>();

        // Add existing data
        for (CryptoPrice data : existing) {
            mergedMap.put(data.getTimestamp(), data);
        }

        // Add/overwrite with new data
        for (CryptoPrice data : newData) {
            mergedMap.put(data.getTimestamp(), data);
        }

        // Convert back to sorted list
        return new ArrayList<>(mergedMap.values());
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

        // Determine how much data we need for this timeframe (adjusted for post-2022 era)
        int requiredPoints = getRequiredPointsForTimeframe(timeframe);

        // Check if we have enough data in file
        List<CryptoPrice> trainingData = fileService.loadHistoricalData(symbol, timeframe);

        // Apply cutoff filter to remove pre-Nov 2022 data
        trainingData = filterDataByCutoff(trainingData);

        // Apply validation to remove corrupted data
        trainingData = validateAndCleanData(trainingData, timeframe);

        if (trainingData.size() >= requiredPoints) {
            log.info("✅ Using {} existing data points for {} ML training (filtered & validated)",
                    trainingData.size(), timeframe);
            return trainingData;
        }

        // COOLDOWN: Don't deep fetch the same symbol/timeframe more than once every 24 hours
        // to prevent "Deep Fetch Death Loops" if Binance doesn't have more data.
        String cooldownKey = symbol + "_" + timeframe;
        Long lastFetch = lastDeepFetchTime.get(cooldownKey);
        if (lastFetch != null && (System.currentTimeMillis() - lastFetch) < TimeUnit.HOURS.toMillis(24)) {
            log.info("⏭️ Skipping deep fetch for {} {}: Cooldown active (size: {})", 
                    symbol, timeframe, trainingData.size());
            return trainingData;
        }

        // Need to fetch fresh data
        log.info("🔄 Fetching {} data for {} (target: {} points, cutoff: Nov 2022)",
                timeframe, symbol, requiredPoints);

        List<CryptoPrice> freshData = fetchOptimizedData(symbol, timeframe, requiredPoints);
        lastDeepFetchTime.put(cooldownKey, System.currentTimeMillis());

        // Apply filters to fresh data
        freshData = filterDataByCutoff(freshData);
        freshData = validateAndCleanData(freshData, timeframe);

        // Save for future training
        fileService.saveHistoricalData(symbol, timeframe, freshData);

        // Invalidate cache
        dataCache.invalidate(symbol + "_" + timeframe);

        log.info("✅ Fetched {} fresh data points for {} ML training (after filtering)",
                freshData.size(), timeframe);
        return freshData;
    }
    private int getRequiredPointsForTimeframe(String timeframe) {
        // Adjusted for post-Nov 2022 era (current market regime)
        // Nov 2022 to present = ~2.5 years
        return switch (timeframe.toLowerCase()) {
            case "1d" -> 650;   // ~2 years of daily data (plenty for training)
            case "1w" -> 130;   // ~2.5 years of weekly data
            case "1m" -> 30;    // ~2.5 years of monthly data (light but usable)
            default -> 100;
        };
    }

    /**
     * Fetch data with multiple API calls for deep history
     */
    private List<CryptoPrice> fetchOptimizedData(String symbol, String timeframe, int totalPoints) {
        try {
            List<CryptoPrice> allData = new ArrayList<>();
            int remainingPoints = totalPoints;
            Long endTime = null;

            int batchNum = 1;
            int maxBatches = (int) Math.ceil(totalPoints / 1000.0);

            while (remainingPoints > 0 && batchNum <= maxBatches) {
                int batchSize = Math.min(remainingPoints, 1000);

                log.info("📡 Batch {}/{}: {} {} for {}",
                        batchNum, maxBatches, batchSize, timeframe, symbol);

                String response = binanceGateway.getRawKlines(symbol,
                        convertTimeframeToBinanceInterval(timeframe),
                        batchSize, endTime)
                        .timeout(Duration.ofSeconds(30))
                        .blockOptional()
                        .orElse("[]");

                List<CryptoPrice> batch = parseBinanceKlinesToCryptoPrice(response, symbol);
                if (batch.isEmpty())
                    break;

                // Add to beginning (oldest first for weekly/monthly)
                if (timeframe.equalsIgnoreCase("1w") || timeframe.equalsIgnoreCase("1m")) {
                    allData.addAll(0, batch);
                } else {
                    allData.addAll(batch);
                }

                remainingPoints -= batch.size();

                // Set endTime for next batch (go further back)
                if (!batch.isEmpty()) {
                    endTime = batch.get(0).getTimestamp() - 1;
                }

                batchNum++;
                if (batchNum <= maxBatches) {
                    Thread.sleep(1000); // Rate limiting between batches
                }
            }

            // Sort by timestamp
            allData.sort(Comparator.comparing(CryptoPrice::getTimestamp));

            log.info("✅ Fetched total {} points for {} {} (back to {})",
                    allData.size(), symbol, timeframe,
                    !allData.isEmpty() ? new Date(allData.get(0).getTimestamp()) : "N/A");

            return allData;

        } catch (Exception e) {
            log.error("❌ Optimized fetch failed for {} {}: {}", symbol, timeframe, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String convertTimeframeToBinanceInterval(String timeframe) {
        return switch (timeframe) {
            case "1d" -> "1d";
            case "1w", "1W" -> "1w";
            case "1m", "1M" -> "1M";
            default -> "1d";
        };
    }

    private int getMaxAgeForTimeframe(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1d" -> 24; 
            case "1w" -> 168; 
            case "1m" -> 720; 
            default -> 24;
        };
    }


    /**
     * Filter data to only include candles after the training cutoff date (Nov 1, 2022)
     * This ensures models train on the current market regime, not outdated patterns
     */
    private List<CryptoPrice> filterDataByCutoff(List<CryptoPrice> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        int beforeCount = data.size();
        List<CryptoPrice> filtered = data.stream()
                .filter(cp -> cp.getTimestamp() >= TRAINING_DATA_CUTOFF)
                .collect(Collectors.toList());

        int filteredCount = beforeCount - filtered.size();
        if (filteredCount > 0) {
            log.info("🗑️ Filtered out {} data points before Nov 1, 2022 ({} -> {})",
                    filteredCount, beforeCount, filtered.size());
        }

        return filtered;
    }

    /**
     * Validate and remove corrupted data points (impossible price movements)
     */
    private List<CryptoPrice> validateAndCleanData(List<CryptoPrice> data, String timeframe) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        double maxChangePct = switch (timeframe.toLowerCase()) {
            case "1d" -> MAX_DAILY_CHANGE_PCT;
            case "1w" -> MAX_WEEKLY_CHANGE_PCT;
            case "1m" -> MAX_MONTHLY_CHANGE_PCT;
            default -> MAX_DAILY_CHANGE_PCT;
        };

        List<CryptoPrice> cleaned = new ArrayList<>();
        int invalidCount = 0;

        for (int i = 0; i < data.size(); i++) {
            CryptoPrice current = data.get(i);

            // Check for invalid prices (zero, negative, NaN)
            if (current.getClose() <= 0 || current.getOpen() <= 0 ||
                current.getHigh() <= 0 || current.getLow() <= 0 ||
                Double.isNaN(current.getClose()) || Double.isInfinite(current.getClose())) {
                log.warn("⚠️ Invalid price data at {}: close={}",
                        new Date(current.getTimestamp()), current.getClose());
                invalidCount++;
                continue;
            }

            // Check for impossible OHLC relationships
            if (current.getHigh() < current.getLow() ||
                current.getHigh() < current.getOpen() ||
                current.getHigh() < current.getClose() ||
                current.getLow() > current.getOpen() ||
                current.getLow() > current.getClose()) {
                log.warn("⚠️ Invalid OHLC at {}: high={} low={} open={} close={}",
                        new Date(current.getTimestamp()), current.getHigh(), current.getLow(),
                        current.getOpen(), current.getClose());
                invalidCount++;
                continue;
            }

            // Check for extreme price changes from previous candle
            if (i > 0) {
                CryptoPrice prev = data.get(i - 1);
                double changePct = Math.abs((current.getClose() - prev.getClose()) / prev.getClose());

                if (changePct > maxChangePct) {
                    log.warn("⚠️ Extreme price change detected at {}: {:.2f}% (prev={} → curr={})",
                            new Date(current.getTimestamp()), changePct * 100,
                            prev.getClose(), current.getClose());
                    invalidCount++;
                    continue;
                }
            }

            cleaned.add(current);
        }

        if (invalidCount > 0) {
            log.warn("🧹 Removed {} invalid data points from {} {} ({} -> {})",
                    invalidCount, timeframe, "data", data.size(), cleaned.size());
        }

        return cleaned;
    }
    public void pruneFileIfNeeded(String symbol, String timeframe, int limit) {
        fileService.pruneFileIfNeeded(symbol, timeframe, limit);
    }
}