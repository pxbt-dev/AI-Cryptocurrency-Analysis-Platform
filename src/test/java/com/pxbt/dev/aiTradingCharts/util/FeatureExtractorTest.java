package com.pxbt.dev.aiTradingCharts.util;

import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeatureExtractorTest {

    @Test
    void testExtractFeatures() {
        List<CryptoPrice> prices = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        // Generate 200 dummy price points
        for (int i = 0; i < 200; i++) {
            double price = 100.0 + (i * 0.5); // Upward trend
            prices.add(new CryptoPrice("BTC", price, 1000.0, now + (i * 60000L), price, price, price, price));
        }

        double[] features = FeatureExtractor.extractFeatures(prices);

        assertNotNull(features);
        assertEquals(20, features.length);
        
        // Check that some features are non-zero
        boolean allZero = true;
        for (double f : features) {
            if (Math.abs(f) > 1e-10) {
                allZero = false;
                break;
            }
        }
        assertFalse(allZero, "Features should not be all zero");
        
        // Since it's an upward trend, SMA5 should be > SMA20 etc.
        // features[0] = (current - sma5) / current
        // For lineard increase, current > sma5, so features[0] should be positive
        assertTrue(features[0] >= -1.0 && features[0] <= 1.0, "Feature 0 should be normalized");
        assertTrue(features[3] >= -1.0 && features[3] <= 1.0, "RSI feature should be normalized");
    }
}
