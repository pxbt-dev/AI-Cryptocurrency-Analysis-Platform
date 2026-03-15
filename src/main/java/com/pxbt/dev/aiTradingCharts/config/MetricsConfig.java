package com.pxbt.dev.aiTradingCharts.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class MetricsConfig {

    @Value("${spring.application.name:aiTradingCharts}")
    private String applicationName;

    @Value("${management.otlp.metrics.export.url:https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/metrics}")
    private String url;

    @Value("${OTLP_CREDENTIALS:}")
    private String credentials;

/* 
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
                return Duration.ofSeconds(30);
            }
            
            @Override
            public Map<String, String> headers() {
                if (credentials != null && !credentials.isEmpty()) {
                    return Map.of("Authorization", "Basic " + credentials.trim());
                }
                return Map.of();
            }

            @Override
            public Map<String, String> resourceAttributes() {
                // This is the "standard" way OTLP identifies your service
                return Map.of("service.name", applicationName);
            }
        };
        
        OtlpMeterRegistry registry = new OtlpMeterRegistry(config, Clock.SYSTEM);
        // Add tags that match standard Kubernetes-based dashboards
        registry.config().commonTags(
            "application", applicationName,
            "app", applicationName, // Dashboard looks for 'app'
            "job", applicationName,
            "namespace", "railway", // Dashboard looks for 'namespace'
            "kubernetes_pod_name", "railway-instance", // Dashboard looks for 'podname'
            "instance", "railway-app"
        );
        return registry;
    }
*/
}
