package com.pxbt.dev.aiTradingCharts.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.io.Serializable;

@Data
@AllArgsConstructor
public class ModelPerformance implements Serializable {
    private static final long serialVersionUID = 1L;
    private double r2;              // R-squared score
    private double rmse;            // Root Mean Squared Error
    private double mae;             // Mean Absolute Error
    private int trainingSampleSize; // Number of training samples
    private int testSampleSize;     // Number of test samples

    public boolean isAcceptable() {
        return r2 > 0.3 && rmse < 0.05;
    }

    public String getQuality() {
        if (r2 > 0.6) return "EXCELLENT";
        if (r2 > 0.4) return "GOOD";
        if (r2 > 0.2) return "FAIR";
        return "POOR";
    }
}