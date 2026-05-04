package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic dispatcher that picks up PENDING thumbnail jobs and dispatches
 * them to the Cloud Run worker via {@link ThumbnailJobService}.
 *
 * <p>Runs on a fixed delay (default 10 s, configurable via
 * {@code mediastore.thumbnail.dispatch-interval-ms}).
 */
@Component
public class ThumbnailDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailDispatcher.class);

    private final ThumbnailJobService jobService;

    public ThumbnailDispatcher(ThumbnailJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${mediastore.thumbnail.dispatch-interval-ms:10000}",
               initialDelayString = "${mediastore.thumbnail.dispatch-interval-ms:10000}")
    public void run() {
        TenantContext.runWithoutTenant(() -> {
            try {
                int dispatched = jobService.dispatchPendingJobs();
                if (dispatched > 0) {
                    logger.info("Thumbnail dispatcher cycle complete: dispatched {} job(s)", dispatched);
                }
            } catch (Exception e) {
                logger.error("Thumbnail dispatcher cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
