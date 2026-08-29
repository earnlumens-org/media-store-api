package org.earnlumens.mediastore.infrastructure.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup migration for the {@code plan_orders} indexes
 * (custom-domain-upgrade 1B.1).
 * <p>
 * Explicitly creates the indexes because
 * {@code spring.data.mongodb.auto-index-creation} is disabled, so the
 * {@code @CompoundIndex} annotations on {@code PlanOrderDocument} are
 * documentation only. {@code createIndex} is a no-op when the index exists.
 * <ul>
 *   <li>{@code idx_plan_order_tenant_status} — backs the owner's
 *       open-order lookups on prepare.</li>
 *   <li>{@code idx_plan_order_stellar_tx} — backs the anti-replay check.</li>
 *   <li>{@code idx_plan_order_applied} — backs admin-api's
 *       COMPLETED-but-unapplied sweep.</li>
 * </ul>
 */
@Component
public class PlanOrderIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlanOrderIndexMigration.class);

    private static final String COLLECTION = "plan_orders";

    private final MongoTemplate mongoTemplate;

    public PlanOrderIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex("idx_plan_order_tenant_status",
                new Document("tenantId", 1).append("status", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_plan_order_tenant_status").background(true));
        ensureIndex("idx_plan_order_stellar_tx",
                new Document("stellarTxHash", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_plan_order_stellar_tx").background(true));
        ensureIndex("idx_plan_order_applied",
                new Document("status", 1).append("appliedAt", 1).append("completedAt", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_plan_order_applied").background(true));
    }

    private void ensureIndex(String name, Document keys,
                             com.mongodb.client.model.IndexOptions options) {
        try {
            mongoTemplate.getCollection(COLLECTION).createIndex(keys, options);
            logger.info("[PlanOrderIndexMigration] Ensured index {} on {}", name, COLLECTION);
        } catch (Exception e) {
            // Never block startup on a migration failure; retries on next boot.
            logger.error("[PlanOrderIndexMigration] Failed to ensure index {} on {}: {}",
                    name, COLLECTION, e.getMessage(), e);
        }
    }
}
