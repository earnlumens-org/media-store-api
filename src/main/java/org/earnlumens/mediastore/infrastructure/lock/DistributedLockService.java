package org.earnlumens.mediastore.infrastructure.lock;

import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Lightweight distributed lock backed by MongoDB, used to ensure scheduled
 * watchdogs/dispatchers run on a single instance when the API is scaled out.
 *
 * <p>Locks are lease-based: a lock is held until {@code lockedUntil} expires
 * and is never explicitly released, which keeps the protocol crash-safe (a
 * dead instance simply lets its lease lapse).
 */
@Service
public class DistributedLockService {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockService.class);
    private static final String COLLECTION = "scheduler_locks";

    private final MongoTemplate mongoTemplate;

    public DistributedLockService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Atomically tries to acquire the named lock for the given lease duration.
     *
     * @return true if this instance now holds the lock, false otherwise
     */
    public boolean tryAcquire(String name, Duration lease) {
        Date now = new Date();
        Date until = Date.from(Instant.now().plus(lease));

        Query query = new Query(Criteria.where("_id").is(name).and("lockedUntil").lt(now));
        Update update = new Update().set("lockedUntil", until);

        try {
            UpdateResult result = mongoTemplate.upsert(query, update, COLLECTION);
            return result.getModifiedCount() == 1 || result.getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            // Another instance holds an unexpired lease.
            return false;
        } catch (Exception e) {
            // Fail open: a Mongo hiccup should not stop maintenance work
            // entirely; duplicate execution is tolerated by job idempotency.
            logger.warn("Lock '{}' acquisition errored, proceeding without lock: {}", name, e.getMessage());
            return true;
        }
    }
}
