package com.pxbt.dev.aiTradingCharts.controller;

import com.pxbt.dev.aiTradingCharts.model.AccuracyRecord;
import com.pxbt.dev.aiTradingCharts.service.AccuracyPersistenceService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/audit")
    public List<AccuracyRecord> getAuditLogs(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe) {
        
        if (symbol != null && timeframe != null) {
            return accuracyPersistenceService.getRecords(symbol, timeframe);
        }
        return accuracyPersistenceService.getAllRecords();
    }
}
