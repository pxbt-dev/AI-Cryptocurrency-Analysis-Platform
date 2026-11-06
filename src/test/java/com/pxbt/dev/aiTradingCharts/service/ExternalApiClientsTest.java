package com.pxbt.dev.aiTradingCharts.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pxbt.dev.aiTradingCharts.Gateway.BinanceGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the external API clients against WireMock stubs instead of the real
 * Binance and alternative.me APIs, so results never depend on the network or the market.
 */
class ExternalApiClientsTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private String baseUrl() {
        return "http://localhost:" + wireMock.port();
    }

    @Test
    void binanceGatewayRequestsUsdtPairWithIntervalLimitAndEndTime() {
        String klines = "[[1700000000000,\"100.0\",\"110.0\",\"90.0\",\"105.0\",\"12.5\"]]";
        wireMock.stubFor(get(urlPathEqualTo("/api/v3/klines"))
                .willReturn(okJson(klines)));

        BinanceGateway gateway = new BinanceGateway(WebClient.builder().build());
        ReflectionTestUtils.setField(gateway, "binanceKlinesEndpoint", baseUrl() + "/api/v3/klines");

        String body = gateway.getRawKlines("btc", "1d", 2, 1700000000000L).block();

        assertEquals(klines, body);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v3/klines"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .withQueryParam("interval", equalTo("1d"))
                .withQueryParam("limit", equalTo("2"))
                .withQueryParam("endTime", equalTo("1700000000000")));
    }

    @Test
    void fearGreedExtremeFearGivesBullishMeanReversionSignal() {
        wireMock.stubFor(get(urlPathEqualTo("/fng/"))
                .willReturn(okJson("{\"data\":[{\"value\":\"10\",\"value_classification\":\"Extreme Fear\"}]}")));

        FearGreedService service = new FearGreedService();
        ReflectionTestUtils.setField(service, "url", baseUrl() + "/fng/?limit=1");

        assertEquals(10.0, service.getScore());
        assertEquals("EXTREME_FEAR", service.getClassification());
        assertEquals(0.5, service.getMeanReversionSignal(), 1e-9);
    }

    @Test
    void fearGreedFallsBackToNeutralWhenApiFails() {
        wireMock.stubFor(get(urlPathEqualTo("/fng/")).willReturn(serverError()));

        FearGreedService service = new FearGreedService();
        ReflectionTestUtils.setField(service, "url", baseUrl() + "/fng/?limit=1");

        assertEquals(50.0, service.getScore());
        assertEquals(0.0, service.getMeanReversionSignal());
    }

    @Test
    void fundingRateIsParsedFromPremiumIndex() {
        wireMock.stubFor(get(urlPathEqualTo("/fapi/v1/premiumIndex"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .willReturn(okJson("{\"symbol\":\"BTCUSDT\",\"lastFundingRate\":\"0.00025\"}")));

        FundingRateService service = new FundingRateService();
        ReflectionTestUtils.setField(service, "fundingUrl", baseUrl() + "/fapi/v1/premiumIndex?symbol=%sUSDT");

        assertEquals(0.00025, service.getFundingRate("btc"), 1e-12);
    }
}
