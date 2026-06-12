package org.earnlumens.mediastore.infrastructure.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.UpdateResult;

import java.util.List;

/**
 * Startup migration for the search scalability indexes (Phase 2, task 2.1 of
 * SCALABILITY-AUDIT.md — P0-2).
 * <p>
 * Does three things, all idempotent:
 * <ol>
 *   <li>Backfills the denormalized {@code titleLower} field on {@code entries}
 *       (lowercase copy of {@code title}) used by index-backed suggestion
 *       prefix lookups. Only documents missing the field are touched.</li>
 *   <li>Creates the weighted compound text indexes that back {@code $text}
 *       search (one text index max per collection). Both are prefixed with
 *       {@code tenantId + status} equality keys so the index is partitioned per
 *       tenant — every {@code $text} query on these collections MUST therefore
 *       include equality conditions on both fields:
 *       <ul>
 *         <li>{@code entries}: title:10, tags:8, authorUsername:5, description:1</li>
 *         <li>{@code collections}: title:10, authorUsername:5, description:1</li>
 *       </ul>
 *       {@code default_language: "none"} disables stemming/stop-words so token
 *       matching is predictable across the platform's 36 content languages.</li>
 *   <li>Creates the {@code idx_tenant_status_titlelower} compound index for
 *       suggestion prefix scans.</li>
 * </ol>
 * Explicit creation is required because {@code spring.data.mongodb.auto-index-creation}
 * is disabled. {@code createIndex} is a no-op when the index already exists.
 * Remove this class once all environments have been migrated.
 */
@Component
public class SearchTextIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SearchTextIndexMigration.class);

    private final MongoTemplate mongoTemplate;

    public SearchTextIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillTitleLower();

        ensureIndex("entries", "idx_tenant_status_titlelower",
                new Document("tenantId", 1).append("status", 1).append("titleLower", 1),
                new IndexOptions());

        ensureIndex("entries", "idx_text_search",
                new Document("tenantId", 1).append("status", 1)
                        .append("title", "text").append("tags", "text")
                        .append("authorUsername", "text").append("description", "text"),
                new IndexOptions()
                        .weights(new Document("title", 10).append("tags", 8)
                                .append("authorUsername", 5).append("description", 1))
                        .defaultLanguage("none"));

        ensureIndex("collections", "idx_coll_text_search",
                new Document("tenantId", 1).append("status", 1)
                        .append("title", "text")
                        .append("authorUsername", "text").append("description", "text"),
                new IndexOptions()
                        .weights(new Document("title", 10)
                                .append("authorUsername", 5).append("description", 1))
                        .defaultLanguage("none"));
    }

    private void backfillTitleLower() {
        try {
            UpdateResult result = mongoTemplate.getCollection("entries").updateMany(
                    Filters.and(
                            Filters.exists("title", true),
                            Filters.exists("titleLower", false)),
                    List.of(new Document("$set",
                            new Document("titleLower", new Document("$toLower", "$title")))));

            long modified = result.getModifiedCount();
            if (modified > 0) {
                logger.info("[SearchTextIndexMigration] Backfilled titleLower on {} entries documents", modified);
            } else {
                logger.info("[SearchTextIndexMigration] No entries documents to backfill — skipping");
            }
        } catch (Exception e) {
            // Never block startup on a migration failure; the regular mapper path
            // keeps new writes correct and the migration retries on next boot.
            logger.error("[SearchTextIndexMigration] Backfill failed: {}", e.getMessage(), e);
        }
    }

    private void ensureIndex(String collection, String name, Document keys, IndexOptions options) {
        try {
            mongoTemplate.getCollection(collection)
                    .createIndex(keys, options.name(name).background(true));
            logger.info("[SearchTextIndexMigration] Ensured index {} on {}", name, collection);
        } catch (Exception e) {
            logger.error("[SearchTextIndexMigration] Failed to ensure index {} on {}: {}",
                    name, collection, e.getMessage(), e);
        }
    }
}
