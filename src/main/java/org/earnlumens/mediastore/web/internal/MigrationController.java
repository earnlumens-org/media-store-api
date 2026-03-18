package org.earnlumens.mediastore.web.internal;

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ⚠️  TEMPORARY CONTROLLER — DELETE AFTER RUNNING MIGRATION ONCE          ⚠️  ║
// ║                                                                              ║
// ║  Removes platform wallet entries from paymentSplits in all entries.          ║
// ║  After the fix, only non-platform splits (SELLER, COLLABORATOR) are stored.  ║
// ║  The platform split is now applied dynamically at payment time from env.     ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.result.UpdateResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠️ TEMPORARY — Removes PLATFORM splits from all entries in MongoDB.
 * <p>
 * After this migration, entries only store SELLER/COLLABORATOR splits.
 * The platform wallet and fee are resolved dynamically at payment time.
 * <p>
 * Protected by X-Cleanup-Secret header — same secret as CleanupController.
 * Path is under /api/internal/** which is permitAll in WebSecurityConfig.
 * <p>
 * DELETE THIS FILE after running the migration in production.
 */
@RestController
@RequestMapping("/api/internal")
public class MigrationController {

    private static final Logger logger = LoggerFactory.getLogger(MigrationController.class);

    private final MongoTemplate mongoTemplate;
    private final PlatformConfig platformConfig;
    private final String cleanupSecret;

    public MigrationController(
            MongoTemplate mongoTemplate,
            PlatformConfig platformConfig,
            @Value("${mediastore.internal.cleanupSecret}") String cleanupSecret
    ) {
        this.mongoTemplate = mongoTemplate;
        this.platformConfig = platformConfig;
        this.cleanupSecret = cleanupSecret;
    }

    /**
     * POST /api/internal/migrate-payment-splits
     * <p>
     * Two-step migration:
     * 1. Removes all PLATFORM-role entries from paymentSplits in entries and orders.
     * 2. Normalizes remaining splits to 100% (e.g. SELLER 90 → 100) so they represent
     *    the share of the non-platform portion. At payment time, the platform fee is
     *    applied dynamically and these splits are scaled to (100 - platformFee)%.
     * <p>
     * Requires header: X-Cleanup-Secret matching the configured secret.
     */
    @PostMapping("/migrate-payment-splits")
    public ResponseEntity<?> migratePaymentSplits(
            @RequestHeader(value = "X-Cleanup-Secret", required = false) String secret
    ) {
        if (secret == null || !cleanupSecret.equals(secret)) {
            logger.warn("Migration: rejected — invalid or missing X-Cleanup-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        logger.info("Starting payment splits migration");
        Map<String, Object> result = new LinkedHashMap<>();

        // Step 1: Remove PLATFORM splits from entries
        Query platformEntryQuery = new Query(Criteria.where("paymentSplits.role").is("PLATFORM"));
        Update pullPlatform = new Update().pull("paymentSplits", new org.bson.Document("role", "PLATFORM"));
        UpdateResult entryPull = mongoTemplate.updateMulti(platformEntryQuery, pullPlatform, "entries");
        result.put("entries_platform_removed", entryPull.getModifiedCount());
        logger.info("Entries: removed PLATFORM splits from {} documents", entryPull.getModifiedCount());

        // Step 2: Remove PLATFORM splits from orders
        UpdateResult orderPull = mongoTemplate.updateMulti(platformEntryQuery, pullPlatform, "orders");
        result.put("orders_platform_removed", orderPull.getModifiedCount());
        logger.info("Orders: removed PLATFORM splits from {} documents", orderPull.getModifiedCount());

        // Step 3: Normalize remaining SELLER splits to 100%.
        // Entries that had a single SELLER at 90% (from old 10% platform fee) need to become 100%.
        // Uses MongoDB aggregation pipeline update to set each split's percent to
        // (split.percent / sum_of_all_percents) * 100, rounded to 2 decimal places.
        Query hasNonEmptySplits = new Query(Criteria.where("paymentSplits").not().size(0)
                .and("paymentSplits.0").exists(true));

        // For entries: set each remaining split's percent so they sum to 100
        long entriesNormalized = normalizeSplitsPercent("entries");
        result.put("entries_normalized", entriesNormalized);

        // For orders: same normalization
        long ordersNormalized = normalizeSplitsPercent("orders");
        result.put("orders_normalized", ordersNormalized);

        result.put("platform_wallet", platformConfig.getWallet());
        result.put("platform_fee_percent", platformConfig.getFeePercent().toPlainString());

        logger.info("Payment splits migration complete: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Normalizes paymentSplits percentages in the given collection so they sum to 100.
     * For documents with a single split (typical SELLER-only case), sets percent to 100.
     * For multi-split documents, scales proportionally.
     */
    private long normalizeSplitsPercent(String collection) {
        // Find documents that have splits with percent != 100 (single-split case)
        // or any splits at all (multi-split case that might need normalization)
        var docs = mongoTemplate.getCollection(collection)
                .find(new org.bson.Document("paymentSplits.0", new org.bson.Document("$exists", true)))
                .into(new java.util.ArrayList<>());

        long modified = 0;
        for (var doc : docs) {
            @SuppressWarnings("unchecked")
            var splits = (java.util.List<org.bson.Document>) doc.get("paymentSplits");
            if (splits == null || splits.isEmpty()) continue;

            // Calculate current total
            double currentTotal = splits.stream()
                    .mapToDouble(s -> s.get("percent") instanceof Number n ? n.doubleValue() : 0.0)
                    .sum();

            // Skip if already normalized (within tolerance)
            if (Math.abs(currentTotal - 100.0) < 0.01) continue;

            // Scale each split proportionally to sum to 100
            for (var split : splits) {
                if (split.get("percent") instanceof Number n) {
                    double scaled = (n.doubleValue() / currentTotal) * 100.0;
                    // Round to 2 decimal places
                    split.put("percent", Math.round(scaled * 100.0) / 100.0);
                }
            }

            mongoTemplate.getCollection(collection).updateOne(
                    new org.bson.Document("_id", doc.get("_id")),
                    new org.bson.Document("$set", new org.bson.Document("paymentSplits", splits))
            );
            modified++;
        }

        logger.info("Normalized splits in {}: {} documents", collection, modified);
        return modified;
    }
}
