package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic watchdog that detects stale moderation jobs (crashed workers)
 * and triggers retry or dead-letter via {@link ModerationJobService}.
 *
 * <p>Runs on a fixed delay (default 30 s, configurable via
 * {@code mediastore.moderation.watchdog-interval-ms}).
 */
@Component
public class ModerationJobWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(ModerationJobWatchdog.class);

    private final ModerationJobService jobService;

    public ModerationJobWatchdog(ModerationJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${mediastore.moderation.watchdog-interval-ms:30000}",
               initialDelayString = "${mediastore.moderation.watchdog-interval-ms:30000}")
    public void run() {
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
