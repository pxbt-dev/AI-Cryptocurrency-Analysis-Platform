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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PricePredictionServiceTest {

    @Mock
    private BinanceHistoricalService historicalDataService;

    @Mock
    private SymbolConfig symbolConfig;

    @Mock
    private AIModelService aiModelService;

    @Mock
    private AccuracyPersistenceService accuracyPersistenceService;

    @Mock
    private TradingMetricsService metricsService;

    @Mock
    private FearGreedService fearGreedService;

    @Mock
    private FundingRateService fundingRateService;

    @InjectMocks
    private PricePredictionService pricePredictionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Neutral external signals, so no mean-reversion dampening applies
        when(fearGreedService.getMeanReversionSignal()).thenReturn(0.0);
        when(fearGreedService.getScore()).thenReturn(50.0);
        when(fearGreedService.getClassification()).thenReturn("GREED");
        when(fundingRateService.getMeanReversionSignal(anyString())).thenReturn(0.0);
        when(fundingRateService.getFundingRate(anyString())).thenReturn(0.0);
        when(symbolConfig.isVolatile(anyString())).thenReturn(false);
    }

    @Test
    void untrainedModelFallsBackToTechnicalTrendForEveryTimeframe() {
        String symbol = "BTC";
        double currentPrice = 50000.0;

        // Steady uptrend: 45,000 -> 49,980 over 250 days
        List<CryptoPrice> dummyData = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 250; i++) {
            double price = 45000.0 + (i * 20);
            dummyData.add(new CryptoPrice(symbol, price, 1.0, now - (250 - i) * 86400000L, price, price, price, price));
        }
        when(historicalDataService.getHistoricalData(anyString(), anyString(), anyInt())).thenReturn(dummyData);

        // AI model not trained yet
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("prediction", 0.0);
        aiResult.put("confidence", 0.1);
        aiResult.put("model", "none");
        when(aiModelService.predictWithConfidence(anyString(), any(), anyString())).thenReturn(aiResult);

        Map<String, PricePrediction> predictions = pricePredictionService.predictMultipleTimeframes(symbol, currentPrice);

        assertEquals(List.of("1day", "1week", "1month"), new ArrayList<>(predictions.keySet()));

        for (Map.Entry<String, PricePrediction> entry : predictions.entrySet()) {
            PricePrediction p = entry.getValue();
            String tf = entry.getKey();

            // The error fallback leaves modelName null, so this fails if any exception was swallowed
            assertEquals("TECHNICAL_TREND", p.getModelName(), tf + " should use the technical-trend model, not the error fallback");
            assertEquals(symbol, p.getSymbol());
            assertTrue(p.getPredictedPrice() > currentPrice, tf + " uptrend should predict a higher price");
            // No backtested evidence for the technical formula, so confidence stays at 0
            assertEquals(0.0, p.getConfidence(), tf + " confidence should be 0 without backtest evidence");
            assertNotNull(p.getTrend());
            assertEquals("GREED", p.getFearGreedLabel());
        }

        verify(fearGreedService, atLeastOnce()).getMeanReversionSignal();
    }
}
