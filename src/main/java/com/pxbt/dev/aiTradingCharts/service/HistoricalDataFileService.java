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

    public void saveHistoricalData(String symbol, String interval, List<CryptoPrice> data) {
        if (data == null || data.isEmpty()) {
            log.warn("⚠️ No data to save for {} {}", symbol, interval);
            return;
        }

        String filename = getFilename(symbol, interval);

        try {
            Path tempFile = Paths.get(filename + ".tmp");
            Path finalFile = Paths.get(filename);

            Files.createDirectories(tempFile.getParent());
            objectMapper.writeValue(tempFile.toFile(), data);
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);

            log.info("💾 Saved {} candles for {} {}", data.size(), symbol, interval);

        } catch (IOException e) {
            log.error("❌ Failed to save data for {} {}: {}", symbol, interval, e.getMessage());
        }
    }

    public List<CryptoPrice> loadHistoricalData(String symbol, String interval) {
        String filename = getFilename(symbol, interval);

        try {
            File file = new File(filename);
            if (!file.exists()) {
                return new ArrayList<>();
            }

            if (file.length() == 0) {
                return new ArrayList<>();
            }

            return objectMapper.readValue(
                    file,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CryptoPrice.class)
            );

        } catch (IOException e) {
            log.warn("⚠️ Failed to load data for {} {}: {}", symbol, interval, e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean needsUpdate(String symbol, String interval, int maxAgeHours) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);

        if (!file.exists()) {
            return true;
        }

        long lastModified = file.lastModified();
        long currentTime = System.currentTimeMillis();
        long ageInHours = (currentTime - lastModified) / (1000 * 60 * 60);

        return ageInHours > maxAgeHours;
    }

    private String getFilename(String symbol, String interval) {
        String cleanSymbol = symbol.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String cleanInterval = interval.replaceAll("[^a-zA-Z0-9]", "");
        return DATA_DIR + cleanSymbol + "_" + cleanInterval + ".json";
    }
}