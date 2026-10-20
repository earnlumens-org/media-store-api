package org.earnlumens.mediastore.infrastructure.config;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup migration for the <b>core</b> indexes of {@code entries},
 * {@code collections}, {@code assets}, {@code orders}, {@code entitlements}
 * and {@code favorites} (MYCELIUM-RESILIENCE.md H9, DATA-MODEL.md §2–§7).
 * <p>
 * {@code spring.data.mongodb.auto-index-creation} is disabled, so the
 * {@code @CompoundIndex} annotations on the entities are documentation only.
 * Before this class existed none of these indexes was created by any code
 * (verified against Atlas on 2026-10-19: only the feed/search/fingerprint
 * indexes of the other migrations were present).
 * <p>
 * Definitions that differ from the historical annotations, on purpose:
 * <ul>
 *   <li>{@code orders}: the two {@code (tenantId, userId, targetType, entryId |
 *       collectionId)} indexes are <b>non-unique</b>. The retry flow
 *       ({@code processExistingOrders}) legitimately keeps several orders per
 *       target (FAILED → EXPIRED → new order) and tips are repeatable.
 *       Purchase idempotency is the CAS on {@code status}, not an index.</li>
 *   <li>{@code entitlements}: the unique indexes are <b>partial</b> on
 *       {@code targetType}. {@code entryId} is {@code null} on every
 *       COLLECTION entitlement (and vice versa), and a unique compound index
 *       treats a missing field as {@code null}, so a plain unique index would
 *       make a user's second collection purchase collide on
 *       {@code (T, U, null)}.</li>
 * </ul>
 * Unlike the other migrations this one <b>fails startup</b> if an index
 * cannot be created — in particular when the database already holds an
 * index with the same name (or the same keys) but a different definition.
 * Replacing such an index is a manual, conscious action; silently keeping the
 * wrong one would leave the uniqueness guarantees undefined.
 * {@code createIndex} is a no-op when an identical index already exists, so
 * the migration is idempotent.
 */
