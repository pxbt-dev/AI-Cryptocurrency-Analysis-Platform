package com.pxbt.dev.aiTradingCharts.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class SystemStatsResponse {
    private String trainingStatus;
    private boolean isTraining;
    private long lastTrainingTime;
    private int trainedModelCount;
    private long uptime;
    private int trainingSessions;
    private int threadCount;
    private long directMemoryMB;
    private long rssMemoryMB;
    private Map<String, Object> memoryUsage;
    private Map<String, Long> modelLastTrained;
}
