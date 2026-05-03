package org.earnlumens.mediastore.application.user;

import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled task that processes expired badge assignments.
 * Runs every hour — marks expired badges and clears authorBadge from user content
 * so it no longer appears in the community feed.
 *
 * <p>Iterates over every ACTIVE tenant returned by
 * {@link TenantConfigService#findAllActiveTenantIds()} so secondary tenants
 * do not silently miss badge expiration.
 */
@Component
public class BadgeExpirationTask {

    private static final Logger logger = LoggerFactory.getLogger(BadgeExpirationTask.class);

    private final UserBadgeService userBadgeService;
    private final TenantConfigService tenantConfigService;

    public BadgeExpirationTask(UserBadgeService userBadgeService,
                               TenantConfigService tenantConfigService) {
        this.userBadgeService = userBadgeService;
        this.tenantConfigService = tenantConfigService;
    }

    @Scheduled(fixedDelayString = "${mediastore.badges.expiration-check-interval-ms:3600000}",
               initialDelayString = "${mediastore.badges.expiration-check-initial-delay-ms:60000}")
    public void run() {
        TenantContext.runWithoutTenant(() -> {
            List<String> tenantIds;
            try {
                tenantIds = tenantConfigService.findAllActiveTenantIds();
            } catch (Exception e) {
                logger.error("Badge expiration: failed to enumerate active tenants: {}", e.getMessage(), e);
                return;
            }

            long totalProcessed = 0;
            for (String tenantId : tenantIds) {
                try {
                    totalProcessed += userBadgeService.processExpiredBadges(tenantId);
                } catch (Exception e) {
                    logger.error("Badge expiration cycle failed for tenant={}: {}", tenantId, e.getMessage(), e);
                }
            }
            if (totalProcessed > 0) {
                logger.info("Badge expiration cycle: processed {} expired assignment(s) across {} tenant(s)",
                        totalProcessed, tenantIds.size());
            }
        });
    }
}
