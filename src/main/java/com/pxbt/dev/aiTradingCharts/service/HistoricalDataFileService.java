package com.pxbt.dev.aiTradingCharts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.handler.CryptoWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
public class HistoricalDataFileService {
    private static final String DATA_DIR;
    static {
        // Check if root volume exists (Railway), otherwise use local relative path
        File railwayVolume = new File("/historical_data");
        if (railwayVolume.exists() && railwayVolume.isDirectory() && railwayVolume.canWrite()) {
            DATA_DIR = "/historical_data/";
        } else {
            DATA_DIR = "historical_data/";
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy
    private CryptoWebSocketHandler webSocketHandler;

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

            String msg = String.format("Saved %d candles for %s %s", data.size(), symbol, interval);
            log.info("💾 " + msg);
            webSocketHandler.broadcastEvent("DISK", msg);

        } catch (IOException e) {
            log.error("❌ Failed to save data for {} {}: {}", symbol, interval, e.getMessage());
        }
    }

    public List<CryptoPrice> loadHistoricalData(String symbol, String interval) {
        return loadRecentData(symbol, interval, 4000); // Reduced from 10000 (enough for 3000-pt training)
    }

    /**
     * Load only the last N items efficiently using streaming to save RAM
     */
    public List<CryptoPrice> loadRecentData(String symbol, String interval, int limit) {
        String filename = getFilename(symbol, interval);
        File file = new File(filename);

        if (!file.exists() || file.length() == 0)
            return new ArrayList<>();

        // Use a Deque as a sliding window to keep only the 'limit' most recent items in
        // memory
        Deque<CryptoPrice> window = new ArrayDeque<>(limit);

        try (com.fasterxml.jackson.core.JsonParser parser = objectMapper.getFactory().createParser(file)) {
            // Check if it's the start of an array
            if (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                    CryptoPrice price = parser.readValueAs(CryptoPrice.class);
                    if (window.size() >= limit) {
                        window.removeFirst();
                    }
                    window.addLast(price);
                }
            } else {
                // Try line-by-line if not a standard JSON array (for JSONL compatibility)
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty())
                            continue;
                        CryptoPrice price = objectMapper.readValue(line, CryptoPrice.class);
                        if (window.size() >= limit)
                            window.removeFirst();
                        window.addLast(price);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("⚠️ Failed to stream data for {} {}: {}", symbol, interval, e.getMessage());
            // Fallback to legacy full load if streaming fails and file is small
            if (file.length() < 2 * 1024 * 1024) {
                try {
                    List<CryptoPrice> list = objectMapper.readValue(file,
                            new com.fasterxml.jackson.core.type.TypeReference<List<CryptoPrice>>() {
                            });
                    return list.size() > limit ? list.subList(list.size() - limit, list.size()) : list;
                } catch (Exception e2) {
                }
            }
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