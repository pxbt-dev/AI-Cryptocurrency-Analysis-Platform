package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Service
public class HistoricalDataFileService {
    private static final String DATA_DIR = "historical_data/";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoricalDataFileService() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            log.info("📁 Created data directory: {}", DATA_DIR);
        } catch (IOException e) {
            log.error("❌ Failed to create data directory: {}", e.getMessage());
        }
    }

    /**
     * Save/Append data to file in JSONL format (one JSON object per line)
     */
    public void saveHistoricalData(String symbol, String interval, List<CryptoPrice> data) {
        if (data == null || data.isEmpty())
            return;

        String filename = getFilename(symbol, interval);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
            for (CryptoPrice price : data) {
                writer.write(objectMapper.writeValueAsString(price));
                writer.newLine();
            }
            log.info("💾 Appended {} candles to {} {}", data.size(), symbol, interval);
        } catch (IOException e) {
            log.error("❌ Failed to save data for {} {}: {}", symbol, interval, e.getMessage());
        }
    }

    /**
     * Load all historical data from JSONL file
     */
    public List<CryptoPrice> loadHistoricalData(String symbol, String interval) {
        String filename = getFilename(symbol, interval);
        List<CryptoPrice> prices = new ArrayList<>();
        File file = new File(filename);

        if (!file.exists() || file.length() == 0)
            return prices;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                prices.add(objectMapper.readValue(line, CryptoPrice.class));
            }
        } catch (IOException e) {
            log.warn("⚠️ Failed to load data for {} {}: {}", symbol, interval, e.getMessage());
        }
        return prices;
    }

    /**
     * Load only the last N items (efficiently if possible, but reading all for now)
     */
    public List<CryptoPrice> loadRecentData(String symbol, String interval, int limit) {
        List<CryptoPrice> all = loadHistoricalData(symbol, timeframeFix(interval)); // Temp fix for naming consistency
        if (all.size() <= limit)
            return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    private String timeframeFix(String timeframe) {
        return timeframe; // Placeholder
    }

    public boolean needsUpdate(String symbol, String interval, int maxAgeHours) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);

        if (!file.exists())
            return true;

        long lastModified = file.lastModified();
        long ageInHours = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60);
        return ageInHours > maxAgeHours;
    }

    private String getFilename(String symbol, String interval) {
        String cleanSymbol = symbol.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String cleanInterval = interval.replaceAll("[^a-zA-Z0-9]", "");
        return DATA_DIR + cleanSymbol + "_" + cleanInterval + ".jsonl"; // Changed extension
    }
}