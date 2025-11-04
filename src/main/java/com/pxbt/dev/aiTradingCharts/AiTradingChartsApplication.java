package com.pxbt.dev.aiTradingCharts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;
import java.util.Timer;
import java.util.TimerTask;

@SpringBootApplication
public class AiTradingChartsApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiTradingChartsApplication.class, args);
	}

	@PostConstruct
	public void startMemoryMonitoring() {
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				Runtime runtime = Runtime.getRuntime();
				long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
				long max = runtime.maxMemory() / (1024 * 1024);
				long free = runtime.freeMemory() / (1024 * 1024);
				int usagePercent = (int) ((used * 100) / max);

				System.out.println("🧠 MEMORY: " + used + "MB / " + max + "MB (" + usagePercent + "%) - Free: " + free + "MB");

				// Warning if usage is high
				if (usagePercent > 80) {
					System.out.println("⚠️  WARNING: High memory usage!");
				}
			}
		}, 10000, 30000); // Start after 10 seconds, repeat every 30 seconds
	}
}