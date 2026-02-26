package org.earnlumens.mediastore.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.client.result.UpdateResult;

/**
 * One-time data migration that runs on application startup.
 * <p>
 * Transitions all assets with status {@code UPLOADED} to {@code READY}.
 * This is needed because the original finalize-upload flow saved assets with
 * status UPLOADED, but the entitlement service requires READY to serve content.
 * <p>
 * Safe to run multiple times — it only updates documents still in UPLOADED state.
 * Remove this class once all environments have been migrated (or when a
 * transcoding pipeline is implemented that manages asset status transitions).
 */
@Component
public class AssetStatusMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AssetStatusMigration.class);

    private final MongoTemplate mongoTemplate;

    public AssetStatusMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query query = new Query(Criteria.where("status").is("UPLOADED"));
        Update update = new Update().set("status", "READY");

        UpdateResult result = mongoTemplate.updateMulti(query, update, "assets");

        long modified = result.getModifiedCount();
        if (modified > 0) {
            logger.info("[AssetStatusMigration] Migrated {} assets from UPLOADED → READY", modified);
        } else {
            logger.info("[AssetStatusMigration] No UPLOADED assets to migrate — skipping");
        }
    }
}
