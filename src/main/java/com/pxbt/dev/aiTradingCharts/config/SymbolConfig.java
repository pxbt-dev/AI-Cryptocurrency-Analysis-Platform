package com.pxbt.dev.aiTradingCharts.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@Getter
public class SymbolConfig {

    @Value("${app.symbols:BTC,SOL,TAO,WIF}")
    private String symbolsCsv;

    @Value("${app.volatile-symbols:SOL,TAO,WIF}")
    private String volatileSymbolsCsv;

    private List<String> symbols;
    private List<String> volatileSymbols;

    @PostConstruct
    public void init() {
        symbols = Arrays.stream(symbolsCsv.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toList());
        
        volatileSymbols = Arrays.stream(volatileSymbolsCsv.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toList());
    }

    public boolean isVolatile(String symbol) {
        return volatileSymbols.contains(symbol.toUpperCase());
    }
}
