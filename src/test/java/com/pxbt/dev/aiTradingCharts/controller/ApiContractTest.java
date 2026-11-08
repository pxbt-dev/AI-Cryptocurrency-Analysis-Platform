package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.BacktestSummary;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.service.AccuracyPersistenceService;
import com.pxbt.dev.aiTradingCharts.service.BinanceHistoricalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the public JSON API and the dashboard page.
 *
 * Controllers, analysis, prediction and JSON serialisation run for real. Only the two
 * data sources are mocked: Binance history ({@link BinanceHistoricalService}) and the
 * accuracy audit file ({@link AccuracyPersistenceService}). Assertions focus on the
 * fields chart.html actually reads, so a change that would break the dashboard fails here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long START = 1_700_000_000_000L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BinanceHistoricalService historicalService;

    @MockBean
    private AccuracyPersistenceService accuracyService;

    /** Steady daily uptrend with distinct OHLC values, oldest first. */
    private static List<CryptoPrice> candles(String symbol, int count) {
        List<CryptoPrice> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = 40_000 + i * 20;
            list.add(new CryptoPrice(symbol, close, 10 + i, START + i * DAY_MS,
                    close - 5, close + 15, close - 15, close));
        }
        return list;
    }

    private static AccuracyRecord record(String symbol, boolean evaluated, boolean match) {
        return AccuracyRecord.builder()
                .symbol(symbol).timeframe("1d")
                .predictionTime(START).targetTime(START + DAY_MS)
                .currentPrice(100).predictedPrice(105).actualPrice(103)
                .predictedChange(0.05).actualChange(0.03)
                .isDirectionMatch(match).isEvaluated(evaluated)
                .modelName("TEST")
                .build();
    }

    @Nested
    class HistoricalData {

        @Test
        void returnsOhlcCandlesInTheShapeTheChartReads() throws Exception {
            when(historicalService.getHistoricalDataReactive("BTC", "1d", 3))
                    .thenReturn(Mono.just(candles("BTC", 3)));

            mvc.perform(get("/api/historical/BTC").param("timeframe", "1d").param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].timestamp").value(START))
                    .andExpect(jsonPath("$[0].open").value(39_995.0))
                    .andExpect(jsonPath("$[0].high").value(40_015.0))
                    .andExpect(jsonPath("$[0].low").value(39_985.0))
                    .andExpect(jsonPath("$[0].close").value(40_000.0))
                    .andExpect(jsonPath("$[0].volume").value(10.0))
                    .andExpect(jsonPath("$[2].timestamp").value(START + 2 * DAY_MS));
        }

        @Test
        void defaultsToDailyCandlesAndLimit100() throws Exception {
            when(historicalService.getHistoricalDataReactive(anyString(), anyString(), anyInt()))
                    .thenReturn(Mono.just(candles("SOL", 1)));

            mvc.perform(get("/api/historical/SOL")).andExpect(status().isOk());

            verify(historicalService).getHistoricalDataReactive("SOL", "1d", 100);
        }

        @Test
        void returns404WhenThereIsNoData() throws Exception {
            when(historicalService.getHistoricalDataReactive(anyString(), anyString(), anyInt()))
                    .thenReturn(Mono.just(List.of()));

            mvc.perform(get("/api/historical/NOPE")).andExpect(status().isNotFound());
        }

        @Test
        void returns500WhenTheDataSourceFails() throws Exception {
            when(historicalService.getHistoricalDataReactive(anyString(), anyString(), anyInt()))
                    .thenReturn(Mono.error(new IllegalStateException("boom")));

            mvc.perform(get("/api/historical/BTC")).andExpect(status().isInternalServerError());
        }
    }

    @Nested
    class ChartData {

        @BeforeEach
        void stubPrices() {
            List<CryptoPrice> data = candles("BTC", 250);
            when(historicalService.getHistoricalDataReactive(eq("BTC"), anyString(), anyInt()))
                    .thenReturn(Mono.just(data));
            // Used by the prediction service for each timeframe
            when(historicalService.getHistoricalData(eq("BTC"), anyString(), anyInt())).thenReturn(data);
        }

        @Test
        void returnsPricesAndAnalysisWithAllThreeTimeframePredictions() throws Exception {
            mvc.perform(get("/api/chart/data").param("symbol", "BTC").param("timeframe", "1d"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.timeframe").value("1d"))
                    .andExpect(jsonPath("$.prices", hasSize(250)))
                    .andExpect(jsonPath("$.analysis.symbol").value("BTC"))
                    .andExpect(jsonPath("$.analysis.timeframe").value("1d"))
                    .andExpect(jsonPath("$.analysis.currentPrice").value(44_980.0))
                    .andExpect(jsonPath("$.analysis.tradingSignal", not(emptyOrNullString())))
                    .andExpect(jsonPath("$.analysis.timeframePredictions.1day.predictedPrice", greaterThan(0.0)))
                    .andExpect(jsonPath("$.analysis.timeframePredictions.1week.predictedPrice", greaterThan(0.0)))
                    .andExpect(jsonPath("$.analysis.timeframePredictions.1month.predictedPrice", greaterThan(0.0)))
                    // The error fallback leaves modelName null; any real model (AI or technical) sets it
                    .andExpect(jsonPath("$.analysis.timeframePredictions.1day.modelName", not(emptyOrNullString())));

            verify(historicalService).getHistoricalDataReactive("BTC", "1d", 100);
        }

        @Test
        void emptyAnalysisCarriesRequestedSymbolAndTimeframeWhenThereIsNoData() throws Exception {
            when(historicalService.getHistoricalDataReactive("SOL", "1d", 100))
                    .thenReturn(Mono.just(List.of()));

            mvc.perform(get("/api/chart/data").param("symbol", "SOL").param("timeframe", "1d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysis.symbol").value("SOL"))
                    .andExpect(jsonPath("$.analysis.timeframe").value("1d"));
        }

        @Test
        void requiresSymbolAndTimeframe() throws Exception {
            mvc.perform(get("/api/chart/data").param("symbol", "BTC")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/chart/data").param("timeframe", "1d")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    class BacktestAudit {

        @Test
        void returnsRecordsWithTheFlagsTheDashboardFiltersOn() throws Exception {
            when(accuracyService.getRecords("BTC", "1d"))
                    .thenReturn(List.of(record("BTC", true, true), record("BTC", false, false)));

            mvc.perform(get("/api/backtest/audit").param("symbol", "BTC").param("timeframe", "1d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].isEvaluated").value(true))
                    .andExpect(jsonPath("$[0].isDirectionMatch").value(true))
                    .andExpect(jsonPath("$[0].predictionTime").value(START))
                    .andExpect(jsonPath("$[0].predictedChange").value(0.05))
                    .andExpect(jsonPath("$[0].predictedPrice").value(105.0))
                    .andExpect(jsonPath("$[0].actualPrice").value(103.0))
                    .andExpect(jsonPath("$[1].isEvaluated").value(false));
        }

        @Test
        void fallsBackToUsdtSymbolWhenShortSymbolHasNoRecords() throws Exception {
            when(accuracyService.getRecords("BTC", "1d")).thenReturn(List.of());
            when(accuracyService.getRecords("BTCUSDT", "1d")).thenReturn(List.of(record("BTCUSDT", true, false)));

            mvc.perform(get("/api/backtest/audit").param("symbol", "BTC").param("timeframe", "1d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"));
        }

        @Test
        void returnsAllRecordsWithoutFilters() throws Exception {
            when(accuracyService.getAllRecords())
                    .thenReturn(List.of(record("BTC", true, true), record("SOL", true, false), record("WIF", false, false)));

            mvc.perform(get("/api/backtest/audit"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }
    }

    @Nested
    class BacktestSummaryEndpoint {

        @Test
        void fallsBackToUsdtSymbolAndDefaultsToDaily() throws Exception {
            when(accuracyService.getSummary("BTC", "1d"))
                    .thenReturn(BacktestSummary.builder().symbol("BTC").timeframe("1d").totalEvaluated(0).build());
            when(accuracyService.getSummary("BTCUSDT", "1d"))
                    .thenReturn(BacktestSummary.builder().symbol("BTCUSDT").timeframe("1d")
                            .totalEvaluated(20).totalPending(2).winRate(55.0).avgError(3.2).build());

            mvc.perform(get("/api/backtest/summary").param("symbol", "BTC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                    .andExpect(jsonPath("$.totalEvaluated").value(20))
                    .andExpect(jsonPath("$.totalPending").value(2))
                    .andExpect(jsonPath("$.winRate").value(55.0))
                    .andExpect(jsonPath("$.avgError").value(3.2));
        }
    }

    @Nested
    class SystemStats {

        @Test
        void reportsTrainingDisabledInTheTestProfile() throws Exception {
            mvc.perform(get("/api/system/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trainingStatus").value("Disabled"))
                    .andExpect(jsonPath("$.memoryUsage.maxMemoryMB", greaterThan(0)))
                    .andExpect(jsonPath("$.uptime").isNumber());
        }
    }

    @Nested
    class DashboardPage {

        @Test
        void servesTheChartPageWithConfiguredSymbols() throws Exception {
            mvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("chart"))
                    .andExpect(model().attribute("symbols", contains("BTC", "SOL", "TAO", "WIF")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        }
    }
}
