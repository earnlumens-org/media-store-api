package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic re-run of the dispute trigger for unresolved moderation cases
 * (MYCELIUM-RESILIENCE.md R-20, DATA-MODEL.md §8.8): a crash between the
 * block and the disputes, or a purchase completed after the takedown, never
 * leaves an affected buyer without follow-up. Idempotent by construction.
 */
@Component
public class ModerationCaseSweep {

    private static final Logger logger = LoggerFactory.getLogger(ModerationCaseSweep.class);

    private final ContentModerationService moderationService;
    private final DistributedLockService lockService;
    private final int batchSize;

    public ModerationCaseSweep(ContentModerationService moderationService,
                               DistributedLockService lockService,
                               @Value("${mediastore.moderation.case-sweep-batch:200}") int batchSize) {
        this.moderationService = moderationService;
        this.lockService = lockService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${mediastore.moderation.case-sweep-interval-ms:300000}",
               initialDelayString = "${mediastore.moderation.case-sweep-interval-ms:300000}")
    public void run() {
        if (!lockService.tryAcquire("moderation-case-sweep", Duration.ofMinutes(4))) {
            return;
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                int created = moderationService.sweepUnresolvedCases(batchSize);
                if (created > 0) {
                    logger.info("Moderation case sweep: opened {} missing dispute(s)", created);
                }
            } catch (Exception e) {
                logger.error("Moderation case sweep failed: {}", e.getMessage(), e);
            }
        });
    }
}
