package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.model.BacktestSummary;
import com.pxbt.dev.aiTradingCharts.service.AccuracyPersistenceService;
import com.pxbt.dev.aiTradingCharts.service.BacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class AccuracyController {
    private final AccuracyPersistenceService accuracyPersistenceService;
    private final BacktestService backtestService;

    // A walk-forward run refits models many times; keep it off the public site unless switched on
    @Value("${app.backtest.manual-run-enabled:false}")
    private boolean manualRunEnabled;

    @GetMapping("/run")
    public String triggerBacktest(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1d") String timeframe) {
        if (!manualRunEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manual backtest runs are disabled");
        }
        int count = backtestService.runBacktest(symbol, timeframe);
        return String.format("✅ Backtest complete for %s. Generated %d historical records.", symbol, count);
    }

    @GetMapping("/audit")
    public List<AccuracyRecord> getAuditLogs(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe) {

        if (symbol != null && timeframe != null) {
            // Normalize: frontend sends 'BTC', backend stores 'BTCUSDT'
            // Try both forms so either works
            List<AccuracyRecord> result = accuracyPersistenceService.getRecords(symbol, timeframe);
            if (result.isEmpty() && !symbol.toUpperCase().endsWith("USDT")) {
                result = accuracyPersistenceService.getRecords(symbol + "USDT", timeframe);
            }
            return result;
        }
        return accuracyPersistenceService.getAllRecords();
    }

    @GetMapping("/summary")
    public BacktestSummary getBacktestSummary(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1d") String timeframe) {

        BacktestSummary summary = accuracyPersistenceService.getSummary(symbol, timeframe);
        if (summary.getTotalEvaluated() == 0 && !symbol.toUpperCase().endsWith("USDT")) {
            summary = accuracyPersistenceService.getSummary(symbol + "USDT", timeframe);
        }
        return summary;
    }
}
