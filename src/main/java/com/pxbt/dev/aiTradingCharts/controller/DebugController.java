package com.pxbt.dev.aiTradingCharts.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class DebugController {

    @GetMapping("/api/debug/jvm")
    public Map<String, Object> getJvmInfo() {
        Map<String, Object> info = new HashMap<>();

        // Memory
        Runtime rt = Runtime.getRuntime();
        info.put("maxMemoryMB", rt.maxMemory() / 1024 / 1024);
        info.put("totalMemoryMB", rt.totalMemory() / 1024 / 1024);
        info.put("freeMemoryMB", rt.freeMemory() / 1024 / 1024);
        info.put("availableProcessors", rt.availableProcessors());

        // JVM info
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("jvmName", System.getProperty("java.vm.name"));

        // Check if our options are applied
        info.put("sun.java.command", System.getProperty("sun.java.command"));
        info.put("javaOpts", System.getenv("JAVA_OPTS"));

        // Heap dump (for debugging)
        try {
            java.lang.management.MemoryMXBean memoryMxBean =
                    java.lang.management.ManagementFactory.getMemoryMXBean();
            info.put("heapUsage", memoryMxBean.getHeapMemoryUsage().toString());
            info.put("nonHeapUsage", memoryMxBean.getNonHeapMemoryUsage().toString());
        } catch (Exception e) {
            info.put("memoryError", e.getMessage());
        }

        return info;
    }
}