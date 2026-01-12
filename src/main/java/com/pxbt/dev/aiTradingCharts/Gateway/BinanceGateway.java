package com.pxbt.dev.aiTradingCharts.Gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


@Slf4j
@Service
public class BinanceGateway {

    private final WebClient webClient;

    @Value("${binance.api.klines-endpoint}")
    private String binanceKlinesEndpoint;

    public BinanceGateway(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> getRawKlines(String symbol, String interval, int limit) {
        return getRawKlines(symbol, interval, limit, null);
    }

    public Mono<String> getRawKlines(String symbol, String interval, int limit, Long endTime) {
        String binanceSymbol = symbol.toUpperCase() + "USDT";

        String url = String.format("%s?symbol=%s&interval=%s&limit=%d",
                binanceKlinesEndpoint, binanceSymbol, interval, limit);

        if (endTime != null) {
            url += "&endTime=" + endTime;
        }

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class);
    }

}