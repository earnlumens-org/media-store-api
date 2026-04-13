package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic dispatcher that picks up PENDING moderation jobs and dispatches
 * them to the Cloud Run worker via {@link ModerationJobService}.
 *
 * <p>Runs on a fixed delay (default 15 s, configurable via
 * {@code mediastore.moderation.dispatch-interval-ms}).
 */
@Component
public class ModerationDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ModerationDispatcher.class);

    private final ModerationJobService jobService;

    public ModerationDispatcher(ModerationJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${mediastore.moderation.dispatch-interval-ms:15000}",
               initialDelayString = "${mediastore.moderation.dispatch-interval-ms:15000}")
    public void run() {
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
