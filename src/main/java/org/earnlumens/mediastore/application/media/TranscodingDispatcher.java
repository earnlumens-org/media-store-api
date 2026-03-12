package org.earnlumens.mediastore.application.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic dispatcher that picks up PENDING transcoding jobs and dispatches
 * them to the Cloud Run worker via {@link TranscodingJobService}.
 *
 * <p>Runs on a fixed delay (default 10 s, configurable via
 * {@code mediastore.transcoding.dispatch-interval-ms}).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Uses {@code fixedDelayString} so cycles never overlap.</li>
 *   <li>Batch size is controlled by {@code mediastore.transcoding.dispatch-batch-size}.</li>
 *   <li>If Cloud Run is not configured (local dev), dispatch is silently skipped
 *       by the adapter — no errors, just a warning log on first attempt.</li>
 * </ul>
 */
@Component
public class TranscodingDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(TranscodingDispatcher.class);

    private final TranscodingJobService jobService;

    public TranscodingDispatcher(TranscodingJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${mediastore.transcoding.dispatch-interval-ms:10000}",
               initialDelayString = "${mediastore.transcoding.dispatch-interval-ms:10000}")
    public void run() {
        try {
            int dispatched = jobService.dispatchPendingJobs();
            if (dispatched > 0) {
                logger.info("Dispatcher cycle complete: dispatched {} job(s)", dispatched);
            }
        } catch (Exception e) {
            logger.error("Dispatcher cycle failed: {}", e.getMessage(), e);
        }
    }
}
