package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class HistoricalDataFileService {
    private static final String DATA_DIR;
    static {
        // Check if root volume exists (Railway), otherwise use local relative path
        if (new File("/historical_data").exists() || System.getProperty("os.name").toLowerCase().contains("linux")) {
            DATA_DIR = "/historical_data/";
        } else {
            DATA_DIR = "historical_data/";
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoricalDataFileService() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            log.info("📁 Using data directory: {}", Paths.get(DATA_DIR).toAbsolutePath());
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
        return loadRecentData(symbol, interval, 5000); // Default to 5000 points
    }

    /**
     * Load only the last N items efficiently using a sliding window to save RAM
     */
    public List<CryptoPrice> loadRecentData(String symbol, String interval, int limit) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);

        if (!file.exists() || file.length() == 0)
            return new ArrayList<>();

        // Use a Deque as a sliding window to keep only the 'limit' most recent items in
        // memory
        Deque<CryptoPrice> window = new ArrayDeque<>(limit);

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;

                try {
                    // Check if it's a JSON array or a single object (handles both formats for
                    // compatibility)
                    if (line.startsWith("[")) {
                        List<CryptoPrice> list = objectMapper.readValue(line,
                                new com.fasterxml.jackson.core.type.TypeReference<List<CryptoPrice>>() {
                                });
                        for (CryptoPrice p : list) {
                            if (window.size() >= limit)
                                window.removeFirst();
                            window.addLast(p);
                        }
                    } else {
                        CryptoPrice price = objectMapper.readValue(line, CryptoPrice.class);
                        if (window.size() >= limit)
                            window.removeFirst();
                        window.addLast(price);
                    }
                } catch (Exception e) {
                    // If parsing as JSONL fails, try reading as a full JSON array (legacy support)
                    try {
                        List<CryptoPrice> list = objectMapper.readValue(file,
                                new com.fasterxml.jackson.core.type.TypeReference<List<CryptoPrice>>() {
                                });
                        return list.size() > limit ? list.subList(list.size() - limit, list.size()) : list;
                    } catch (Exception e2) {
                        log.error("Error parsing historical data for {}: {}", symbol, e.getMessage());
                    }
                    break;
                }
            }
        } catch (IOException e) {
            log.warn("⚠️ Failed to stream data for {} {}: {}", symbol, interval, e.getMessage());
        }

        return new ArrayList<>(window);
    }

    public boolean needsUpdate(String symbol, String interval, int maxAgeHours) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);

        if (!file.exists()) {
            return true;
        }

        long lastModified = file.lastModified();
        long ageInHours = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60);
        return ageInHours > maxAgeHours;
    }

    public void pruneFileIfNeeded(String symbol, String interval, int maxPoints) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);
        if (!file.exists() || file.length() < 1024 * 1024)
            return; // Only prune if > 1MB

        log.info("✂️ Pruning file {} to {} points", filename, maxPoints);
        List<CryptoPrice> recent = loadRecentData(symbol, interval, maxPoints);
        saveHistoricalData(symbol, interval, recent);
    }

    private String getFilename(String symbol, String interval) {
        String cleanSymbol = symbol.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String cleanInterval = interval.replaceAll("[^a-zA-Z0-9]", "");
        return DATA_DIR + cleanSymbol + "_" + cleanInterval + ".json";
    }
}