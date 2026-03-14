package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic watchdog that detects stale transcoding jobs (crashed workers)
 * and triggers retry or dead-letter via {@link TranscodingJobService}.
 *
 * <p>Runs on a fixed delay (default 30 s, configurable via
 * {@code mediastore.transcoding.watchdog-interval-ms}).
 *
 * <p>Design notes for scale:
 * <ul>
 *   <li>Uses {@code fixedDelayString} so the next cycle starts only after the
 *       previous one completes — no overlap.</li>
 *   <li>Batch-limited queries prevent memory pressure under high job volume.</li>
 *   <li>For multi-instance deployments, add a distributed lock (e.g., ShedLock
 *       with MongoDB) to ensure only one instance runs the watchdog.</li>
 * </ul>
 */
@Component
public class TranscodingJobWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(TranscodingJobWatchdog.class);

    private final TranscodingJobService jobService;

    public TranscodingJobWatchdog(TranscodingJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${mediastore.transcoding.watchdog-interval-ms:30000}",
               initialDelayString = "${mediastore.transcoding.watchdog-interval-ms:30000}")
    public void run() {
        TenantContext.runWithoutTenant(() -> {
            try {
                int recovered = jobService.recoverStaleJobs();
                if (recovered > 0) {
                    logger.info("Watchdog cycle complete: recovered {} stale job(s)", recovered);
                }
            } catch (Exception e) {
                logger.error("Watchdog cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
