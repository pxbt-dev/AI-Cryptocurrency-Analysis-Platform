package com.pxbt.dev.aiTradingCharts.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Configuration
public class MetricsConfig {

    @Value("${spring.application.name:aiTradingCharts}")
    private String applicationName;

    @Value("${management.otlp.metrics.export.url:https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/metrics}")
    private String url;

    @Value("${OTLP_CREDENTIALS:}")
    private String credentials;

    @Bean
    public OtlpMeterRegistry otlpMeterRegistry() {
        // Guard: if credentials are not set (e.g. local dev), skip to avoid 401 spam
        if (credentials == null || credentials.isBlank()) {
            log.warn("⚠️ OTLP_CREDENTIALS not set - Grafana OTLP export disabled. Set env var on Railway to enable.");
            OtlpConfig noopConfig = new OtlpConfig() {
                @Override
                public String get(String key) { return null; }
                @Override
                public String url() { return "http://localhost:0/disabled"; }
                @Override
                public boolean enabled() { return false; }
            };
            return new OtlpMeterRegistry(noopConfig, Clock.SYSTEM);
        }

        log.info("✅ Configuring OTLP Grafana registry -> {}", url);

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
                return Duration.ofSeconds(30);
            }

            @Override
            public Map<String, String> headers() {
                return Map.of("Authorization", "Basic " + credentials.trim());
            }

            @Override
            public Map<String, String> resourceAttributes() {
                return Map.of("service.name", applicationName);
            }
        };

        OtlpMeterRegistry registry = new OtlpMeterRegistry(config, Clock.SYSTEM);
        // Common tags matching standard Grafana/Kubernetes-based dashboards
        registry.config().commonTags(
            "application",          applicationName,
            "app",                  applicationName,
            "job",                  applicationName,
            "namespace",            "railway",
            "kubernetes_pod_name",  "railway-instance",
            "instance",             "railway-app"
        );

        log.info("✅ OTLP Grafana registry configured (step=30s, service={})", applicationName);
        return registry;
    }
}
