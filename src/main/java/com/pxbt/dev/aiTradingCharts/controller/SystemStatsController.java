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

import java.util.HashMap;
import java.util.Map;

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
        String[] timeframes = { "1h", "4h", "1d", "1W", "1M" };
        for (String tf : timeframes) {
            Long time = aiModelService.getLastTrainingTime(tf);
            if (time != null) {
                modelTimes.put(tf, time);
            }
        }

        SystemStatsResponse response = SystemStatsResponse.builder()
                .trainingStatus(trainingDataService.getTrainingStatus())
                .isTraining(trainingDataService.isTraining())
                .lastTrainingTime(trainingDataService.getLastTrainingTime())
                .trainedModelCount(aiModelService.getTrainedModelCount())
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
}
