package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationJobEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ModerationJobMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationJobMongoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ModerationJobRepositoryImpl implements ModerationJobRepository {

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

    @Override
    public ModerationJob save(ModerationJob job) {
        var entity = mapper.toEntity(job);
        var saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }

    @Override
    public Optional<ModerationJob> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
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
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(jobId),
                Criteria.where("tenantId").is(tenantId),
                Criteria.where("status").nin(ModerationJobStatus.COMPLETED.name(), ModerationJobStatus.DEAD.name()),
                new Criteria().orOperator(
                        Criteria.where("status").ne(ModerationJobStatus.FAILED.name()),
                        Criteria.where("completedAt").is(null))));
        Update update = new Update().set("status", ModerationJobStatus.COMPLETED.name());
        return mongoTemplate.updateFirst(query, update, ModerationJobEntity.class).getModifiedCount() == 1;
    }

    @Override
    public Optional<ModerationJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId) {
        return mongoRepository.findActiveByTenantIdAndEntryId(tenantId, entryId).map(mapper::toModel);
    }
}
