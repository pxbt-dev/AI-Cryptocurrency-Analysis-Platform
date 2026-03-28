package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.BacktestSummary;
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

    public synchronized void recordBatch(List<AccuracyRecord> records) {
        auditLogs.addAll(records);
        saveToDisk();
    }

    public List<AccuracyRecord> getRecords(String symbol, String timeframe) {
        return auditLogs.stream()
                .filter(r -> r.getSymbol().equalsIgnoreCase(symbol) && r.getTimeframe().equalsIgnoreCase(timeframe))
                .collect(Collectors.toList());
    }

    public BacktestSummary getSummary(String symbol, String timeframe) {
        List<AccuracyRecord> records = getRecords(symbol, timeframe);
        List<AccuracyRecord> evaluated = records.stream().filter(AccuracyRecord::isEvaluated).collect(Collectors.toList());
        List<AccuracyRecord> pending = records.stream().filter(r -> !r.isEvaluated()).collect(Collectors.toList());

        if (evaluated.isEmpty()) {
            return BacktestSummary.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .totalEvaluated(0)
                    .totalPending(pending.size())
                    .winRate(0)
                    .avgError(0)
                    .build();
        }

        long wins = evaluated.stream().filter(AccuracyRecord::isDirectionMatch).count();
        double winRate = (wins * 100.0) / evaluated.size();

        double totalError = evaluated.stream()
                .mapToDouble(r -> Math.abs(r.getPredictedPrice() - r.getActualPrice()) / r.getActualPrice())
                .sum();
        double avgError = (totalError * 100.0) / evaluated.size();

        double latestActual = evaluated.get(evaluated.size() - 1).getActualPrice();

        return BacktestSummary.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .totalEvaluated(evaluated.size())
                .totalPending(pending.size())
                .winRate(winRate)
                .avgError(avgError)
                .latestActualPrice(latestActual)
                .build();
    }

    public List<AccuracyRecord> getAllRecords() {
        return new ArrayList<>(auditLogs);
    }

    public synchronized void clearBacktestRecords(String symbol, String timeframe) {
        auditLogs.removeIf(r ->
            r.getSymbol().equalsIgnoreCase(symbol) &&
            r.getTimeframe().equalsIgnoreCase(timeframe) &&
            r.isEvaluated());
        saveToDisk();
        log.info("🗑️ Cleared old backtest records for {} {}", symbol, timeframe);
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
