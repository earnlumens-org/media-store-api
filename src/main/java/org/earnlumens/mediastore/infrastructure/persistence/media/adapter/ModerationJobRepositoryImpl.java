package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationJobEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ModerationJobMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationJobMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class ModerationJobRepositoryImpl implements ModerationJobRepository {

    private static final Logger logger = LoggerFactory.getLogger(ModerationJobRepositoryImpl.class);
    static final String COLLECTION = "moderation_jobs";

    private final ModerationJobMongoRepository mongoRepository;
    private final ModerationJobMapper mapper;
    private final MongoTemplate mongoTemplate;

    public ModerationJobRepositoryImpl(ModerationJobMongoRepository mongoRepository,
                                       ModerationJobMapper mapper,
                                       MongoTemplate mongoTemplate) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Inserts a new job, or replaces an existing one <b>in place</b>.
     *
     * <p>Legacy documents (written before 2026-05) carry a {@code String}
     * {@code _id}; current ones an {@code ObjectId}. Spring Data maps both to
     * the entity's {@code String id}, but {@code MongoRepository.save} would
     * re-encode a 24-hex String id as an {@code ObjectId} and upsert a
     * <b>second</b> document, leaving the legacy one untouched (the watchdog
     * then re-finds it on every sweep — MYCELIUM-RESILIENCE.md rev. 28).
     * Existing jobs are therefore replaced through the driver with a filter
     * that matches the stored {@code _id} in either representation, and the
     * replacement never carries an {@code _id}, so the stored one is kept
     * as-is. A job whose id matches nothing is <b>not</b> resurrected.
     */
    @Override
    public ModerationJob save(ModerationJob job) {
        var entity = mapper.toEntity(job);
        if (entity.getId() == null) {
            return mapper.toModel(mongoRepository.save(entity));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        Document replacement = new Document();
        mongoTemplate.getConverter().write(entity, replacement);
        replacement.remove("_id");
        UpdateResult result = collection().replaceOne(idFilter(entity.getId()), replacement);
        if (result.getMatchedCount() == 0) {
            logger.warn("moderation_jobs: save() matched no document for id={} — not inserting (job deleted?)",
                    entity.getId());
            return job;
        }
        return mapper.toModel(entity);
    }

    @Override
    public Optional<ModerationJob> findByTenantIdAndId(String tenantId, String id) {
        if (id == null) {
            return Optional.empty();
        }
        Document doc = collection().find(Filters.and(idFilter(id), Filters.eq("tenantId", tenantId))).first();
        return Optional.ofNullable(doc)
                .map(d -> mongoTemplate.getConverter().read(ModerationJobEntity.class, d))
                .map(mapper::toModel);
    }

    @Override
    public List<ModerationJob> findAllByStatus(ModerationJobStatus status, int limit) {
        return mongoRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<ModerationJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit) {
        return mongoRepository.findStaleJobs(heartbeatBefore, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public boolean claimCompletion(String tenantId, String jobId) {
        Bson filter = Filters.and(
                idFilter(jobId),
                Filters.eq("tenantId", tenantId),
                Filters.nin("status", ModerationJobStatus.COMPLETED.name(), ModerationJobStatus.DEAD.name()),
                Filters.or(
                        Filters.ne("status", ModerationJobStatus.FAILED.name()),
                        Filters.eq("completedAt", null)));
        Bson update = Updates.combine(
                Updates.set("status", ModerationJobStatus.COMPLETED.name()),
                Updates.set("updatedAt", toStoredDate(LocalDateTime.now())));
        return collection().updateOne(filter, update).getModifiedCount() == 1;
    }

    @Override
    public Optional<ModerationJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId) {
        return mongoRepository.findActiveByTenantIdAndEntryId(tenantId, entryId).map(mapper::toModel);
    }

    private MongoCollection<Document> collection() {
        return mongoTemplate.getCollection(COLLECTION);
    }

    /** Same LocalDateTime→Date encoding Spring Data applies to the entity's own fields. */
    private Date toStoredDate(LocalDateTime value) {
        return mongoTemplate.getConverter().getConversionService().convert(value, Date.class);
    }

    /**
     * Driver-level filter for a job id that matches the stored {@code _id}
     * whether it is an {@code ObjectId} or a legacy {@code String}. Must stay
     * on the raw driver: Spring's {@code QueryMapper} would re-encode every
     * 24-hex String under {@code _id} as an {@code ObjectId}.
     */
    static Bson idFilter(String id) {
        if (ObjectId.isValid(id)) {
            return Filters.in("_id", new ObjectId(id), id);
        }
        return Filters.eq("_id", id);
    }
}
