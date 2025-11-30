package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.Gateway.BinanceGateway;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceHistoricalService {

    private final BinanceGateway binanceGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, List<CryptoPrice>> currentData = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("📚 BinanceHistoricalService initializing with long term data...");
        loadAllDataInOneCall();
    }

    private void loadAllDataInOneCall() {
        try {
            Map<String, List<CryptoPrice>> allData = new HashMap<>();
            String[] symbols = {"BTC", "SOL", "TAO", "WIF"};

            for (String symbol : symbols) {
                // ✅ LOAD 1000 POINTS FOR AI PREDICTIONS
                List<CryptoPrice> symbolData = fetchBinanceData(symbol, "1d", 1000);
                allData.put(symbol, symbolData);

                log.info("📊 {}: Loaded {} data points covering ~{} years",
                        symbol, symbolData.size(), symbolData.size() / 365);
            }

            currentData.putAll(allData);
            log.info("Historical data loaded from Binance");

        } catch (Exception e) {
            log.error("❌ Failed to load historical data: {}", e.getMessage());
            currentData = Map.of(
                    "BTC", new ArrayList<>(),
                    "SOL", new ArrayList<>(),
                    "TAO", new ArrayList<>(),
                    "WIF", new ArrayList<>()
            );
        }
    }

    // ===== METHODS FOR MarketDataService (PriceUpdate) =====

    /**
     * ✅ FOR MarketDataService - returns PriceUpdate objects
     * Renamed to avoid method clash
     */
    public List<PriceUpdate> getHistoricalDataAsPriceUpdate(String symbol, String interval, int limit) {
        try {
            String response = binanceGateway.getRawKlines(symbol, interval, limit).block();
            return parseBinanceKlinesToPriceUpdate(response, symbol);
        } catch (Exception e) {
            log.error("❌ Failed to get historical data for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * ✅ KEEP THIS - MarketDataService needs it
     */
    private List<PriceUpdate> parseBinanceKlinesToPriceUpdate(String response, String symbol) {
        try {
            List<List<Object>> klines = objectMapper.readValue(response, new TypeReference<>() {
            });

            List<PriceUpdate> priceUpdates = new ArrayList<>();
            for (List<Object> kline : klines) {
                long timestamp = Long.parseLong(kline.get(0).toString());
                double open = Double.parseDouble(kline.get(1).toString());
                double high = Double.parseDouble(kline.get(2).toString());
                double low = Double.parseDouble(kline.get(3).toString());
                double close = Double.parseDouble(kline.get(4).toString());
                double volume = Double.parseDouble(kline.get(5).toString());

                PriceUpdate update = new PriceUpdate(
                        symbol,
                        close,      // price (current price)
                        volume,     // volume
                        timestamp,  // timestamp
                        open,       // open
                        high,       // high
                        low,        // low
                        close       // close
                );
                priceUpdates.add(update);
            }

            log.info("✅ Loaded {} historical OHLC data points for {}", priceUpdates.size(), symbol);
            return priceUpdates;
        } catch (Exception e) {
            log.error("❌ Failed to parse Binance response: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== METHODS FOR PricePredictionService (CryptoPrice) =====

    /**
     * ✅ FOR PricePredictionService - returns cached CryptoPrice data
     */
    public List<CryptoPrice> getHistoricalData(String symbol, int days) {
        List<CryptoPrice> data = currentData.get(symbol);
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }

        int pointsNeeded = Math.min(days, data.size());
        int startIndex = Math.max(0, data.size() - pointsNeeded);
        return new ArrayList<>(data.subList(startIndex, data.size()));
    }

    public List<CryptoPrice> getFullHistoricalData(String symbol) {
        return currentData.getOrDefault(symbol, new ArrayList<>());
    }

    // ===== METHODS FOR Frontend/Controllers (CryptoPrice) =====

    /**
     * ✅ FOR Frontend - returns reactive CryptoPrice data
     * Renamed to avoid method clash
     */
    public Mono<List<CryptoPrice>> getHistoricalDataReactive(String symbol, String timeframe, int limit) {
        String binanceInterval = convertTimeframeToBinanceInterval(timeframe);
        return binanceGateway.getRawKlines(symbol, binanceInterval, limit)
                .map(response -> parseBinanceKlinesToCryptoPrice(response, symbol))
                .doOnSuccess(data -> log.info("Loaded {} {} data points for {}", data.size(), timeframe, symbol));
    }

    // ===== PRIVATE HELPER METHODS =====

    /**
     * ✅ Parsing method that returns CryptoPrice
     */
    private List<CryptoPrice> parseBinanceKlinesToCryptoPrice(String response, String symbol) {
        try {
            List<List<Object>> klines = objectMapper.readValue(response, new TypeReference<>() {});

            List<CryptoPrice> cryptoPrices = new ArrayList<>();
            for (List<Object> kline : klines) {
                long timestamp = Long.parseLong(kline.get(0).toString());
                double open = Double.parseDouble(kline.get(1).toString());
                double high = Double.parseDouble(kline.get(2).toString());
                double low = Double.parseDouble(kline.get(3).toString());
                double close = Double.parseDouble(kline.get(4).toString());
                double volume = Double.parseDouble(kline.get(5).toString());

                CryptoPrice cryptoPrice = new CryptoPrice(
                        symbol, close, volume, timestamp, open, high, low, close
                );
                cryptoPrices.add(cryptoPrice);
            }

            log.info("✅ Loaded {} historical OHLC data points for {}", cryptoPrices.size(), symbol);
            return cryptoPrices;
        } catch (Exception e) {
            log.error("❌ Failed to parse Binance response: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * ✅ Helper method for batch fetching
     */
    private List<CryptoPrice> fetchBinanceData(String symbol, String timeframe, int limit) {
        try {
            String binanceInterval = convertTimeframeToBinanceInterval(timeframe);
            String response = binanceGateway.getRawKlines(symbol, binanceInterval, limit).block();
            return parseBinanceKlinesToCryptoPrice(response, symbol);
        } catch (Exception e) {
            log.error("❌ Failed to fetch Binance data for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String convertTimeframeToBinanceInterval(String timeframe) {
        return switch(timeframe) {
            case "1m" -> "1m";
            case "1h" -> "1h";
            case "4h" -> "4h";
            case "1d" -> "1d";
            case "1w" -> "1w";
            default -> "1h";
        };
    }
}
