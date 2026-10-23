package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.result.UpdateResult;
import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.DisputeEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.DisputeMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.DisputeMongoRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DisputeRepositoryImpl implements DisputeRepository {

    private final DisputeMongoRepository mongoRepository;
    private final MongoTemplate mongoTemplate;
    private final DisputeMapper mapper;

    public DisputeRepositoryImpl(DisputeMongoRepository mongoRepository,
                                 MongoTemplate mongoTemplate,
                                 DisputeMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    /**
     * Atomic upsert with {@code $setOnInsert} only: an existing document (and
     * its administrator-set status) is never modified. Together with the
     * unique index {@code idx_dispute_case_entitlement} this makes the
     * trigger idempotent and race-safe (D-15, R-20).
     */
    @Override
    public boolean openIfAbsent(Dispute dispute) {
        DisputeEntity entity = mapper.toEntity(dispute);
        Query query = new Query(Criteria.where("tenantId").is(entity.getTenantId())
                .and("moderationCaseId").is(entity.getModerationCaseId())
                .and("entitlementId").is(entity.getEntitlementId()));
        Update update = new Update()
                .setOnInsert("tenantId", entity.getTenantId())
                .setOnInsert("moderationCaseId", entity.getModerationCaseId())
                .setOnInsert("entitlementId", entity.getEntitlementId())
                .setOnInsert("orderId", entity.getOrderId())
                .setOnInsert("userId", entity.getUserId())
                .setOnInsert("entryId", entity.getEntryId())
                .setOnInsert("affectedEntryIds", entity.getAffectedEntryIds())
                .setOnInsert("collectionId", entity.getCollectionId())
                .setOnInsert("reason", entity.getReason())
                .setOnInsert("status", entity.getStatus())
                .setOnInsert("createdAt", entity.getCreatedAt());
        UpdateResult result = mongoTemplate.upsert(query, update, DisputeEntity.class);
        return result.getUpsertedId() != null;
    }

    @Override
    public List<Dispute> findByTenantIdAndModerationCaseId(String tenantId, String moderationCaseId) {
        return mongoRepository.findByTenantIdAndModerationCaseId(tenantId, moderationCaseId)
                .stream().map(mapper::toModel).toList();
    }

    @Override
    public List<Dispute> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, DisputeStatus status) {
        return mongoRepository.findByTenantIdAndUserIdAndStatus(tenantId, userId, status.name())
                .stream().map(mapper::toModel).toList();
    }

    @Override
    public Optional<Dispute> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findByTenantIdAndId(tenantId, id).map(mapper::toModel);
    }
}
