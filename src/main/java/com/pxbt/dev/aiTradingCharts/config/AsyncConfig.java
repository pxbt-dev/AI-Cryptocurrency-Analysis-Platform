package com.pxbt.dev.aiTradingCharts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "trainingTaskExecutor")
    public Executor trainingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);  // Only 1 training job at a time - prevents memory doubling
        executor.setMaxPoolSize(1);   // Strictly limited - Weka training is very memory-intensive
        executor.setQueueCapacity(1); // Only 1 pending job queued (extra requests are dropped by caller guard)
        executor.setThreadNamePrefix("ML-Train-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
