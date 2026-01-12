package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import com.pxbt.dev.aiTradingCharts.model.*;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
@EnableScheduling
public class RealTimeDataService {

    private final Map<String, Deque<PriceUpdate>> priceCache = new ConcurrentHashMap<>();
    private final List<WebSocketClient> webSocketClients = new ArrayList<>();

    // Smart polling control
    private long lastDataBroadcastTime = 0;

    @Autowired
    private CryptoWebSocketHandler webSocketHandler;

    @Autowired
    private PricePredictionService predictionService;

    @Autowired
    private BinanceHistoricalService binanceHistoricalService;

    @Autowired
    private ChartPatternService chartPatternService;

    @Autowired
    private FibonacciTimeZoneService fibonacciTimeZoneService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> symbols = Arrays.asList("BTC", "SOL", "TAO", "WIF");
    private final Map<String, String> symbolToStream = Map.of(
            "BTC", "btcusdt@ticker",
            "SOL", "solusdt@ticker",
            "TAO", "taousdt@ticker",
            "WIF", "wifusdt@ticker"
    );

    @PostConstruct
    public void init() {
        log.info("🚀 INITIALIZING RealTimeDataService - Real-Time Broadcasting Enabled");
        log.info("📊 Real-time updates: EVERY PRICE CHANGE | Manual refresh: 2 minutes");
        connectToBinanceWebSockets();
    }

    private void connectToBinanceWebSockets() {
        log.info("🔗 Connecting to Binance WebSockets (real-time mode)...");

        for (String symbol : symbols) {
            String streamName = symbolToStream.get(symbol);
            if (streamName != null) {
                connectToSymbolWebSocket(symbol, streamName);
                // Small delay to avoid rate limiting
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }

        log.info("✅ WebSocket connections established (real-time broadcasting)");
    }

    private void connectToSymbolWebSocket(String symbol, String streamName) {
        try {
            String binanceUrl = "wss://stream.binance.com:9443/ws/" + streamName;
            log.debug("🔗 Connecting {} -> {}", symbol, binanceUrl);

            WebSocketClient client = new WebSocketClient(new URI(binanceUrl)) {
                @Override
                public void onMessage(String message) {
                    // REAL-TIME MODE: Process AND broadcast every update
                    processRealTimeUpdate(message, symbol, true);
                }

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.debug("✅ {} WebSocket CONNECTED (real-time)", symbol);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("❌ {} WebSocket CLOSED - Reason: {}", symbol, reason);
                    // Don't auto-reconnect aggressively
                    scheduleGentleReconnection(symbol, streamName);
                }

                @Override
                public void onError(Exception ex) {
                    log.debug("💥 {} WebSocket ERROR: {}", symbol, ex.getMessage());
                }
            };

            client.connect();
            webSocketClients.add(client);

        } catch (Exception e) {
            log.error("❌ Failed to connect {} WebSocket: {}", symbol, e.getMessage());
        }
    }

