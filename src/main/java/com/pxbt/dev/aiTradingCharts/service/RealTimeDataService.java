package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import com.pxbt.dev.aiTradingCharts.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.util.Timer;
import java.util.TimerTask;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@EnableScheduling
public class RealTimeDataService {
    private static final Logger logger = LoggerFactory.getLogger(RealTimeDataService.class);

    private final Map<String, Deque<PriceUpdate>> priceCache = new ConcurrentHashMap<>();
    private final List<WebSocketClient> webSocketClients = new ArrayList<>();

    // Smart polling control
    private long lastDataBroadcastTime = 0;
    private static final long BROADCAST_INTERVAL = 60 * 60 * 1000; // 1 hour between auto-broadcasts
    private static final long MANUAL_REFRESH_WINDOW = 2 * 60 * 1000; // 2 minutes for manual refresh

    @Autowired
    private CryptoWebSocketHandler webSocketHandler;

    @Autowired
    private PricePredictionService predictionService;

    @Autowired
    private HistoricalDataService historicalDataService;

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
        logger.info("🚀 INITIALIZING RealTimeDataService - Real-Time Broadcasting Enabled");
        logger.info("📊 Real-time updates: EVERY PRICE CHANGE | Manual refresh: 2 minutes");
        connectToBinanceWebSockets();
    }

    private void connectToBinanceWebSockets() {
        logger.info("🔗 Connecting to Binance WebSockets (real-time mode)...");

        for (String symbol : symbols) {
            String streamName = symbolToStream.get(symbol);
            if (streamName != null) {
                connectToSymbolWebSocket(symbol, streamName);
                // Small delay to avoid rate limiting
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }

        logger.info("✅ WebSocket connections established (real-time broadcasting)");
    }

    private void connectToSymbolWebSocket(String symbol, String streamName) {
        try {
            String binanceUrl = "wss://stream.binance.com:9443/ws/" + streamName;
            logger.debug("🔗 Connecting {} -> {}", symbol, binanceUrl);

            WebSocketClient client = new WebSocketClient(new URI(binanceUrl)) {
                @Override
                public void onMessage(String message) {
                    // REAL-TIME MODE: Process AND broadcast every update
                    processRealTimeUpdate(message, symbol, true);
                }

                @Override
                public void onOpen(ServerHandshake handshake) {
                    logger.debug("✅ {} WebSocket CONNECTED (real-time)", symbol);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.warn("❌ {} WebSocket CLOSED - Reason: {}", symbol, reason);
                    // Don't auto-reconnect aggressively
                    scheduleGentleReconnection(symbol, streamName);
                }

                @Override
                public void onError(Exception ex) {
                    logger.debug("💥 {} WebSocket ERROR: {}", symbol, ex.getMessage());
                }
            };

            client.connect();
            webSocketClients.add(client);

        } catch (Exception e) {
            logger.error("❌ Failed to connect {} WebSocket: {}", symbol, e.getMessage());
        }
    }

    private void scheduleGentleReconnection(String symbol, String streamName) {
        logger.info("🔄 Scheduling {} reconnection in 30 seconds...", symbol);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                logger.info("🔄 Attempting {} reconnection...", symbol);
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
                logger.debug("⚠️ Invalid price for {}: {}", symbol, price);
                return;
            }

            PriceUpdate priceUpdate = new PriceUpdate(symbol, price, volume, System.currentTimeMillis());

            // Always update cache (for manual predictions)
            updatePriceCache(symbol, priceUpdate);

            // BROADCAST EVERY UPDATE for real-time charts
            logger.info("📢 Broadcasting {} update - Price: ${}", symbol, price);

            // Send to AI analysis
            AIAnalysisResult analysis = analyzeWithAI(priceUpdate);

            // Broadcast to WebSocket clients
            broadcastUpdate(priceUpdate, analysis);

            lastDataBroadcastTime = System.currentTimeMillis();

        } catch (Exception e) {
            logger.error("❌ Error processing {} update: {}", symbol, e.getMessage());
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
        logger.info("🎯 MANUAL REFRESH triggered - Broadcasting all symbols");

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
                logger.error("❌ Manual refresh failed for {}: {}", symbol, e.getMessage());
            }
        }

        logger.info("✅ Manual refresh completed");
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
        logger.info("🧠 Quick predictions refresh triggered");

        for (String symbol : symbols) {
            try {
                PriceUpdate latestUpdate = getLatestPriceUpdate(symbol);
                if (latestUpdate != null) {
                    AIAnalysisResult analysis = analyzeWithAI(latestUpdate);
                    broadcastUpdate(latestUpdate, analysis);
                }
            } catch (Exception e) {
                logger.error("❌ Quick refresh failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    public AIAnalysisResult analyzeWithAI(PriceUpdate update) {
        try {
            double currentPrice = update.getPrice();

            // Get historical data for analysis
            List<CryptoPrice> historicalData = historicalDataService.getHistoricalData(
                    update.getSymbol(), 90 // Need more data for Fibonacci
            );

            // Detect chart patterns
            List<ChartPattern> patterns = chartPatternService.detectPatterns(
                    update.getSymbol(), historicalData
            );

            // Calculate Fibonacci Time Zones
            List<FibonacciTimeZone> fibZones = fibonacciTimeZoneService.calculateTimeZones(
                    update.getSymbol(), historicalData
            );

            // Get predictions for multiple timeframes
            Map<String, PricePrediction> timeframePredictions =
                    predictionService.predictMultipleTimeframes(update.getSymbol(), currentPrice);

            logger.debug("⏰ Calculated {} Fibonacci Time Zones for {}", fibZones.size(), update.getSymbol());

            return new AIAnalysisResult(
                    update.getSymbol(),
                    currentPrice,
                    timeframePredictions,
                    patterns,
                    fibZones, // Include Fibonacci zones
                    System.currentTimeMillis()
            );

        } catch (Exception e) {
            logger.error("❌ AI ANALYSIS ERROR for {}: {}", update.getSymbol(), e.getMessage());
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

    private void broadcastUpdate(PriceUpdate priceUpdate, AIAnalysisResult analysis) {
        try {
            // Create a combined message
            Map<String, Object> broadcastMessage = new HashMap<>();
            broadcastMessage.put("type", "price_update");
            broadcastMessage.put("symbol", priceUpdate.getSymbol());
            broadcastMessage.put("price", priceUpdate.getPrice());
            broadcastMessage.put("volume", priceUpdate.getVolume());
            broadcastMessage.put("timestamp", priceUpdate.getTimestamp());
            broadcastMessage.put("analysis", analysis);

            String jsonMessage = objectMapper.writeValueAsString(broadcastMessage);

            // Broadcast to all connected WebSocket clients
            webSocketHandler.broadcast(jsonMessage);

            logger.debug("📢 Broadcasted update for {}", priceUpdate.getSymbol());

        } catch (Exception e) {
            logger.error("❌ Error broadcasting update for {}: {}", priceUpdate.getSymbol(), e.getMessage());
        }
    }

    public List<PriceUpdate> getRecentPrices(String symbol) {
        Deque<PriceUpdate> updates = priceCache.get(symbol);
        if (updates == null) return new ArrayList<>();
        return new ArrayList<>(updates);
    }

    public boolean isConnected() {
        return webSocketClients.stream().anyMatch(client -> client.isOpen());
    }

    public Map<String, Object> getPollingStatus() {
        long timeSinceLastBroadcast = System.currentTimeMillis() - lastDataBroadcastTime;
        long nextBroadcastIn = Math.max(0, BROADCAST_INTERVAL - timeSinceLastBroadcast);

        return Map.of(
                "autoRefreshEnabled", true,
                "autoRefreshIntervalMinutes", BROADCAST_INTERVAL / (60 * 1000),
                "lastBroadcastTime", lastDataBroadcastTime,
                "nextBroadcastInMinutes", nextBroadcastIn / (60 * 1000),
                "connectedSymbols", webSocketClients.stream()
                        .filter(client -> client.isOpen())
                        .count(),
                "totalSymbols", symbols.size()
        );
    }
}