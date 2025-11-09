package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.dto.OHLCData;
import com.pxbt.dev.aiTradingCharts.model.CryptoPrice;
import com.pxbt.dev.aiTradingCharts.service.BinanceHistoricalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/historical")
public class HistoricalDataController {

    private final BinanceHistoricalService binanceHistoricalService;

    public HistoricalDataController(BinanceHistoricalService binanceHistoricalService) {
        this.binanceHistoricalService = binanceHistoricalService;
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<List<OHLCData>> getHistoricalData(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String timeframe,
            @RequestParam(defaultValue = "100") int limit) {

        try {
            // ✅ Get CryptoPrice objects instead of PriceUpdate
            List<CryptoPrice> cryptoPrices = binanceHistoricalService.getHistoricalDataReactive(symbol, timeframe, limit)
                    .block(); // Using block() for synchronous endpoint

            if (cryptoPrices == null || cryptoPrices.isEmpty()) {
                log.warn("❌ No historical data found for {} {}", symbol, timeframe);
                return ResponseEntity.notFound().build();
            }

            // ✅ Convert CryptoPrice to OHLCData for frontend
            List<OHLCData> ohlcData = cryptoPrices.stream()
                    .map(price -> new OHLCData (
                            price.getTimestamp(),
                            price.getOpen(),
                            price.getHigh(),
                            price.getLow(),
                            price.getClose(),
                            price.getVolume()
                    ))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(ohlcData);

        } catch (Exception e) {
            log.error("❌ Failed to get historical data for {} {}: {}", symbol, timeframe, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}