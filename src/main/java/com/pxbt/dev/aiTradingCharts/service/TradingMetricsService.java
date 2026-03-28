package com.pxbt.dev.aiTradingCharts.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 📊 TradingMetricsService
 *
 * Expose custom business metrics to Grafana via Prometheus/Micrometer.
 * 
 * Reusable pattern — copy this to noaaWeatherBot and spreadBot!
 *
 * Key PromQL queries:
 *   - trading_predictions_total          → predictions per symbol/timeframe
 *   - trading_backtest_accuracy_gauge    → live accuracy % per symbol
 *   - trading_backtest_runs_total        → how many backtests run
 *   - trading_websocket_broadcasts_total → WS broadcast volume
 *   - trading_active_symbols_gauge       → symbols currently being tracked
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingMetricsService {

    private final MeterRegistry meterRegistry;

    // In-memory state for gauges
    private final Map<String, Double>     accuracyBySymbol    = new ConcurrentHashMap<>();
    private final AtomicInteger           activeSymbolCount   = new AtomicInteger(0);
    private final AtomicLong              totalPredictions    = new AtomicLong(0);
    private final AtomicLong              totalBacktestPoints = new AtomicLong(0);

    // Counters per symbol (lazy init)
    private final Map<String, Counter>    predictionCounters  = new ConcurrentHashMap<>();
    private final Map<String, Counter>    backtestCounters    = new ConcurrentHashMap<>();

    private Counter wsBroadcastCounter;
    private Counter wsErrorCounter;
    private Timer   predictionTimer;

    @PostConstruct
    public void init() {
        // Global counters
        wsBroadcastCounter = Counter.builder("trading_websocket_broadcasts_total")
                .description("Total WebSocket broadcasts sent")
                .register(meterRegistry);

        wsErrorCounter = Counter.builder("trading_websocket_errors_total")
                .description("Total WebSocket broadcast errors")
                .register(meterRegistry);

        predictionTimer = Timer.builder("trading_prediction_duration")
                .description("Time taken to generate an AI prediction")
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);

        // Global gauges (these read live from the maps)
        Gauge.builder("trading_active_symbols_gauge", activeSymbolCount, AtomicInteger::get)
                .description("Number of actively tracked symbols")
                .register(meterRegistry);

        Gauge.builder("trading_predictions_total_gauge", totalPredictions, AtomicLong::get)
                .description("Total AI predictions generated since startup")
                .register(meterRegistry);

        Gauge.builder("trading_backtest_evaluated_points_gauge", totalBacktestPoints, AtomicLong::get)
                .description("Total historical points evaluated in backtests")
                .register(meterRegistry);

        log.info("📊 TradingMetricsService initialized - exposing business metrics to Grafana");
    }

    // ───── PREDICTION METRICS ─────

    public void recordPrediction(String symbol, String timeframe) {
        totalPredictions.incrementAndGet();
        predictionCounters.computeIfAbsent(symbol + "_" + timeframe, k ->
            Counter.builder("trading_predictions_total")
                .description("AI predictions generated")
                .tag("symbol", symbol)
                .tag("timeframe", timeframe)
                .register(meterRegistry)
        ).increment();
    }

    public Timer.Sample startPredictionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopPredictionTimer(Timer.Sample sample) {
        sample.stop(predictionTimer);
    }

    // ───── BACKTEST METRICS ─────

    public void recordBacktestRun(String symbol, String timeframe, int recordsGenerated, double accuracyPercent) {
        totalBacktestPoints.addAndGet(recordsGenerated);

        // Update live accuracy gauge per symbol
        boolean isFirstRun = !accuracyBySymbol.containsKey(symbol);
        accuracyBySymbol.put(symbol, accuracyPercent);
        if (isFirstRun) {
            // Register a gauge that reads from the map (only register once per symbol)
            Gauge.builder("trading_backtest_accuracy_gauge", accuracyBySymbol,
                            map -> map.getOrDefault(symbol, 0.0))
                    .description("Latest backtest accuracy percentage")
                    .tag("symbol", symbol)
                    .register(meterRegistry);
        }

        backtestCounters.computeIfAbsent(symbol, k ->
            Counter.builder("trading_backtest_runs_total")
                .description("Number of backtest runs completed")
                .tag("symbol", symbol)
                .register(meterRegistry)
        ).increment();

        log.debug("📊 Metrics updated: {} backtest accuracy={}%", symbol, accuracyPercent);
    }

    // ───── WEBSOCKET METRICS ─────

    public void recordWsBroadcast() {
        wsBroadcastCounter.increment();
    }

    public void recordWsError() {
        wsErrorCounter.increment();
    }

    // ───── SYMBOL TRACKING ─────

    public void setActiveSymbols(int count) {
        activeSymbolCount.set(count);
    }
}
