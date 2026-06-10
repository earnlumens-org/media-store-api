package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic watchdog that detects stale moderation jobs (crashed workers)
 * and triggers retry or dead-letter via {@link ModerationJobService}.
 *
 * <p>Runs on a fixed delay (default 15 s, configurable via
 * {@code mediastore.moderation.watchdog-interval-ms}).
 */
@Component
public class ModerationJobWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(ModerationJobWatchdog.class);

    private final ModerationJobService jobService;
    private final DistributedLockService lockService;

    public ModerationJobWatchdog(ModerationJobService jobService, DistributedLockService lockService) {
        this.jobService = jobService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${mediastore.moderation.watchdog-interval-ms:15000}",
               initialDelayString = "${mediastore.moderation.watchdog-interval-ms:15000}")
    public void run() {
        if (!lockService.tryAcquire("moderation-watchdog", Duration.ofSeconds(12))) {
            return; // another instance is running this cycle
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                int recovered = jobService.recoverStaleJobs();
                if (recovered > 0) {
                    logger.info("Moderation watchdog: recovered {} stale job(s)", recovered);
                }
            } catch (Exception e) {
                logger.error("Moderation watchdog cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
