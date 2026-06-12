package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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

    /**
     * Executor for the blocking phase of payment submission (Horizon submit +
     * on-chain confirmation polling, up to ~20 s per payment). Virtual threads:
     * the polling sleeps cost no platform threads, so hundreds of concurrent
     * confirmations never starve Tomcat or other pools. The concurrency limit
     * only bounds outbound Horizon traffic. Tasks killed by an instance
     * shutdown are repaired by the payment reconciliation watchdog.
     */
    @Bean(name = "paymentSubmitExecutor")
    public Executor paymentSubmitExecutor(
            @Value("${mediastore.payments.submit-concurrency:200}") int concurrencyLimit) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("payment-submit-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        return executor;
    }
}
