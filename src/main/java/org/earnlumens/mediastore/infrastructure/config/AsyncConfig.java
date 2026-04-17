package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async thread pool configuration for background tasks such as
 * moderation job dispatch.
 */
@Configuration
public class AsyncConfig {

    private final ModerationConfig moderationConfig;

    public AsyncConfig(ModerationConfig moderationConfig) {
        this.moderationConfig = moderationConfig;
    }

    @Bean(name = "moderationDispatchExecutor")
    public Executor moderationDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(moderationConfig.getDispatchThreads());
        executor.setMaxPoolSize(moderationConfig.getDispatchThreads() * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mod-dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
