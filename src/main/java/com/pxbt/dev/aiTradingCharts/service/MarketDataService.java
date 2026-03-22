package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class MarketDataService {

    // Store historical data for each symbol
    private final Map<String, List<PriceUpdate>> historicalData = new ConcurrentHashMap<>();
    // Need to keep this reasonable as many more caused out-of-memory errors on
    // railway deploy
    // Need to keep this reasonable to avoid out-of-memory errors
    private static final int MAX_HISTORICAL_POINTS = 300; // Reduced from 1000

    @Autowired
    private SymbolConfig symbolConfig;

    @Autowired
    @Lazy
    private BinanceHistoricalService binanceHistoricalService;

    private volatile boolean shuttingDown = false;

    /**
     * Load recent historical data from Binance when application starts
     */
    @PostConstruct
    public void loadInitialHistoricalData() {
        // Run in background so we don't block server startup (prevents webServerStartStop failure)
        new Thread(() -> {
            try {
                // Short sleep to let server settle
                Thread.sleep(1000);
                
                log.info("🔄 Background: Loading recent historical data for real-time analysis...");
                List<String> symbols = symbolConfig.getSymbols();

                for (String symbol : symbols) {
                    if (shuttingDown) break;
                    try {
                        List<PriceUpdate> recentData = binanceHistoricalService.getHistoricalDataAsPriceUpdate(
                                symbol, "1d", MAX_HISTORICAL_POINTS);

                        if (!recentData.isEmpty()) {
                            historicalData.put(symbol, new ArrayList<>(recentData));
                            log.info("✅ Background: Loaded {} recent points for {} (back to {})",
                                    recentData.size(), symbol,
                                    new Date(recentData.get(0).getTimestamp()));
                        }
                    } catch (Exception e) {
                        log.error("❌ Background: Failed to load initial data for {}: {}", symbol, e.getMessage());
                        historicalData.put(symbol, new ArrayList<>());
                    }
                }
                logDataStatus();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MarketData-Init").start();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
    }

    @Scheduled(fixedRate = 600000) // Every 10 minutes
    public void trimMemoryCache() {
        for (Map.Entry<String, List<PriceUpdate>> entry : historicalData.entrySet()) {
            List<PriceUpdate> data = entry.getValue();
            synchronized (data) {
                if (data.size() > MAX_HISTORICAL_POINTS) {
                    List<PriceUpdate> newData = new ArrayList<>(
                            data.subList(data.size() - MAX_HISTORICAL_POINTS, data.size()));
                    historicalData.put(entry.getKey(), newData);
                }
            }
        }
        log.debug("✂️ Trimmed memory caches to {}", MAX_HISTORICAL_POINTS);
    }

    /**
     * Add new price update to historical data
     */
    public void addPriceUpdate(PriceUpdate priceUpdate) {
        String symbol = priceUpdate.getSymbol();

        List<PriceUpdate> data = historicalData.computeIfAbsent(symbol, k -> new ArrayList<>());

        synchronized (data) {
            // Add the new price update
            data.add(priceUpdate);

            // Keep data manageable - remove the oldest points if we exceed the limit
            while (data.size() > MAX_HISTORICAL_POINTS) {
                data.remove(0);
            }
        }

        log.debug("💾 Stored price data: {} at ${} (Total: {} points)",
                symbol, priceUpdate.getPrice(), data.size());
    }

    /**
     * Get historical data for a symbol
     * 
     * @param symbol The symbol to get data for
     * @param limit  Maximum number of data points to return (returns most recent)
     * @return List of price updates, most recent first
     */
    public List<PriceUpdate> getHistoricalData(String symbol, int limit) {
        List<PriceUpdate> data = historicalData.get(symbol);

        if (data == null) {
            return new ArrayList<>();
        }

        synchronized (data) {
            if (data.isEmpty()) {
                return new ArrayList<>();
            }

            // Return the most recent 'limit' data points
            int startIndex = Math.max(0, data.size() - limit);
            return new ArrayList<>(data.subList(startIndex, data.size()));
        }
    }

    /**
     * Get all available historical data for a symbol
     */
    public List<PriceUpdate> getHistoricalData(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null) return new ArrayList<>();
        synchronized (data) {
            return new ArrayList<>(data);
        }
    }

    /**
     * Get the number of data points available for a symbol
     */
    public int getDataCount(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null) return 0;
        synchronized (data) {
            return data.size();
        }
    }

    /**
     * Get the most recent price for a symbol
     */
    public Double getCurrentPrice(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null) return null;
        synchronized (data) {
            if (data.isEmpty()) {
                return null;
            }
            return data.get(data.size() - 1).getPrice();
        }
    }

    /**
     * Get the timestamp of the most recent update for a symbol
     */
    public Long getLastUpdateTime(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null) return null;
        synchronized (data) {
            if (data.isEmpty()) {
                return null;
            }
            return data.get(data.size() - 1).getTimestamp();
        }
    }

    /**
     * Check if we have sufficient data for analysis
     */
    public boolean hasSufficientData(String symbol, int minimumPoints) {
        return getDataCount(symbol) >= minimumPoints;
    }

    /**
     * Get data coverage in days for a symbol
     */
    public double getDataCoverageDays(String symbol) {
        List<PriceUpdate> data = getHistoricalData(symbol);
        if (data.size() < 2) {
            return 0.0;
        }

        long startTime = data.get(0).getTimestamp();
        long endTime = data.get(data.size() - 1).getTimestamp();
        long durationMs = endTime - startTime;

        return durationMs / (1000.0 * 60 * 60 * 24); // Convert to days
    }

    /**
     * Get all available symbols that have data
     */
    public List<String> getAvailableSymbols() {
        return new ArrayList<>(historicalData.keySet());
    }

    /**
     * Log current data status for monitoring
     */
    public void logDataStatus() {
        log.info("📊 Current Market Data Status:");
        for (String symbol : getAvailableSymbols()) {
            int count = getDataCount(symbol);
            double coverage = getDataCoverageDays(symbol);
            Double currentPrice = getCurrentPrice(symbol);

            log.info("   {}: {} points, {} days coverage, Current: {}",
                    symbol,
                    count,
                    String.format("%.1f", coverage), // This formats coverage to 1 decimal place
                    currentPrice != null ? String.format("$%.2f", currentPrice) : "N/A");
        }
    }

}