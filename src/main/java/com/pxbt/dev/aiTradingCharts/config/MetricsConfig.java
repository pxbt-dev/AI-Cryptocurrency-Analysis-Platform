package com.pxbt.dev.aiTradingCharts.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class MetricsConfig {

    @Value("${management.otlp.metrics.export.url:https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/metrics}")
    private String url;

    @Value("${management.otlp.metrics.export.step:30s}")
    private String step;

    @Value("${OTLP_CREDENTIALS:}")
    private String credentials;

    @Bean
    public OtlpMeterRegistry otlpMeterRegistry() {
        OtlpConfig config = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }
            
            @Override
            public String url() {
                return url;
            }

            @Override
            public Duration step() {
                return Duration.parse(step);
            }
            
            @Override
            public Map<String, String> headers() {
                if (credentials != null && !credentials.isEmpty()) {
                    return Map.of("Authorization", "Basic " + credentials.trim());
                }
                return Map.of();
            }
        };
        
        return new OtlpMeterRegistry(config, Clock.SYSTEM);
    }
}