@Component
public class CoreIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CoreIndexMigration.class);

    /** MongoDB error codes for "index exists with a different definition". */
    private static final int INDEX_OPTIONS_CONFLICT = 85;
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    record IndexSpec(String collection, String name, Document keys, IndexOptions options) {
        static IndexSpec of(String collection, String name, Document keys) {
            return new IndexSpec(collection, name, keys, new IndexOptions().name(name));
        }

        static IndexSpec unique(String collection, String name, Document keys) {
            return new IndexSpec(collection, name, keys, new IndexOptions().name(name).unique(true));
        }

        static IndexSpec uniquePartial(String collection, String name, Document keys, Document filter) {
            return new IndexSpec(collection, name, keys,
                    new IndexOptions().name(name).unique(true).partialFilterExpression(filter));
        }
    }

    static List<IndexSpec> specs() {
        return List.of(
                // entries (EntryEntity) — feed/search/fingerprint indexes are owned by other migrations
                IndexSpec.unique("entries", "idx_tenant_id",
                        new Document("tenantId", 1).append("_id", 1)),
                IndexSpec.of("entries", "idx_tenant_user",
                        new Document("tenantId", 1).append("userId", 1)),
                IndexSpec.of("entries", "idx_tenant_status_published",
                        new Document("tenantId", 1).append("status", 1).append("publishedAt", -1)),
                IndexSpec.of("entries", "idx_tenant_status_type_published",
                        new Document("tenantId", 1).append("status", 1).append("type", 1).append("publishedAt", -1)),
                IndexSpec.of("entries", "idx_tenant_space_status_published",
                        new Document("tenantId", 1).append("spaceIds", 1).append("status", 1).append("publishedAt", -1)),

                // collections (CollectionEntity)
                IndexSpec.unique("collections", "idx_coll_tenant_id",
                        new Document("tenantId", 1).append("_id", 1)),
                IndexSpec.of("collections", "idx_coll_tenant_user",
                        new Document("tenantId", 1).append("userId", 1)),
                IndexSpec.of("collections", "idx_coll_tenant_status_published",
                        new Document("tenantId", 1).append("status", 1).append("publishedAt", -1)),
                IndexSpec.of("collections", "idx_coll_tenant_items_entry",
                        new Document("tenantId", 1).append("items.entryId", 1)),

                // assets (AssetEntity)
                IndexSpec.of("assets", "idx_tenant_entry_kind",
                        new Document("tenantId", 1).append("entryId", 1).append("kind", 1)),
                IndexSpec.unique("assets", "idx_tenant_r2key",
                        new Document("tenantId", 1).append("r2Key", 1)),

                // orders (OrderEntity) — NON-unique per target by design (retries, tips)
                IndexSpec.of("orders", "idx_order_tenant_user_target_entry",
                        new Document("tenantId", 1).append("userId", 1).append("targetType", 1).append("entryId", 1)),
                IndexSpec.of("orders", "idx_order_tenant_user_target_coll",
                        new Document("tenantId", 1).append("userId", 1).append("targetType", 1).append("collectionId", 1)),
                IndexSpec.of("orders", "idx_order_tenant_entry_status",
                        new Document("tenantId", 1).append("entryId", 1).append("status", 1)),
                IndexSpec.of("orders", "idx_order_tenant_coll_status",
                        new Document("tenantId", 1).append("collectionId", 1).append("status", 1)),
                IndexSpec.of("orders", "idx_order_tenant_user_status_created",
                        new Document("tenantId", 1).append("userId", 1).append("status", 1).append("createdAt", -1)),
                IndexSpec.of("orders", "idx_order_tenant_seller_status_created",
                        new Document("tenantId", 1).append("sellerId", 1).append("status", 1).append("createdAt", -1)),
                IndexSpec.of("orders", "idx_order_tenant_franchise_status_created",
                        new Document("tenantId", 1).append("franchiseId", 1).append("status", 1).append("createdAt", -1)),
                IndexSpec.of("orders", "idx_order_stellar_tx",
                        new Document("stellarTxHash", 1)),
                IndexSpec.of("orders", "idx_order_status_expires",
                        new Document("status", 1).append("expiresAt", 1)),

                // entitlements (EntitlementEntity) — one entitlement per user × target, enforced per targetType
                IndexSpec.uniquePartial("entitlements", "idx_ent_tenant_user_entry",
                        new Document("tenantId", 1).append("userId", 1).append("entryId", 1),
                        new Document("targetType", "ENTRY")),
                IndexSpec.uniquePartial("entitlements", "idx_ent_tenant_user_coll",
                        new Document("tenantId", 1).append("userId", 1).append("collectionId", 1),
                        new Document("targetType", "COLLECTION")),
                IndexSpec.of("entitlements", "idx_tenant_entry_status",
                        new Document("tenantId", 1).append("entryId", 1).append("status", 1)),
                IndexSpec.of("entitlements", "idx_ent_tenant_coll_status",
                        new Document("tenantId", 1).append("collectionId", 1).append("status", 1)),
                IndexSpec.of("entitlements", "idx_tenant_user_status_granted",
                        new Document("tenantId", 1).append("userId", 1).append("status", 1).append("grantedAt", -1)),

                // favorites (FavoriteEntity)
                IndexSpec.unique("favorites", "idx_tenant_user_item",
                        new Document("tenantId", 1).append("userId", 1).append("itemId", 1)),
                IndexSpec.of("favorites", "idx_tenant_user_added",
                        new Document("tenantId", 1).append("userId", 1).append("addedAt", -1))
        );
    }

    private final MongoTemplate mongoTemplate;

    public CoreIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<IndexSpec> specs = specs();
        for (IndexSpec spec : specs) {
            ensureIndex(spec);
        }
        logger.info("[CoreIndexMigration] Ensured {} core indexes", specs.size());
    }

    private void ensureIndex(IndexSpec spec) {
        try {
            mongoTemplate.getCollection(spec.collection()).createIndex(spec.keys(), spec.options());
            logger.info("[CoreIndexMigration] Ensured index {} on {}", spec.name(), spec.collection());
        } catch (MongoCommandException e) {
            String reason = (e.getErrorCode() == INDEX_OPTIONS_CONFLICT || e.getErrorCode() == INDEX_KEY_SPECS_CONFLICT)
                    ? "an index with the same name or keys but a different definition already exists"
                    : "the server rejected the index";
            throw new IllegalStateException(String.format(
                    "[CoreIndexMigration] Cannot create index %s on %s: %s (code %d: %s). "
                            + "Refusing to continue startup; resolve it manually and restart.",
                    spec.name(), spec.collection(), reason, e.getErrorCode(), e.getErrorMessage()), e);
        }
    }
}
