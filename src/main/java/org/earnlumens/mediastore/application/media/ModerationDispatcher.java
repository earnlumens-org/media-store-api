package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic dispatcher that picks up PENDING moderation jobs and dispatches
 * them to the Cloud Run worker via {@link ModerationJobService}.
 *
 * <p>Runs on a fixed delay (default 5 s, configurable via
 * {@code mediastore.moderation.dispatch-interval-ms}).
 */
@Component
public class ModerationDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ModerationDispatcher.class);

    private final ModerationJobService jobService;
    private final DistributedLockService lockService;

    public ModerationDispatcher(ModerationJobService jobService, DistributedLockService lockService) {
        this.jobService = jobService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${mediastore.moderation.dispatch-interval-ms:5000}",
               initialDelayString = "${mediastore.moderation.dispatch-interval-ms:5000}")
    public void run() {
        if (!lockService.tryAcquire("moderation-dispatcher", Duration.ofSeconds(4))) {
            return; // another instance is running this cycle
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                int dispatched = jobService.dispatchPendingJobs();
                if (dispatched > 0) {
                    logger.info("Moderation dispatcher: dispatched {} job(s)", dispatched);
                }
            } catch (Exception e) {
                logger.error("Moderation dispatcher cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
