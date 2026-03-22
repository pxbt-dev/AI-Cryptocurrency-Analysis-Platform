package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import com.pxbt.dev.aiTradingCharts.model.*;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@EnableScheduling
public class RealTimeDataService {

    private final Map<String, Deque<PriceUpdate>> priceCache = new ConcurrentHashMap<>();
    private final Map<String, AIAnalysisResult> lastAnalysisCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAnalysisTime = new ConcurrentHashMap<>();
    private static final long ANALYSIS_INTERVAL_MS = 600000; // Only analyze every 10 minutes
    private final List<WebSocketClient> webSocketClients = new ArrayList<>();
    // Single shared scheduler for reconnections - prevents Timer thread leaks
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WS-Reconnect");
                t.setDaemon(true);
                return t;
            });

    // Smart polling and broadcast control
    private final Map<String, Long> lastPriceBroadcastTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAnalysisBroadcastTime = new ConcurrentHashMap<>();
    private static final long PRICE_BROADCAST_INTERVAL_MS = 5000;
    private static final long ANALYSIS_BROADCAST_INTERVAL_MS = 60000;
    
    private long lastDataBroadcastTime = 0;

    @Autowired
    private CryptoWebSocketHandler webSocketHandler;

    @Autowired
    private TradingAnalysisService tradingAnalysisService;

    @Autowired
    private PricePredictionService predictionService;

    @Autowired
    private BinanceHistoricalService binanceHistoricalService;

    @Autowired
    private ChartPatternService chartPatternService;

    @Autowired
    private FibonacciTimeZoneService fibonacciTimeZoneService;

    @Autowired
    private SymbolConfig symbolConfig;

    private ObjectMapper objectMapper = new ObjectMapper();

    private List<String> symbols = new ArrayList<>();
    private Map<String, String> symbolToStream = new HashMap<>();
    private volatile boolean shuttingDown = false;

    @PostConstruct
    public void init() {
        log.info("🚀 INITIALIZING RealTimeDataService - Real-Time Broadcasting Enabled");
        
        // Initialize symbols and streams from config
        this.symbols = symbolConfig.getSymbols();
        this.symbolToStream = new HashMap<>();
        for (String symbol : symbols) {
            symbolToStream.put(symbol, symbol.toLowerCase() + "usdt@ticker");
        }
        
        log.info("📊 Tracking symbols: {}", symbols);
        log.info("📊 Real-time updates: EVERY PRICE CHANGE | Manual refresh: 2 minutes");
        
        // CRITICAL: Connect in background thread so we don't block server startup
        // This fixes the "Failed to start bean 'webServerStartStop'" error
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for server to stabilize
                if (!shuttingDown) {
                    connectToBinanceWebSockets();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "WS-Init").start();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down RealTimeDataService...");
        this.shuttingDown = true;
        reconnectScheduler.shutdownNow();
        for (WebSocketClient client : webSocketClients) {
            try {
                if (client.isOpen()) {
                    client.closeBlocking();
                }
            } catch (Exception e) {
                log.warn("⚠️ Error closing WS client: {}", e.getMessage());
            }
        }
        webSocketClients.clear();
        log.info("✅ RealTimeDataService shutdown complete");
    }

    private void connectToBinanceWebSockets() {
        if (shuttingDown) return;
        log.info("🔗 Connecting to Binance WebSockets (real-time mode)...");

        for (String symbol : symbols) {
            if (shuttingDown) break;
            String streamName = symbolToStream.get(symbol);
            if (streamName != null) {
                connectToSymbolWebSocket(symbol, streamName);
                // Small delay to avoid rate limiting
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void connectToSymbolWebSocket(String symbol, String streamName) {
        if (shuttingDown) return;
        try {
            String binanceUrl = "wss://stream.binance.com:9443/ws/" + streamName;
            log.debug("🔗 Connecting {} -> {}", symbol, binanceUrl);

            WebSocketClient client = new WebSocketClient(new URI(binanceUrl)) {
                @Override
                public void onMessage(String message) {
                    if (shuttingDown) return;
                    // REAL-TIME MODE: Process AND broadcast every update
                    processRealTimeUpdate(message, symbol, true);
                }

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.debug("✅ {} WebSocket CONNECTED (real-time)", symbol);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    webSocketClients.remove(this);
                    if (!shuttingDown) {
                        log.warn("❌ {} WebSocket CLOSED - Reason: {}", symbol, reason);
                        scheduleGentleReconnection(symbol, streamName);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    if (!shuttingDown) {
                        log.debug("💥 {} WebSocket ERROR: {}", symbol, ex.getMessage());
                    }
                }
            };

            client.connect();
            webSocketClients.add(client);

        } catch (Exception e) {
            log.error("❌ Failed to connect {} WebSocket: {}", symbol, e.getMessage());
        }
    }

    private void scheduleGentleReconnection(String symbol, String streamName) {
        if (shuttingDown) return;
        log.info("🔄 Scheduling {} reconnection in 30 seconds...", symbol);
        reconnectScheduler.schedule(() -> {
            if (!shuttingDown) {
                log.info("🔄 Attempting {} reconnection...", symbol);
                connectToSymbolWebSocket(symbol, streamName);
            }
        }, 30, TimeUnit.SECONDS);
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

            // THROTTLING LOGIC: Only re-analyze if enough time has passed
            long now = System.currentTimeMillis();
            AIAnalysisResult analysis;

            if (shouldReanalyze(symbol, now)) {
                log.debug("🧠 Analysis triggered for {} (Interval exceeded)", symbol);
                analysis = analyzeWithAI(priceUpdate);
                lastAnalysisCache.put(symbol, analysis);
                lastAnalysisTime.put(symbol, now);
            } else {
                analysis = lastAnalysisCache.get(symbol);
                if (analysis == null) {
                    analysis = analyzeWithAI(priceUpdate);
                    lastAnalysisCache.put(symbol, analysis);
                    lastAnalysisTime.put(symbol, now);
                } else {
                    // Update the price/timestamp in the cached analysis for the broadcast
                    analysis.setCurrentPrice(price);
                }
            }

            // BROADCAST LOGIC (Throttled)
            if (shouldBroadcastPrice(symbol, now)) {
                boolean includeFullAnalysis = shouldBroadcastFullAnalysis(symbol, now);
                
                if (includeFullAnalysis) {
                    log.debug("📢 Broadcasting FULL update for {} (Analysis included)", symbol);
                    broadcastUpdate(priceUpdate, analysis);
                    lastAnalysisBroadcastTime.put(symbol, now);
                } else {
                    log.debug("📢 Broadcasting LIGHT update for {} (Price only)", symbol);
                    broadcastPriceOnly(priceUpdate);
                }
                lastPriceBroadcastTime.put(symbol, now);
            }

            lastDataBroadcastTime = now;

        } catch (Exception e) {
            log.error("❌ Error processing {} update: {}", symbol, e.getMessage());
        }
    }

    private boolean shouldReanalyze(String symbol, long now) {
        Long lastTime = lastAnalysisTime.get(symbol);
        return lastTime == null || (now - lastTime) >= ANALYSIS_INTERVAL_MS;
    }

    private boolean shouldBroadcastPrice(String symbol, long now) {
        Long lastTime = lastPriceBroadcastTime.get(symbol);
        return lastTime == null || (now - lastTime) >= PRICE_BROADCAST_INTERVAL_MS;
    }

    private boolean shouldBroadcastFullAnalysis(String symbol, long now) {
        Long lastTime = lastAnalysisBroadcastTime.get(symbol);
        return lastTime == null || (now - lastTime) >= ANALYSIS_BROADCAST_INTERVAL_MS;
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
        // Use the unified TradingAnalysisService to get full structural and predictive analysis
        return tradingAnalysisService.analyzeMarketData(update.getSymbol(), update.getPrice());
    }

    private List<ChartPattern> ensureValidChartPatterns(List<ChartPattern> patterns, String symbol) {
        if (patterns == null)
            return new ArrayList<>();

        return patterns.stream()
                .map(pattern -> {
                    if (pattern.getPatternType() == null) {
                        // Create a safe copy with default patternType
                        return new ChartPattern(
                                "NEUTRAL", // ✅ Default patternType
                                pattern.getPriceLevel(),
                                pattern.getConfidence(),
                                pattern.getDescription() != null ? pattern.getDescription() : "No pattern detected",
                                pattern.getTimestamp());
                    }
                    return pattern;
                })
                .toList();
    }

    private void broadcastUpdate(PriceUpdate priceUpdate, AIAnalysisResult analysis) {
        try {
            if (analysis != null && analysis.getChartPatterns() != null) {
                analysis.setChartPatterns(
                        ensureValidChartPatterns(analysis.getChartPatterns(), priceUpdate.getSymbol()));
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
            webSocketHandler.broadcast(jsonMessage);

        } catch (Exception e) {
            log.error("❌ Error broadcasting update for {}: {}", priceUpdate.getSymbol(), e.getMessage());
            sendSafeFallbackMessage(priceUpdate);
        }
    }

    private void broadcastPriceOnly(PriceUpdate priceUpdate) {
        try {
            Map<String, Object> broadcastMessage = new HashMap<>();
            broadcastMessage.put("type", "price_update");
            broadcastMessage.put("symbol", priceUpdate.getSymbol());
            broadcastMessage.put("price", priceUpdate.getPrice());
            broadcastMessage.put("volume", priceUpdate.getVolume());
            broadcastMessage.put("timestamp", priceUpdate.getTimestamp());
            // No analysis field included in lightweight updates
            
            webSocketHandler.broadcast(objectMapper.writeValueAsString(broadcastMessage));
        } catch (Exception e) {
            log.error("❌ Error broadcasting price-only update for {}: {}", priceUpdate.getSymbol(), e.getMessage());
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