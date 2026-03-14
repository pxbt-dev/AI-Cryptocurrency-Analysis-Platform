package com.pxbt.dev.aiTradingCharts.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartCacheService {

    private final HistoricalDataFileService fileService;

    // L1: Hot data in memory - Bounded to 50 active timeframe sets to prevent creep
    private final Cache<String, List<CryptoPrice>> hotCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .softValues() // Vital: Allows JVM to reclaim these lists under memory pressure
            .build();
    
    private static final int HOT_CACHE_SIZE = 300; 

    /**
     * Get data with smart caching: hot cache → file → update hot cache
     */
    public List<CryptoPrice> getSmartData(String symbol, String timeframe, int limit) {
        String cacheKey = symbol + "_" + timeframe;

        // 1. Try hot cache first (FAST PATH)
        List<CryptoPrice> hotData = hotCache.getIfPresent(cacheKey);
        if (hotData != null && !hotData.isEmpty()) {
            if (hotData.size() >= limit) {
                log.debug("🔥 Hot cache HIT for {} {} ({} points)", symbol, timeframe, limit);
                int start = Math.max(0, hotData.size() - limit);
                return new ArrayList<>(hotData.subList(start, hotData.size()));
            }
        }

        // 2. Load from file (SLOW PATH) 
        log.debug("📁 Loading recent data from file for {} {} (requested: {}, cache_max: {})", 
                symbol, timeframe, limit, HOT_CACHE_SIZE);
        
        int pointsToLoad = Math.max(limit, HOT_CACHE_SIZE);
        List<CryptoPrice> fileData = fileService.loadRecentData(symbol, timeframe, pointsToLoad);

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
        List<CryptoPrice> listToCache;
        if (fullData.size() > HOT_CACHE_SIZE) {
            listToCache = new ArrayList<>(
                    fullData.subList(fullData.size() - HOT_CACHE_SIZE, fullData.size())
            );
        } else {
            listToCache = new ArrayList<>(fullData);
        }
        hotCache.put(cacheKey, listToCache);
    }

    /**
     * Clear hot cache
     */
    public void clearHotCache() {
        long size = hotCache.estimatedSize();
        hotCache.invalidateAll();
        log.info("🧹 Cleared hot cache (estimated {} entries)", size);
    }
}