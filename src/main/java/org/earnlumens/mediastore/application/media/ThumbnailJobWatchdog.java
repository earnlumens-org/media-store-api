package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic watchdog that detects stale thumbnail jobs (crashed workers)
 * and triggers retry or dead-letter via {@link ThumbnailJobService}.
 *
 * <p>Runs on a fixed delay (default 30 s, configurable via
 * {@code mediastore.thumbnail.watchdog-interval-ms}).
 */
@Component
public class ThumbnailJobWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailJobWatchdog.class);

    private final ThumbnailJobService jobService;
    private final DistributedLockService lockService;

    public ThumbnailJobWatchdog(ThumbnailJobService jobService, DistributedLockService lockService) {
        this.jobService = jobService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${mediastore.thumbnail.watchdog-interval-ms:30000}",
               initialDelayString = "${mediastore.thumbnail.watchdog-interval-ms:30000}")
    public void run() {
        if (!lockService.tryAcquire("thumbnail-watchdog", Duration.ofSeconds(25))) {
            return; // another instance is running this cycle
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                int recovered = jobService.recoverStaleJobs();
                if (recovered > 0) {
                    logger.info("Thumbnail watchdog cycle complete: recovered {} stale job(s)", recovered);
                }
            } catch (Exception e) {
                logger.error("Thumbnail watchdog cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
