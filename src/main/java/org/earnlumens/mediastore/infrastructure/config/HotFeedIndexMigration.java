package org.earnlumens.mediastore.infrastructure.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;

import java.util.List;

/**
 * Startup migration for the hot-feed scalability indexes (Phase 1 of
 * SCALABILITY-AUDIT.md).
 * <p>
 * Does two things, both idempotent:
 * <ol>
 *   <li>Backfills the denormalized {@code authorUsernameLower} field on
 *       {@code entries} and {@code collections} (lowercase copy of
 *       {@code authorUsername}) using an aggregation-pipeline update. Only
 *       documents missing the field are touched.</li>
 *   <li>Explicitly creates the new compound indexes. This is required because
 *       {@code spring.data.mongodb.auto-index-creation} is disabled, so the
 *       {@code @CompoundIndex} annotations on the entities are documentation
 *       only. {@code createIndex} is a no-op when the index already exists.</li>
 * </ol>
 * Remove this class once all environments have been migrated.
 */
@Component
public class HotFeedIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(HotFeedIndexMigration.class);

    private final MongoTemplate mongoTemplate;

    public HotFeedIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillAuthorUsernameLower("entries");
        backfillAuthorUsernameLower("collections");

        ensureIndex("entries", "idx_tenant_status_badge_published",
                new Document("tenantId", 1).append("status", 1)
                        .append("authorBadge", 1).append("publishedAt", -1));
        ensureIndex("entries", "idx_tenant_status_authorlower_published",
                new Document("tenantId", 1).append("status", 1)
                        .append("authorUsernameLower", 1).append("publishedAt", -1));
        ensureIndex("collections", "idx_coll_tenant_status_badge_published",
                new Document("tenantId", 1).append("status", 1)
                        .append("authorBadge", 1).append("publishedAt", -1));
        ensureIndex("collections", "idx_coll_tenant_status_authorlower_published",
                new Document("tenantId", 1).append("status", 1)
                        .append("authorUsernameLower", 1).append("publishedAt", -1));
    }

    private void backfillAuthorUsernameLower(String collection) {
        try {
            UpdateResult result = mongoTemplate.getCollection(collection).updateMany(
                    Filters.and(
                            Filters.exists("authorUsername", true),
                            Filters.exists("authorUsernameLower", false)),
                    List.of(new Document("$set",
                            new Document("authorUsernameLower",
                                    new Document("$toLower", "$authorUsername")))));

            long modified = result.getModifiedCount();
            if (modified > 0) {
                logger.info("[HotFeedIndexMigration] Backfilled authorUsernameLower on {} {} documents",
                        modified, collection);
            } else {
                logger.info("[HotFeedIndexMigration] No {} documents to backfill — skipping", collection);
            }
        } catch (Exception e) {
            // Never block startup on a migration failure; the regular mapper path
            // keeps new writes correct and the migration retries on next boot.
            logger.error("[HotFeedIndexMigration] Backfill failed for {}: {}", collection, e.getMessage(), e);
        }
    }

    private void ensureIndex(String collection, String name, Document keys) {
        try {
            mongoTemplate.getCollection(collection).createIndex(keys,
                    new com.mongodb.client.model.IndexOptions().name(name).background(true));
            logger.info("[HotFeedIndexMigration] Ensured index {} on {}", name, collection);
        } catch (Exception e) {
            logger.error("[HotFeedIndexMigration] Failed to ensure index {} on {}: {}",
                    name, collection, e.getMessage(), e);
        }
    }
}
