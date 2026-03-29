package com.pxbt.dev.aiTradingCharts.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches Binance perpetual futures funding rates (free, no API key for read-only).
 * Cached per symbol for 15 minutes.
 *
 * Positive funding = longs paying shorts = market over-leveraged long = bearish mean-reversion signal.
 * Negative funding = shorts paying longs = market over-leveraged short = bullish mean-reversion signal.
 */
@Slf4j
@Service
public class FundingRateService {

    private static final String FUNDING_URL =
            "https://fapi.binance.com/fapi/v1/fundingRate?symbol=%sUSDT&limit=1";
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L; // 15 minutes

    private final RestTemplate restTemplate = new RestTemplate();

    private record CacheEntry(double rate, long timestamp) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Returns the latest funding rate for the given symbol (e.g. "BTC").
     * Typical values: -0.003 to +0.003 (i.e. -0.3% to +0.3% per 8h).
     * Returns 0.0 if the symbol has no futures market or the fetch fails.
     */
    public double getFundingRate(String symbol) {
        String key = symbol.toUpperCase();
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.timestamp() < CACHE_TTL_MS) {
            return entry.rate();
        }
        try {
            String url = String.format(FUNDING_URL, key);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = restTemplate.getForObject(url, List.class);
            if (body != null && !body.isEmpty()) {
                Object rateObj = body.get(0).get("fundingRate");
                if (rateObj == null) return 0.0;
                double rate = Double.parseDouble(rateObj.toString());
                cache.put(key, new CacheEntry(rate, now));
                log.info("💸 Funding rate [{}]: {}%", key, String.format("%.4f", rate * 100));
                return rate;
            }
        } catch (Exception e) {
            log.debug("Funding rate unavailable for {} (no futures market or API error): {}", key, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Returns a mean-reversion signal in [-1, +1] based on funding rate.
     *  +1 = funding strongly negative (shorts crowded → expect upward reversion)
     *  -1 = funding strongly positive (longs crowded → expect downward reversion)
     *
     * Threshold: rates beyond ±0.001 (0.1% per 8h) are considered elevated.
     */
    public double getMeanReversionSignal(String symbol) {
        double rate = getFundingRate(symbol);
        // Clamp to ±0.003 before scaling
        double clamped = Math.max(-0.003, Math.min(0.003, rate));
        // Invert: positive funding → negative signal (bearish reversion)
        return -(clamped / 0.003);
    }
}
