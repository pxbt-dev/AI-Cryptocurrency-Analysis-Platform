package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Service to simulate a trading engine terminal.
 * Generates logs for TRADE FILL, SPREAD CAPTURED, TOXIC FLOW, and INVENTORY REBALANCE.
 */
@Slf4j
// @Service
public class SimulationEngine {

    @Autowired
    private CryptoWebSocketHandler webSocketHandler;

    @jakarta.annotation.PostConstruct
    public void init() {
        broadcastInitializationLog();
    }

    private final Random random = new Random();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String className = "com.bot.spreadengine.service.SimulationEngine";
    private final String threadName = "[scheduling-1]";
    private final int pid = 12345;

    @Scheduled(fixedDelay = 5000)
    public void simulateTradeFill() {
        if (random.nextInt(10) > 3) { // 70% chance to simulate a trade
            String symbol = "BTC_5M";
            String side = random.nextBoolean() ? "BID" : "ASK";
            int qty = 10 + random.nextInt(90);
            
            String logContent = String.format("TRADE FILL: %s %s qty %d", side, symbol, qty);
            broadcastLog("INFO", logContent);
        }
    }

    @Scheduled(fixedDelay = 8000)
    public void simulateSpreadCaptured() {
        if (random.nextInt(10) > 4) {
            double spread = 0.01 + (random.nextDouble() * 0.1);
            String logContent = String.format("SPREAD CAPTURED: $%.3f", spread);
            broadcastLog("INFO", logContent);
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void simulateToxicFlow() {
        if (random.nextInt(10) > 7) { // 20% chance
            double vpin = 0.3 + (random.nextDouble() * 0.4);
            String logContent = String.format("TOXIC FLOW DETECTED: VPIN %.2f", vpin);
            broadcastLog("WARN", logContent);
        }
    }

    @Scheduled(fixedDelay = 20000)
    public void simulateInventoryRebalance() {
        if (random.nextInt(10) > 6) { // 30% chance
            int skew = -20 + random.nextInt(41);
            String logContent = String.format("INVENTORY REBALANCE: skew %d", skew);
            broadcastLog("DEBUG", logContent);
        }
    }

    public void broadcastLog(String level, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        // Format: 2026-03-22 19:35:50.001  INFO 12345 --- [scheduling-1] com.bot.spreadengine.service.SimulationEngine  : MESSAGE
        String fullLog = String.format("%s  %-5s %d --- %s %-45s : %s", 
                timestamp, level, pid, threadName, className, message);
        
        // We wrap it in a JSON format that the frontend can identify as a system log
        try {
            String jsonLog = String.format("{\"type\":\"engine_log\", \"level\":\"%s\", \"message\":\"%s\"}", 
                    level.toLowerCase(), fullLog.replace("\"", "\\\""));
            webSocketHandler.broadcast(jsonLog);
        } catch (Exception e) {
            log.error("Failed to broadcast simulation log: {}", e.getMessage());
        }
    }
    
    public void broadcastInitializationLog() {
        broadcastLog("INFO", "Engine Terminal started successfully.");
    }
}
