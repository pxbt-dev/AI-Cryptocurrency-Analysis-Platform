package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.dto.SystemStatsResponse;
import com.pxbt.dev.aiTradingCharts.service.AIModelService;
import com.pxbt.dev.aiTradingCharts.service.TrainingDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemStatsController {

    @Autowired
    private TrainingDataService trainingDataService;

    @Autowired
    private AIModelService aiModelService;

    @GetMapping("/stats")
    public ResponseEntity<SystemStatsResponse> getStats() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> memory = new HashMap<>();
        memory.put("maxMemoryMB", rt.maxMemory() / 1024 / 1024);
        memory.put("totalMemoryMB", rt.totalMemory() / 1024 / 1024);
        memory.put("freeMemoryMB", rt.freeMemory() / 1024 / 1024);
        memory.put("usedMemoryMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);

        Map<String, Long> modelTimes = new HashMap<>();
        String[] timeframes = { "1d", "1W", "1M" };
        for (String tf : timeframes) {
            Long time = aiModelService.getLastTrainingTime(tf);
            if (time != null) {
                modelTimes.put(tf, time);
            }
        }

        long uptimeMillis = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        int threadCount = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();

        long directMemory = getDirectMemoryUsage();
        long rss = getProcessRss();

        SystemStatsResponse response = SystemStatsResponse.builder()
                .trainingStatus(trainingDataService.getTrainingStatus())
                .isTraining(trainingDataService.isTraining())
                .lastTrainingTime(trainingDataService.getLastTrainingTime())
                .trainedModelCount(aiModelService.getTrainedModelCount())
                .uptime(uptimeMillis)
                .threadCount(threadCount)
                .directMemoryMB(directMemory / 1024 / 1024)
                .rssMemoryMB(rss / 1024 / 1024)
                .trainingSessions(aiModelService.getTrainedModelCount() > 0 ? 1 : 0)
                .memoryUsage(memory)
                .modelLastTrained(modelTimes)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/train")
    public ResponseEntity<Map<String, String>> triggerTraining() {
        if (trainingDataService.isTraining()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Training already in progress"));
        }

        log.info("🚀 Manual training trigger received");
        trainingDataService.forceRetrain();

        return ResponseEntity.ok(Map.of("message", "Training triggered successfully"));
    }

    private long getDirectMemoryUsage() {
        try {
            List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
            for (MemoryPoolMXBean pool : pools) {
                if (pool.getName().contains("Direct") || pool.getName().contains("Compressed Class Space")) {
                    return pool.getUsage().getUsed();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get direct memory usage: {}", e.getMessage());
        }
        return 0;
    }

    private long getProcessRss() {
        try {
            // Read from /proc/self/status for accurate RSS on Linux
            Path path = Paths.get("/proc/self/status");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    if (line.startsWith("VmRSS:")) {
                        String value = line.split(":")[1].trim().split("\\s+")[0];
                        return Long.parseLong(value) * 1024; // Convert KB to Bytes
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read process RSS: {}", e.getMessage());
        }
        return 0;
    }
}
