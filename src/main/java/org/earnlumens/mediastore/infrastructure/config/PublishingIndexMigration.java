package org.earnlumens.mediastore.infrastructure.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Startup migration for the Publishing Block indexes.
 * <p>
 * Explicitly creates the indexes because
 * {@code spring.data.mongodb.auto-index-creation} is disabled, so the
 * {@code @CompoundIndex} annotations on the entities are documentation only.
 * {@code createIndex} is a no-op when the index already exists.
 * <ul>
 *   <li>{@code publishing_blocks.idx_pubblock_tenant_space_seq} — unique,
 *       guarantees one block per (tenant, space, sequence); concurrent block
 *       creation resolves via DuplicateKeyException retry.</li>
 *   <li>{@code publishing_blocks.idx_pubblock_tenant_space_status_seq} —
 *       backs "earliest OPEN block" lookups on the enqueue hot path.</li>
 *   <li>{@code publishing_blocks.idx_pubblock_status_lock} /
 *       {@code idx_pubblock_status_publish} — back the cross-tenant scheduler
 *       sweeps (lock phase / publish phase).</li>
 *   <li>{@code publishing_queue_items.idx_pubitem_active_unique} — unique
 *       partial index over active items: one active queue item per entity per
 *       space (anti-spam / anti-double-queue at the storage layer).</li>
 *   <li>{@code publishing_queue_items.idx_pubitem_tenant_block_status} —
 *       backs block ordering/locking/publishing reads.</li>
 *   <li>{@code publishing_queue_items.idx_pubitem_tenant_space_status_seq} —
 *       backs "how many ahead of me" counts.</li>
 * </ul>
 */
@Component
public class PublishingIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PublishingIndexMigration.class);

    private final MongoTemplate mongoTemplate;

    public PublishingIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex("publishing_blocks", "idx_pubblock_tenant_space_seq",
                new Document("tenantId", 1).append("spaceId", 1).append("sequence", -1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubblock_tenant_space_seq").unique(true).background(true));
        ensureIndex("publishing_blocks", "idx_pubblock_tenant_space_status_seq",
                new Document("tenantId", 1).append("spaceId", 1)
                        .append("status", 1).append("sequence", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubblock_tenant_space_status_seq").background(true));
        ensureIndex("publishing_blocks", "idx_pubblock_status_lock",
                new Document("status", 1).append("lockAt", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubblock_status_lock").background(true));
        ensureIndex("publishing_blocks", "idx_pubblock_status_publish",
                new Document("status", 1).append("publishAt", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubblock_status_publish").background(true));

        ensureIndex("publishing_queue_items", "idx_pubitem_active_unique",
                new Document("tenantId", 1).append("spaceId", 1)
                        .append("entityType", 1).append("entityId", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubitem_active_unique").unique(true).background(true)
                        .partialFilterExpression(new Document("status",
                                new Document("$in", Arrays.asList("QUEUED", "LOCKED")))));
        ensureIndex("publishing_queue_items", "idx_pubitem_tenant_entity",
                new Document("tenantId", 1).append("entityType", 1)
                        .append("entityId", 1).append("enqueuedAt", -1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubitem_tenant_entity").background(true));
        ensureIndex("publishing_queue_items", "idx_pubitem_tenant_block_status",
                new Document("tenantId", 1).append("blockId", 1).append("status", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubitem_tenant_block_status").background(true));
        ensureIndex("publishing_queue_items", "idx_pubitem_tenant_space_status_seq",
                new Document("tenantId", 1).append("spaceId", 1)
                        .append("status", 1).append("blockSequence", 1),
                new com.mongodb.client.model.IndexOptions()
                        .name("idx_pubitem_tenant_space_status_seq").background(true));
    }

    private void ensureIndex(String collection, String name, Document keys,
                             com.mongodb.client.model.IndexOptions options) {
        try {
            mongoTemplate.getCollection(collection).createIndex(keys, options);
            logger.info("[PublishingIndexMigration] Ensured index {} on {}", name, collection);
        } catch (Exception e) {
            // Never block startup on a migration failure; retries on next boot.
            logger.error("[PublishingIndexMigration] Failed to ensure index {} on {}: {}",
                    name, collection, e.getMessage(), e);
        }
    }
}
