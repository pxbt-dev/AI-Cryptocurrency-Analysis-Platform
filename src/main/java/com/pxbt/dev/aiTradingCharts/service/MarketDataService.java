package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    // Need to keep this reasonable as many more caused out-of-memory errors on railway deploy
    private static final int MAX_HISTORICAL_POINTS = 1000; // Match Binance loading

    @Autowired
    private BinanceHistoricalService binanceHistoricalService;

    /**
     * Load extensive historical data from Binance when application starts
     */
    @PostConstruct
    public void loadInitialHistoricalData() {
        log.info("🔄 Loading DEEP historical data for ML...");

        String[] symbols = {"BTC", "SOL", "TAO", "WIF"};

        for (String symbol : symbols) {
            try {
                // Force deep fetch for ML training (5 years = 1825 points)
                List<PriceUpdate> deepData = binanceHistoricalService.getHistoricalDataAsPriceUpdate(
                        symbol, "1d", 1825  // 5 years for proper ML training
                );

                if (!deepData.isEmpty()) {
                    historicalData.put(symbol, new CopyOnWriteArrayList<>(deepData));
                    log.info("✅ Loaded {} DEEP historical points for {} (back to {})",
                            deepData.size(), symbol,
                            new Date(deepData.get(0).getTimestamp()));
                }
            } catch (Exception e) {
                log.error("❌ Failed to load deep data for {}: {}", symbol, e.getMessage());
                historicalData.put(symbol, new CopyOnWriteArrayList<>());
            }
        }

        logDataStatus();
    }


    /**
     * Add new price update to historical data
     */
    public void addPriceUpdate(PriceUpdate priceUpdate) {
        String symbol = priceUpdate.getSymbol();

        historicalData.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>());

        List<PriceUpdate> data = historicalData.get(symbol);

        // Add the new price update
        data.add(priceUpdate);

        // Keep data manageable - remove the oldest points if we exceed the limit
        if (data.size() > MAX_HISTORICAL_POINTS) {
            int excess = data.size() - MAX_HISTORICAL_POINTS;
            // Remove oldest 'excess' number of elements
            for (int i = 0; i < excess; i++) {
                if (!data.isEmpty()) {
                    data.remove(0);
                }
            }
        }

        log.debug("💾 Stored price data: {} at ${} (Total: {} points)",
                symbol, priceUpdate.getPrice(), data.size());
    }

    /**
     * Get historical data for a symbol
     * @param symbol The symbol to get data for
     * @param limit Maximum number of data points to return (returns most recent)
     * @return List of price updates, most recent first
     */
    public List<PriceUpdate> getHistoricalData(String symbol, int limit) {
        List<PriceUpdate> data = historicalData.getOrDefault(symbol, new CopyOnWriteArrayList<>());

        if (data.isEmpty()) {
            return new ArrayList<>();
        }

        // Return the most recent 'limit' data points
        int startIndex = Math.max(0, data.size() - limit);
        return new ArrayList<>(data.subList(startIndex, data.size()));
    }

    /**
     * Get all available historical data for a symbol
     */
    public List<PriceUpdate> getHistoricalData(String symbol) {
        return new ArrayList<>(historicalData.getOrDefault(symbol, new CopyOnWriteArrayList<>()));
    }

    /**
     * Get the number of data points available for a symbol
     */
    public int getDataCount(String symbol) {
        return historicalData.getOrDefault(symbol, new CopyOnWriteArrayList<>()).size();
    }

    /**
     * Get the most recent price for a symbol
     */
    public Double getCurrentPrice(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.get(data.size() - 1).getPrice();
    }

    /**
     * Get the timestamp of the most recent update for a symbol
     */
    public Long getLastUpdateTime(String symbol) {
        List<PriceUpdate> data = historicalData.get(symbol);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.get(data.size() - 1).getTimestamp();
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
                    String.format("%.1f", coverage),  // This formats coverage to 1 decimal place
                    currentPrice != null ? String.format("$%.2f", currentPrice) : "N/A");
        }
    }

}