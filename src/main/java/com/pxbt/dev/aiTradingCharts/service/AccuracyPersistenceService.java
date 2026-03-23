package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccuracyPersistenceService {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String DATA_DIR = "backtest_data/";
    private final String FILE_NAME = DATA_DIR + "accuracy_audit.json";
    
    private final List<AccuracyRecord> auditLogs = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
        loadFromDisk();
    }

    public synchronized void recordPrediction(AccuracyRecord record) {
        auditLogs.add(record);
        saveToDisk();
    }

    public List<AccuracyRecord> getRecords(String symbol, String timeframe) {
        return auditLogs.stream()
                .filter(r -> r.getSymbol().equalsIgnoreCase(symbol) && r.getTimeframe().equalsIgnoreCase(timeframe))
                .collect(Collectors.toList());
    }

    public List<AccuracyRecord> getAllRecords() {
        return new ArrayList<>(auditLogs);
    }

    private void saveToDisk() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE_NAME), auditLogs);
        } catch (IOException e) {
            log.error("❌ Failed to save accuracy audit: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        File file = new File(FILE_NAME);
        if (file.exists()) {
            try {
                List<AccuracyRecord> loaded = objectMapper.readValue(file, new TypeReference<List<AccuracyRecord>>() {});
                auditLogs.clear();
                auditLogs.addAll(loaded);
                log.info("✅ Loaded {} historical accuracy records", auditLogs.size());
            } catch (IOException e) {
                log.error("❌ Failed to load accuracy audit: {}", e.getMessage());
            }
        }
    }
}
