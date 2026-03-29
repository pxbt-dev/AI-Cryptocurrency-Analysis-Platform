package com.pxbt.dev.aiTradingCharts.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches the Crypto Fear & Greed Index from alternative.me (free, no API key).
 * Cached for 1 hour — the index only updates once per day.
 *
 * Score 0–100:  0–24 = Extreme Fear, 25–49 = Fear,
 *               50–74 = Greed,        75–100 = Extreme Greed
 */
@Slf4j
@Service
public class FearGreedService {

    private static final String URL = "https://api.alternative.me/fng/?limit=1";
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicReference<Double> cached = new AtomicReference<>(50.0); // neutral default
    private final AtomicLong lastFetch = new AtomicLong(0);

    /**
     * Returns the raw Fear & Greed score (0–100).
     * Uses cached value if fresh; otherwise fetches from API.
     */
    public double getScore() {
        long now = System.currentTimeMillis();
        if (now - lastFetch.get() < CACHE_TTL_MS) {
            return cached.get();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(URL, Map.class);
            if (body != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                if (data != null && !data.isEmpty()) {
                    double score = Double.parseDouble(data.get(0).get("value").toString());
                    cached.set(score);
                    lastFetch.set(now);
                    log.info("😱 Fear & Greed updated: {} ({})", score, data.get(0).get("value_classification"));
                    return score;
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Fear & Greed fetch failed, using cached {}: {}", cached.get(), e.getMessage());
        }
        return cached.get();
    }

    /**
     * Returns a mean-reversion signal in [-1, +1].
     *  -1 = extreme greed  (expect downward reversion)
     *  +1 = extreme fear   (expect upward reversion)
     *   0 = neutral
     * Only activates outside the 20–80 neutral band.
     */
    public double getMeanReversionSignal() {
        double score = getScore();
        if (score < 20) {
            // Extreme fear — historically a buy signal
            return (20 - score) / 20.0;   // 0 → +1 as score goes 20 → 0
        } else if (score > 80) {
            // Extreme greed — historically a sell signal
            return -((score - 80) / 20.0); // 0 → -1 as score goes 80 → 100
        }
        return 0.0;
    }

    public String getClassification() {
        double score = getScore();
        if (score < 20) return "EXTREME_FEAR";
        if (score < 50) return "FEAR";
        if (score < 75) return "GREED";
        return "EXTREME_GREED";
    }
}
