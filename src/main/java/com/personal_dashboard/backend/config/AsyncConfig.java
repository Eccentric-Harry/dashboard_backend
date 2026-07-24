package com.personal_dashboard.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables Spring's {@code @Async} support and provides a dedicated, bounded
 * executor for the AI meal-analysis pipeline.
 *
 * <p>Meal analysis is long-running and IO-bound (two Gemini vision calls plus a
 * best-effort image generation), so it runs on this pool rather than the request
 * threads. That keeps the {@code POST /meals/analyze} response instantaneous —
 * it just enqueues a job — while the actual work happens in the background and
 * the client polls for the result.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "mealAnalysisExecutor")
    public Executor mealAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Queue additional scans when all threads are busy rather than rejecting them.
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("meal-analysis-");
        // Let in-flight scans finish on shutdown so a job isn't left half-done.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
