package org.earnlumens.mediastore.infrastructure.counter;

import com.mongodb.client.model.IndexOptions;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB-backed {@link DistributedCounter} (Phase 3, task 3.1 — Decision 2.4:
 * Mongo-first, Redis deferred behind adoption triggers R1–R6).
 *
 * <p>One document per {@code (scope, key, window)} in the
 * {@code rate_limit_counters} collection:
 * <pre>
 *   { _id: "auth:1.2.3.4:29671234", count: 7, expiresAt: ISODate(...) }
 * </pre>
 * Each call is a single atomic {@code findOneAndUpdate} with {@code $inc} and
 * upsert — the same proven pattern as
 * {@link org.earnlumens.mediastore.infrastructure.lock.DistributedLockService}.
 * A TTL index on {@code expiresAt} purges finished windows automatically
 * (Mongo's TTL monitor sweeps every ~60 s, which is why callers pass a
 * retention slightly longer than their window).
 *
 * <p>This collection is platform infrastructure keyed by client IP — it is
 * deliberately <em>not</em> tenant-scoped (rate limits protect the whole
 * deployment, and an attacker rotating tenants must not reset their budget).
 *
 * <p>All failures — including the index bootstrap — degrade to
 * {@link OptionalLong#empty()} / a warning log; the failure policy lives in
 * the callers, per the contract of {@link DistributedCounter}.
 */
@Service
public class MongoDistributedCounter implements DistributedCounter {

    private static final Logger logger = LoggerFactory.getLogger(MongoDistributedCounter.class);

    static final String COLLECTION = "rate_limit_counters";
    static final String TTL_INDEX_NAME = "ttl_expiresAt";

    private final MongoTemplate mongoTemplate;

    public MongoDistributedCounter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Creates the TTL purge index (idempotent; auto-index-creation is disabled
     * project-wide). Failure is logged but never blocks startup: counters keep
     * working without the index, documents just linger until it is created.
     */
    @PostConstruct
    void ensureTtlIndex() {
        try {
            mongoTemplate.getCollection(COLLECTION).createIndex(
                    new Document("expiresAt", 1),
                    new IndexOptions().name(TTL_INDEX_NAME).expireAfter(0L, TimeUnit.SECONDS));
        } catch (Exception e) {
            logger.warn("Could not ensure TTL index on {}: {}", COLLECTION, e.getMessage());
        }
    }

    @Override
    public OptionalLong incrementAndGet(String scope, String key, long windowBucket, Duration retention) {
        String id = counterId(scope, key, windowBucket);
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .inc("count", 1)
                .setOnInsert("expiresAt", Date.from(Instant.now().plus(retention)));
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);

        try {
            return extractCount(mongoTemplate.findAndModify(query, update, options, Document.class, COLLECTION));
        } catch (DuplicateKeyException e) {
            // Two instances raced the upsert of a fresh window; the document
            // now exists, so a single retry resolves to a plain $inc.
            try {
                return extractCount(mongoTemplate.findAndModify(query, update, options, Document.class, COLLECTION));
            } catch (Exception retryFailure) {
                logger.warn("Distributed counter '{}' retry failed: {}", id, retryFailure.getMessage());
                return OptionalLong.empty();
            }
        } catch (Exception e) {
            logger.warn("Distributed counter '{}' errored: {}", id, e.getMessage());
            return OptionalLong.empty();
        }
    }

    // ── Pure helpers (unit-tested without Mongo) ──────────────────

    /** Document id for a counter window: {@code "{scope}:{key}:{windowBucket}"}. */
    static String counterId(String scope, String key, long windowBucket) {
        return scope + ":" + key + ":" + windowBucket;
    }

    /** Extracts the post-increment count from the returned document. */
    static OptionalLong extractCount(Document doc) {
        if (doc == null) return OptionalLong.empty();
        Object count = doc.get("count");
        if (!(count instanceof Number n)) return OptionalLong.empty();
        return OptionalLong.of(n.longValue());
    }
}
