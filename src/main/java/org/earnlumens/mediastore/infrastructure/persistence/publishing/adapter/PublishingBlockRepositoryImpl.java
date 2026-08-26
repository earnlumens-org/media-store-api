package org.earnlumens.mediastore.infrastructure.persistence.publishing.adapter;

import org.earnlumens.mediastore.domain.publishing.model.PublishingBlock;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlockStatus;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingBlockRepository;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.entity.PublishingBlockEntity;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.mapper.PublishingBlockMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MongoTemplate-backed implementation. Every slot mutation is a single
 * findAndModify with the guard conditions inside the query, so concurrent
 * enqueues / FastPass confirmations / scheduler transitions are race-free.
 */
@Repository
public class PublishingBlockRepositoryImpl implements PublishingBlockRepository {

    private static final FindAndModifyOptions RETURN_NEW =
            FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongoTemplate;
    private final PublishingBlockMapper mapper;

    public PublishingBlockRepositoryImpl(MongoTemplate mongoTemplate, PublishingBlockMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    @Override
    public PublishingBlock save(PublishingBlock block) {
        PublishingBlockEntity saved = mongoTemplate.save(mapper.toEntity(block));
        return mapper.toModel(saved);
    }

    @Override
    public Optional<PublishingBlock> findByTenantIdAndId(String tenantId, String id) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(id));
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public List<PublishingBlock> findByTenantIdAndIdIn(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Query q = Query.query(Criteria.where("tenantId").is(tenantId).and("_id").in(ids));
        return mongoTemplate.find(q, PublishingBlockEntity.class).stream()
                .map(mapper::toModel).toList();
    }

    @Override
    public Optional<PublishingBlock> findEarliestOpenBlock(String tenantId, String spaceId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("spaceId").is(spaceId)
                        .and("status").is(PublishingBlockStatus.OPEN.name()))
                .with(Sort.by(Sort.Direction.ASC, "sequence"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingBlock> findEarliestOpenBlockWithFreeBaseSlot(String tenantId, String spaceId) {
        // baseSlotsUsed < baseCapacity expressed via $expr (field-to-field comparison).
        Criteria criteria = Criteria.where("tenantId").is(tenantId)
                .and("spaceId").is(spaceId)
                .and("status").is(PublishingBlockStatus.OPEN.name());
        Query q = Query.query(criteria)
                .addCriteria(Criteria.expr(
                        org.springframework.data.mongodb.core.aggregation.ComparisonOperators.Lt
                                .valueOf("baseSlotsUsed").lessThan("baseCapacity")))
                .with(Sort.by(Sort.Direction.ASC, "sequence"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingBlock> findLatestBlock(String tenantId, String spaceId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId).and("spaceId").is(spaceId))
                .with(Sort.by(Sort.Direction.DESC, "sequence"))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingBlock> tryReserveBaseSlot(String tenantId, String blockId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("_id").is(blockId)
                        .and("status").is(PublishingBlockStatus.OPEN.name()))
                .addCriteria(Criteria.expr(
                        org.springframework.data.mongodb.core.aggregation.ComparisonOperators.Lt
                                .valueOf("baseSlotsUsed").lessThan("baseCapacity")));
        Update u = new Update().inc("baseSlotsUsed", 1);
        return Optional.ofNullable(
                        mongoTemplate.findAndModify(q, u, RETURN_NEW, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public void releaseBaseSlot(String tenantId, String blockId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(blockId)
                .and("baseSlotsUsed").gt(0));
        mongoTemplate.findAndModify(q, new Update().inc("baseSlotsUsed", -1),
                RETURN_NEW, PublishingBlockEntity.class);
    }

    @Override
    public Optional<PublishingBlock> tryAddFastPassSlot(String tenantId, String blockId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(blockId)
                .and("status").is(PublishingBlockStatus.OPEN.name()));
        return Optional.ofNullable(
                        mongoTemplate.findAndModify(q, new Update().inc("fastPassSlots", 1),
                                RETURN_NEW, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public void releaseFastPassSlot(String tenantId, String blockId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(blockId)
                .and("fastPassSlots").gt(0));
        mongoTemplate.findAndModify(q, new Update().inc("fastPassSlots", -1),
                RETURN_NEW, PublishingBlockEntity.class);
    }

    @Override
    public Optional<PublishingBlock> tryTransitionStatus(String tenantId, String blockId,
                                                         PublishingBlockStatus from,
                                                         PublishingBlockStatus to) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(blockId)
                .and("status").is(from.name()));
        Update u = new Update().set("status", to.name());
        if (to == PublishingBlockStatus.PUBLISHED) {
            u.set("publishedAt", LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
        return Optional.ofNullable(
                        mongoTemplate.findAndModify(q, u, RETURN_NEW, PublishingBlockEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public List<PublishingBlock> findBlocksToLock(LocalDateTime now, int limit) {
        Query q = Query.query(Criteria.where("status").is(PublishingBlockStatus.OPEN.name())
                        .and("lockAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "lockAt"))
                .limit(limit);
        return mongoTemplate.find(q, PublishingBlockEntity.class).stream()
                .map(mapper::toModel).toList();
    }

    @Override
    public List<PublishingBlock> findBlocksToPublish(LocalDateTime now, int limit) {
        Query q = Query.query(Criteria.where("status").is(PublishingBlockStatus.LOCKED.name())
                        .and("publishAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "publishAt"))
                .limit(limit);
        return mongoTemplate.find(q, PublishingBlockEntity.class).stream()
                .map(mapper::toModel).toList();
    }
}