    private void scheduleGentleReconnection(String symbol, String streamName) {
        log.info("🔄 Scheduling {} reconnection in 30 seconds...", symbol);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                log.info("🔄 Attempting {} reconnection...", symbol);
                connectToSymbolWebSocket(symbol, streamName);
            }
        }, 30000); // 30 seconds - be gentle
    }

    /**
     * Process update with REAL-TIME broadcasting
     */
    private void processRealTimeUpdate(String message, String symbol, boolean forceBroadcast) {
        try {
            JsonNode update = objectMapper.readTree(message);

            double price = update.has("c") ? update.get("c").asDouble() : 0;
            double volume = update.has("v") ? update.get("v").asDouble() : 0;

            // Validate data
            if (price <= 0) {
                log.debug("⚠️ Invalid price for {}: {}", symbol, price);
                return;
            }

            PriceUpdate priceUpdate = new PriceUpdate(symbol, price, volume, System.currentTimeMillis());

            // Always update cache (for manual predictions)
            updatePriceCache(symbol, priceUpdate);

            // Send to AI analysis
            AIAnalysisResult analysis = analyzeWithAI(priceUpdate);

            // Broadcast to WebSocket clients
            broadcastUpdate(priceUpdate, analysis);

            lastDataBroadcastTime = System.currentTimeMillis();

        } catch (Exception e) {
            log.error("❌ Error processing {} update: {}", symbol, e.getMessage());
        }
    }

    private void updatePriceCache(String symbol, PriceUpdate priceUpdate) {
        priceCache.computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
                .add(priceUpdate);

        // Keep only last 100 updates per symbol (clean memory)
        Deque<PriceUpdate> symbolCache = priceCache.get(symbol);
        while (symbolCache.size() > 100) {
            symbolCache.removeFirst();
        }
    }

    /**
     * MANUAL REFRESH - Force update all symbols
     */
    public void manualRefresh() {
        log.info("🎯 MANUAL REFRESH triggered - Broadcasting all symbols");

        for (String symbol : symbols) {
            try {
                // Get latest price from cache or generate synthetic update
                PriceUpdate latestUpdate = getLatestPriceUpdate(symbol);
                if (latestUpdate != null) {
                    processRealTimeUpdate(
                            createSyntheticMessage(latestUpdate),
                            symbol,
                            true // Force broadcast
                    );
                }
            } catch (Exception e) {
                log.error("❌ Manual refresh failed for {}: {}", symbol, e.getMessage());
            }
        }

        log.info("✅ Manual refresh completed");
    }

    private PriceUpdate getLatestPriceUpdate(String symbol) {
        Deque<PriceUpdate> symbolCache = priceCache.get(symbol);
        if (symbolCache != null && !symbolCache.isEmpty()) {
            return symbolCache.getLast();
        }
        return null;
    }

    private String createSyntheticMessage(PriceUpdate update) {
        try {
            Map<String, Object> syntheticMessage = new HashMap<>();
            syntheticMessage.put("s", update.getSymbol() + "USDT");
            syntheticMessage.put("c", update.getPrice());
            syntheticMessage.put("v", update.getVolume());
            return objectMapper.writeValueAsString(syntheticMessage);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Quick refresh - updates predictions without full data broadcast
     */
    public void quickPredictionsRefresh() {
        log.info("🧠 Quick predictions refresh triggered");

        for (String symbol : symbols) {
            try {
                PriceUpdate latestUpdate = getLatestPriceUpdate(symbol);
                if (latestUpdate != null) {
                    AIAnalysisResult analysis = analyzeWithAI(latestUpdate);
                    broadcastUpdate(latestUpdate, analysis);
                }
            } catch (Exception e) {
                log.error("❌ Quick refresh failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    public AIAnalysisResult analyzeWithAI(PriceUpdate update) {
        try {
            double currentPrice = update.getPrice();

            // Get historical data for analysis
            List<CryptoPrice> historicalData = binanceHistoricalService.getHistoricalData(
                    update.getSymbol(), "1d", 90 // Need more data for Fibonacci
            );

            // Detect chart patterns
            List<ChartPattern> patterns = chartPatternService.detectPatterns(
                    update.getSymbol(), historicalData
            );

            patterns = ensureValidChartPatterns(patterns, update.getSymbol());

            // Calculate Fibonacci Time Zones
            List<FibonacciTimeZone> fibZones = fibonacciTimeZoneService.calculateTimeZones(
                    update.getSymbol(), historicalData
            );

            // Get predictions for multiple timeframes
            Map<String, PricePrediction> timeframePredictions =
                    predictionService.predictMultipleTimeframes(update.getSymbol(), currentPrice);

            log.debug("⏰ Calculated {} Fibonacci Time Zones for {}", fibZones.size(), update.getSymbol());

            return new AIAnalysisResult(
                    update.getSymbol(),
                    currentPrice,
                    timeframePredictions,
                    patterns,
                    fibZones, // Include Fibonacci zones
                    System.currentTimeMillis()
            );

        } catch (Exception e) {
            log.error("❌ AI ANALYSIS ERROR for {}: {}", update.getSymbol(), e.getMessage());
            Map<String, PricePrediction> errorPredictions = new HashMap<>();
            errorPredictions.put("1day", new PricePrediction(update.getSymbol(), update.getPrice(), 0.1, "ERROR"));

            return new AIAnalysisResult(
                    update.getSymbol(),
                    update.getPrice(),
                    errorPredictions,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    System.currentTimeMillis()
            );
        }
    }

    private List<ChartPattern> ensureValidChartPatterns(List<ChartPattern> patterns, String symbol) {
        if (patterns == null) return new ArrayList<>();

        return patterns.stream()
                .map(pattern -> {
                    if (pattern.getPatternType() == null) {
                        // Create a safe copy with default patternType
                        return new ChartPattern(
                                "NEUTRAL", // ✅ Default patternType
                                pattern.getPriceLevel(),
                                pattern.getConfidence(),
                                pattern.getDescription() != null ? pattern.getDescription() : "No pattern detected",
                                pattern.getTimestamp()
                        );
                    }
                    return pattern;
                })
                .toList();
    }

    private void broadcastUpdate(PriceUpdate priceUpdate, AIAnalysisResult analysis) {
        try {

            if (analysis.getChartPatterns() != null) {
                analysis.setChartPatterns(ensureValidChartPatterns(analysis.getChartPatterns(), priceUpdate.getSymbol()));
            }

            // Create a combined message
            Map<String, Object> broadcastMessage = new HashMap<>();
            broadcastMessage.put("type", "price_update");
            broadcastMessage.put("symbol", priceUpdate.getSymbol());
            broadcastMessage.put("price", priceUpdate.getPrice());
            broadcastMessage.put("volume", priceUpdate.getVolume());
            broadcastMessage.put("timestamp", priceUpdate.getTimestamp());
            broadcastMessage.put("analysis", analysis);


            String jsonMessage = objectMapper.writeValueAsString(broadcastMessage);
            objectMapper.readTree(jsonMessage); // This will throw if invalid JSON

            // Broadcast to all connected WebSocket clients
            webSocketHandler.broadcast(jsonMessage);

            log.debug("📢 Broadcasted update for {}", priceUpdate.getSymbol());

        } catch (Exception e) {
            log.error("❌ Error broadcasting update for {}: {}", priceUpdate.getSymbol(), e.getMessage());

            sendSafeFallbackMessage(priceUpdate);
        }
    }


    private void sendSafeFallbackMessage(PriceUpdate priceUpdate) {
        try {
            Map<String, Object> safeMessage = new HashMap<>();
            safeMessage.put("type", "price_update");
            safeMessage.put("symbol", priceUpdate.getSymbol());
            safeMessage.put("price", priceUpdate.getPrice());
            safeMessage.put("volume", priceUpdate.getVolume());
            safeMessage.put("timestamp", priceUpdate.getTimestamp());
            safeMessage.put("analysis", Map.of("error", "Analysis temporarily unavailable"));

            webSocketHandler.broadcast(objectMapper.writeValueAsString(safeMessage));
        } catch (Exception e) {
            log.error("❌ Even fallback message failed for {}: {}", priceUpdate.getSymbol(), e.getMessage());
        }
    }

}