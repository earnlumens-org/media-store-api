package org.earnlumens.mediastore.application.user;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that processes expired badge assignments.
 * Runs every hour — marks expired badges and clears authorBadge from user content
 * so it no longer appears in the community feed.
 */
@Component
public class BadgeExpirationTask {

    private static final Logger logger = LoggerFactory.getLogger(BadgeExpirationTask.class);

    /** Default tenant — single-tenant for now, multi-tenant-ready. */
    private static final String DEFAULT_TENANT = "earnlumens";

    private final UserBadgeService userBadgeService;

    public BadgeExpirationTask(UserBadgeService userBadgeService) {
        this.userBadgeService = userBadgeService;
    }

    @Scheduled(fixedDelayString = "${mediastore.badges.expiration-check-interval-ms:3600000}",
               initialDelayString = "${mediastore.badges.expiration-check-initial-delay-ms:60000}")
    public void run() {
        TenantContext.runWithoutTenant(() -> {
            try {
                long processed = userBadgeService.processExpiredBadges(DEFAULT_TENANT);
                if (processed > 0) {
                    logger.info("Badge expiration cycle: processed {} expired assignment(s)", processed);
                }
            } catch (Exception e) {
                logger.error("Badge expiration cycle failed: {}", e.getMessage(), e);
            }
        });
    }
}
