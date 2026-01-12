package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartCacheService {

    private final HistoricalDataFileService fileService;

    // L1: Hot data in memory (recent points only)
    private final Map<String, List<CryptoPrice>> hotCache = new ConcurrentHashMap<>();
    private static final int HOT_CACHE_SIZE = 50; // Even smaller for Railway

    /**
     * Get data with smart caching: hot cache → file → update hot cache
     */
    public List<CryptoPrice> getSmartData(String symbol, String timeframe, int limit) {
        String cacheKey = symbol + "_" + timeframe;

        // 1. Try hot cache first (FAST PATH)
        List<CryptoPrice> hotData = hotCache.get(cacheKey);
        if (hotData != null && !hotData.isEmpty()) {
            if (hotData.size() >= limit) {
                log.debug("🔥 Hot cache HIT for {} {} ({} points)", symbol, timeframe, limit);
                int start = Math.max(0, hotData.size() - limit);
                return new ArrayList<>(hotData.subList(start, hotData.size()));
            }
        }

        // 2. Load from file (SLOW PATH)
        log.debug("📁 Loading from file for {} {} (limit: {})", symbol, timeframe, limit);
        List<CryptoPrice> fileData = fileService.loadHistoricalData(symbol, timeframe);

        if (fileData.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. Update hot cache with recent data only
        updateHotCache(cacheKey, fileData);

        // 4. Return requested amount
        int start = Math.max(0, fileData.size() - limit);
        return new ArrayList<>(fileData.subList(start, fileData.size()));
    }

    /**
     * Update hot cache with most recent data only
     */
    private void updateHotCache(String cacheKey, List<CryptoPrice> fullData) {
        if (fullData.size() > HOT_CACHE_SIZE) {
            hotCache.put(cacheKey, new ArrayList<>(
                    fullData.subList(fullData.size() - HOT_CACHE_SIZE, fullData.size())
            ));
        } else {
            hotCache.put(cacheKey, new ArrayList<>(fullData));
        }
    }

    /**
     * Clear hot cache (call during memory pressure)
     */
    public void clearHotCache() {
        int size = hotCache.size();
        hotCache.clear();
        log.info("🧹 Cleared hot cache ({} entries)", size);
    }
}