package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.config.SymbolConfig;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.model.PricePrediction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class PricePredictionServiceTest {

    @Mock
    private BinanceHistoricalService historicalDataService;

    @Mock
    private SymbolConfig symbolConfig;

    @Mock
    private AIModelService aiModelService;

    @InjectMocks
    private PricePredictionService pricePredictionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testPredictMultipleTimeframes() {
        String symbol = "BTC";
        double currentPrice = 50000.0;
        
        List<CryptoPrice> dummyData = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 250; i++) {
            double price = 45000.0 + (i * 20);
            dummyData.add(new CryptoPrice(symbol, price, 1.0, now - (250 - i) * 86400000L, price, price, price, price));
        }

        // Error in spelling of BinanceHistoricalService in original mock field, but correcting it here
        // Wait, the mock field is 'historicalDataService'
        when(historicalDataService.getHistoricalData(anyString(), anyString(), anyInt())).thenReturn(dummyData);
        
        // Mock AI model response (model not trained)
        java.util.Map<String, Object> aiResult = new java.util.HashMap<>();
        aiResult.put("prediction", 0.0);
        aiResult.put("confidence", 0.1);
        aiResult.put("model", "none");
        when(aiModelService.predictWithConfidence(anyString(), any(), anyString())).thenReturn(aiResult);
        when(symbolConfig.isVolatile(anyString())).thenReturn(false);

        Map<String, PricePrediction> predictions = pricePredictionService.predictMultipleTimeframes(symbol, currentPrice);

        assertNotNull(predictions);
        assertTrue(predictions.containsKey("1day"));
        assertTrue(predictions.containsKey("1week"));
        assertTrue(predictions.containsKey("1month"));

        PricePrediction p1d = predictions.get("1day");
        assertEquals(symbol, p1d.getSymbol());
        assertTrue(p1d.getPredictedPrice() > 0);
        assertNotNull(p1d.getTrend());
    }
}
