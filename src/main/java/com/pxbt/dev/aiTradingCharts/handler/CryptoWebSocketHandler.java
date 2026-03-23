package com.pxbt.dev.aiTradingCharts.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.AIAnalysisResult;
import com.pxbt.dev.aiTradingCharts.model.PriceUpdate;
import com.pxbt.dev.aiTradingCharts.service.MarketDataService;
import com.pxbt.dev.aiTradingCharts.service.TradingAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Slf4j
@Component
public class CryptoWebSocketHandler implements WebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    public int getActiveSessionCount() {
        return sessions.size();
    }

    @Autowired
    @Lazy
    private TradingAnalysisService analysisService;
    
    @Autowired
    @Lazy
    private MarketDataService marketDataService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Enforce send timeout (5s) and outbound buffer limit (512KB) per session
        // This prevents native memory leaks when clients are slow or suspended
        ConcurrentWebSocketSessionDecorator safeSession = new ConcurrentWebSocketSessionDecorator(
                session, 5000, 512 * 1024);
        sessions.add(safeSession);
        log.info("🔌 NEW CLIENT CONNECTED - Session: {}, Remote: {}, Total: {}",
                session.getId(), session.getRemoteAddress(), sessions.size());

        // Send welcome message to confirm connection
        try {
            String welcomeMsg = "{\"type\": \"welcome\", \"message\": \"Connected to AI Trading Data\", \"timestamp\": "
                    + System.currentTimeMillis() + "}";
            synchronized (session) {
                session.sendMessage(new TextMessage(welcomeMsg));
            }
            log.debug("✅ Welcome message sent to client: {}", session.getId());
        } catch (Exception e) {
            log.error("❌ Failed to send welcome message to client {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("🔌 CLIENT DISCONNECTED - Session: {}, Reason: {}, Code: {}, Remaining: {}",
                session.getId(), status.getReason(), status.getCode(), sessions.size());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        String payload = message.getPayload().toString();

        // Handle analysis requests
        if (payload.startsWith("analyze:")) {
            handleAnalysisRequest(session, payload);
        }
        // Store real market data for analysis
        else {
            try {
                PriceUpdate priceUpdate = mapper.readValue(payload, PriceUpdate.class);

                // STORE REAL MARKET DATA FOR ANALYSIS
                marketDataService.addPriceUpdate(priceUpdate);
                log.debug("💾 Stored price data: {} at ${}", priceUpdate.getSymbol(), priceUpdate.getPrice());

            } catch (Exception e) {
                // Not a PriceUpdate, could be welcome/pong etc.
                log.trace("Not a price update message: {}", payload);
            }
        }
    }

    private void handleAnalysisRequest(WebSocketSession session, String payload) {
        try {
            // Parse: "analyze:BTC,50000"
            String[] parts = payload.substring(8).split(",");
            String symbol = parts[0];
            double price = Double.parseDouble(parts[1]);

            log.info("🔍 ANALYSIS REQUESTED - Symbol: {}, Price: ${}", symbol, price);

            // CALL THE ANALYSIS SERVICE
            AIAnalysisResult result = analysisService.analyzeMarketData(symbol, price);

            // Convert to JSON and send back to client
            String analysisJson = mapper.writeValueAsString(result);
            synchronized (session) {
                session.sendMessage(new TextMessage("analysis:" + analysisJson));
            }

            log.info("✅ ANALYSIS COMPLETE - Symbol: {}, Confidence: {}%, Signal: {}",
                    symbol, result.getConfidence(), result.getTradingSignal());

        } catch (Exception e) {
            log.error("❌ ANALYSIS FAILED: {}", e.getMessage());
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage("error:Analysis failed - " + e.getMessage()));
                }
            } catch (IOException ioException) {
                log.error("❌ Failed to send error message");
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("💥 TRANSPORT ERROR - Session: {}, Error: {}",
                session.getId(), exception.getMessage(), exception);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * Broadcast message to all connected WebSocket clients
     */
    /**
     * Broadcast message to all connected WebSocket clients
     */
    /**
     * Broadcast a system event/log to all clients
     */
    public void broadcastEvent(String category, String message) {
        String eventJson = String.format("{\"type\": \"event\", \"category\": \"%s\", \"message\": \"%s\", \"timestamp\": %d}",
                category, message, System.currentTimeMillis());
        broadcast(eventJson);
    }

    public void broadcast(String message) {
        if (sessions.isEmpty()) {
            log.debug("📢 No clients connected to broadcast message");
            return;
        }

        log.debug("📢 BROADCASTING to {} clients - Message size: {} bytes",
                sessions.size(), message.length());

        int successCount = 0;
        int errorCount = 0;
        List<WebSocketSession> closedSessions = new ArrayList<>();

        // 1. First iteration to send messages and identify closed sessions
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(message));
                    }
                    successCount++;
                } else {
                    closedSessions.add(session);
                }
            } catch (IOException e) {
                log.warn("❌ Failed to send message to session {}: {}", session.getId(), e.getMessage());
                closedSessions.add(session);
            } catch (Exception e) {
                log.error("❌ Unexpected error broadcasting to session {}: {}", session.getId(), e.getMessage());
                closedSessions.add(session);
            }
        }

        // 2. Perform safe cleanup of closed sessions outside the broadcast loop
        if (!closedSessions.isEmpty()) {
            sessions.removeAll(closedSessions);
            log.info("🧹 Cleaned up {} closed or failed sessions (Remaining: {})",
                    closedSessions.size(), sessions.size());
        }

        log.debug("📢 BROADCAST RESULTS - Success: {}, Errors: {}, Total Clients: {}",
                successCount, errorCount, sessions.size());
    }
}