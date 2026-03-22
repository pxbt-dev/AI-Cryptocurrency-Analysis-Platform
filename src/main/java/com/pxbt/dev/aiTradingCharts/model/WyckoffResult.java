package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WyckoffResult {
    private String phase;
    private String details;
    private double score; // -1.0 (Markdown) to +1.0 (Markup)
}
