package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

@Slf4j
@Service
public class MonitoringService {

    @Autowired
    private CryptoWebSocketHandler webSocketHandler;

    /**
     * Periodic health check every 5 minutes to monitor for leaks
     */
    @Scheduled(fixedRate = 300000)
    public void logSystemHealth() {
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory() / 1024 / 1024;
        long freeMemory = rt.freeMemory() / 1024 / 1024;
        long usedMemory = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;

        int activeSessions = webSocketHandler.getActiveSessionCount();
        
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();

        log.info("📊 --- SYSTEM HEALTH HEARTBEAT ---");
        log.info("🧠 Memory: {}MB / {}MB (Used/Max) | Free: {}MB", usedMemory, maxMemory, freeMemory);
        log.info("🔌 WebSockets: {} Active Sessions", activeSessions);
        log.info("🧵 Threads: {} Active Threads", threadCount);
        
        if (usedMemory > maxMemory * 0.85) {
            log.warn("⚠️ HIGH MEMORY USAGE DETECTED: {}% of max heap utilized", (usedMemory * 100 / maxMemory));
        }
        
        if (activeSessions > 100) {
            log.warn("⚠️ HIGH SESSION COUNT: {} active connections", activeSessions);
        }
        log.info("----------------------------------");
    }
}
