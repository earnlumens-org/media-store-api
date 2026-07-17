package org.earnlumens.mediastore.infrastructure.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup migration for the Original First indexes (see ORIGINAL-FIRST.md).
 * <p>
 * Explicitly creates the indexes because
 * {@code spring.data.mongodb.auto-index-creation} is disabled, so the
 * {@code @CompoundIndex} annotations on the entities are documentation only.
 * {@code createIndex} is a no-op when the index already exists.
 * <ul>
 *   <li>{@code entries.idx_tenant_fingerprint} — sparse (only entries with a
 *       FULL asset carry a fingerprint), backs duplicate detection at
 *       finalize time and fingerprint-group lookups for claims.</li>
 *   <li>{@code original_claims.idx_claim_unique} — unique, enforces one claim
 *       per user per fingerprint group (anti-abuse).</li>
 *   <li>{@code original_claims.idx_claim_user_created} — backs the rolling
 *       daily claim quota query.</li>
 * </ul>
 */
@Component
public class OriginalFirstIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(OriginalFirstIndexMigration.class);

    private final MongoTemplate mongoTemplate;

    public OriginalFirstIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex("entries", "idx_tenant_fingerprint",
                new Document("tenantId", 1).append("contentFingerprint", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_tenant_fingerprint").sparse(true).background(true));
        ensureIndex("original_claims", "idx_claim_unique",
                new Document("tenantId", 1).append("fingerprint", 1).append("claimantUserId", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_claim_unique").unique(true).background(true));
        ensureIndex("original_claims", "idx_claim_user_created",
                new Document("tenantId", 1).append("claimantUserId", 1).append("createdAt", -1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_claim_user_created").background(true));
    }

    private void ensureIndex(String collection, String name, Document keys,
                             com.mongodb.client.model.IndexOptions options) {
        try {
            mongoTemplate.getCollection(collection).createIndex(keys, options);
            logger.info("[OriginalFirstIndexMigration] Ensured index {} on {}", name, collection);
        } catch (Exception e) {
            // Never block startup on a migration failure; retries on next boot.
            logger.error("[OriginalFirstIndexMigration] Failed to ensure index {} on {}: {}",
                    name, collection, e.getMessage(), e);
        }
    }
}
