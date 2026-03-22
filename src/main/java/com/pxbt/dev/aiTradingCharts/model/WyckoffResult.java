package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WyckoffResult {
    private String phase;
    private String details;
    private double score; // -1.0 (Markdown) to +1.0 (Markup)
    private double volatility; // % relative standard deviation
    private double moneyFlow;  // Normalized -1.0 to +1.0
    private List<String> events = new ArrayList<>();
}
