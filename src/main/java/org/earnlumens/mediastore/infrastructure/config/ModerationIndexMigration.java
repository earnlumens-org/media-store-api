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
 * Startup migration for the indexes of {@code moderation_cases} and
 * {@code disputes} plus the pending-edit lookup on {@code entries}
 * (MYCELIUM-RESILIENCE.md H3 / N9, DATA-MODEL.md §7d / §7e).
 * <p>
 * Same contract as {@link CoreIndexMigration}: explicit names, idempotent
 * {@code createIndex}, and <b>startup fails</b> if the server already holds a
 * homonymous or same-key index with a different definition. The unique index
 * on {@code disputes} is the idempotency key of the dispute trigger (D-15):
 * without it two concurrent takedowns could open two disputes for the same
 * purchase, so its absence is not tolerated.
 */
@Component
public class ModerationIndexMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ModerationIndexMigration.class);

    private static final int INDEX_OPTIONS_CONFLICT = 85;
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    record IndexSpec(String collection, String name, Document keys, IndexOptions options) {
        static IndexSpec of(String collection, String name, Document keys) {
            return new IndexSpec(collection, name, keys, new IndexOptions().name(name));
        }

        static IndexSpec unique(String collection, String name, Document keys) {
            return new IndexSpec(collection, name, keys, new IndexOptions().name(name).unique(true));
        }

        static IndexSpec partial(String collection, String name, Document keys, Document filter) {
            return new IndexSpec(collection, name, keys,
                    new IndexOptions().name(name).partialFilterExpression(filter));
        }
    }

    static List<IndexSpec> specs() {
        return List.of(
                // moderation_cases (ModerationCaseEntity)
                IndexSpec.of("moderation_cases", "idx_case_tenant_created",
                        new Document("tenantId", 1).append("createdAt", -1)),
                IndexSpec.of("moderation_cases", "idx_case_tenant_entries",
                        new Document("tenantId", 1).append("entryIds", 1)),
                IndexSpec.of("moderation_cases", "idx_case_resolved_created",
                        new Document("resolvedAt", 1).append("createdAt", 1)),

                // disputes (DisputeEntity) — one dispute per (case, purchase)
                IndexSpec.unique("disputes", "idx_dispute_case_entitlement",
                        new Document("tenantId", 1).append("moderationCaseId", 1).append("entitlementId", 1)),
                IndexSpec.of("disputes", "idx_dispute_tenant_user_status",
                        new Document("tenantId", 1).append("userId", 1).append("status", 1)),
                IndexSpec.of("disputes", "idx_dispute_tenant_status_created",
                        new Document("tenantId", 1).append("status", 1).append("createdAt", -1)),

                // entries — pending-edit queue for human moderators (N9)
                IndexSpec.partial("entries", "idx_tenant_pending_submitted",
                        new Document("tenantId", 1).append("pendingVersion.submittedAt", 1),
                        new Document("pendingVersion", new Document("$exists", true)))
        );
    }

    private final MongoTemplate mongoTemplate;

    public ModerationIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<IndexSpec> specs = specs();
        for (IndexSpec spec : specs) {
            ensureIndex(spec);
        }
        logger.info("[ModerationIndexMigration] Ensured {} moderation indexes", specs.size());
    }

    private void ensureIndex(IndexSpec spec) {
        try {
            mongoTemplate.getCollection(spec.collection()).createIndex(spec.keys(), spec.options());
            logger.info("[ModerationIndexMigration] Ensured index {} on {}", spec.name(), spec.collection());
        } catch (MongoCommandException e) {
            String reason = (e.getErrorCode() == INDEX_OPTIONS_CONFLICT || e.getErrorCode() == INDEX_KEY_SPECS_CONFLICT)
                    ? "an index with the same name or keys but a different definition already exists"
                    : "the server rejected the index";
            throw new IllegalStateException(String.format(
                    "[ModerationIndexMigration] Cannot create index %s on %s: %s (code %d: %s). "
                            + "Refusing to continue startup; resolve it manually and restart.",
                    spec.name(), spec.collection(), reason, e.getErrorCode(), e.getErrorMessage()), e);
        }
    }
}
