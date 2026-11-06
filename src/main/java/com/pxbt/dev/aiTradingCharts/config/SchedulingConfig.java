package com.pxbt.dev.aiTradingCharts.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on all @Scheduled jobs (data refresh, retraining, backtests, monitoring).
 *
 * On by default. The test profile sets app.background.enabled=false so tests
 * never start timers that call live market APIs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.background.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
