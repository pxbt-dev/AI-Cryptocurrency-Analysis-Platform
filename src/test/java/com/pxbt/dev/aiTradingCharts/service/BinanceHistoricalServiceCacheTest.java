package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.Gateway.BinanceGateway;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chart asks for 300 daily candles while training asks for 1825. Both must get what they
 * asked for, whichever request warmed the cache first.
 */
class BinanceHistoricalServiceCacheTest {

    private static final long DAY_MS = 86_400_000L;
    private static final List<CryptoPrice> STORED = candles(2400);

    private BinanceHistoricalService service;

    @BeforeEach
    void setUp() {
        service = new BinanceHistoricalService(mock(BinanceGateway.class));

        SmartCacheService smartCache = mock(SmartCacheService.class);
        when(smartCache.getSmartData(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            int limit = inv.getArgument(2);
            return new ArrayList<>(STORED.subList(Math.max(0, STORED.size() - limit), STORED.size()));
        });
        HistoricalDataFileService fileService = mock(HistoricalDataFileService.class);
        when(fileService.needsUpdate(anyString(), eq("1d"), anyInt())).thenReturn(false);

        ReflectionTestUtils.setField(service, "smartCacheService", smartCache);
        ReflectionTestUtils.setField(service, "fileService", fileService);
    }

    @Test
    void chartRequestAfterTrainingRequestGetsOnlyRecentCandles() {
        assertEquals(1825, service.getHistoricalData("BTC", "1d", 1825).size());

        List<CryptoPrice> chart = service.getHistoricalData("BTC", "1d", 300);

        assertEquals(300, chart.size());
        assertEquals(STORED.get(STORED.size() - 1).getTimestamp(), chart.get(chart.size() - 1).getTimestamp());
    }

    @Test
    void trainingRequestAfterChartRequestGetsFullHistory() {
        assertEquals(300, service.getHistoricalData("BTC", "1d", 300).size());

        assertEquals(1825, service.getHistoricalData("BTC", "1d", 1825).size());
    }

    private static List<CryptoPrice> candles(int n) {
        List<CryptoPrice> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double p = 100 + i;
            list.add(new CryptoPrice("BTC", p, 1.0, i * DAY_MS, p, p, p, p));
        }
        return list;
    }
}
