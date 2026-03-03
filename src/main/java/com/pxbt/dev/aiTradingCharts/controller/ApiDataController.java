package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.dto.ChartDataResponseDto;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.model.AIAnalysisResult;
import com.pxbt.dev.aiTradingCharts.service.BinanceHistoricalService;
import com.pxbt.dev.aiTradingCharts.service.TradingAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chart")
public class ApiDataController {

    @Autowired
    private BinanceHistoricalService binanceHistoricalService;

    @Autowired
    private TradingAnalysisService tradingAnalysisService;

    @GetMapping("/data")
    public ResponseEntity<ChartDataResponseDto> getChartData(
            @RequestParam String symbol,
            @RequestParam String timeframe) {

        log.info("📈 Chart data requested - Symbol: {}, Timeframe: {}", symbol, timeframe);

        try {
            List<CryptoPrice> historicalData = binanceHistoricalService.getHistoricalDataReactive(symbol, timeframe, 100)
                    .block(); // Using block() since this is a synchronous endpoint

            AIAnalysisResult analysis = tradingAnalysisService.analyzePriceData(historicalData, timeframe);

            ChartDataResponseDto response = new ChartDataResponseDto(historicalData, analysis, timeframe);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Failed to get chart data for {} {}: {}", symbol, timeframe, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}