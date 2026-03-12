package com.pxbt.dev.aiTradingCharts.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class HtmlController {

    @Autowired
    private com.pxbt.dev.aiTradingCharts.config.SymbolConfig symbolConfig;

    @GetMapping("/")
    public String chartPage(Model model) {
        System.out.println("🎯 Serving main chart page");
        model.addAttribute("symbols", symbolConfig.getSymbols());
        return "chart"; // This serves chart.html from templates/
    }
}