package org.earnlumens.mediastore.infrastructure.persistence.publishing.adapter;

import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItem;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItemStatus;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingQueueItemRepository;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.entity.PublishingQueueItemEntity;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.mapper.PublishingQueueItemMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PublishingQueueItemRepositoryImpl implements PublishingQueueItemRepository {

    private static final FindAndModifyOptions RETURN_NEW =
            FindAndModifyOptions.options().returnNew(true);

    private static final List<String> ACTIVE_STATUSES = List.of(
            PublishingQueueItemStatus.QUEUED.name(),
            PublishingQueueItemStatus.LOCKED.name());

    private final MongoTemplate mongoTemplate;
    private final PublishingQueueItemMapper mapper;

    public PublishingQueueItemRepositoryImpl(MongoTemplate mongoTemplate,
                                             PublishingQueueItemMapper mapper) {
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    @Override
    public PublishingQueueItem save(PublishingQueueItem item) {
        PublishingQueueItemEntity saved = mongoTemplate.save(mapper.toEntity(item));
        return mapper.toModel(saved);
    }

    @Override
    public Optional<PublishingQueueItem> findByTenantIdAndId(String tenantId, String id) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(id));
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingQueueItem> findActiveByEntityAndSpace(String tenantId,
                                                                    PublishingEntityType entityType,
                                                                    String entityId,
                                                                    String spaceId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("entityType").is(entityType.name())
                .and("entityId").is(entityId)
                .and("spaceId").is(spaceId)
                .and("status").in(ACTIVE_STATUSES));
        return Optional.ofNullable(mongoTemplate.findOne(q, PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public List<PublishingQueueItem> findByEntity(String tenantId,
                                                  PublishingEntityType entityType,
                                                  String entityId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("entityType").is(entityType.name())
                        .and("entityId").is(entityId))
                .with(Sort.by(Sort.Direction.DESC, "enqueuedAt"));
        return mongoTemplate.find(q, PublishingQueueItemEntity.class).stream()
                .map(mapper::toModel).toList();
    }

    @Override
    public List<PublishingQueueItem> findByBlockIdAndStatus(String tenantId, String blockId,
                                                            PublishingQueueItemStatus status) {
        return findByBlockIdAndStatusIn(tenantId, blockId, List.of(status));
    }

    @Override
    public List<PublishingQueueItem> findByBlockIdAndStatusIn(String tenantId, String blockId,
                                                              List<PublishingQueueItemStatus> statuses) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("blockId").is(blockId)
                .and("status").in(statuses.stream().map(Enum::name).toList()));
        return mongoTemplate.find(q, PublishingQueueItemEntity.class).stream()
                .map(mapper::toModel).toList();
    }

    @Override
    public long countActiveBySpaceBeforeSequence(String tenantId, String spaceId, long sequence) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("spaceId").is(spaceId)
                .and("status").in(ACTIVE_STATUSES)
                .and("blockSequence").lt(sequence));
        return mongoTemplate.count(q, PublishingQueueItemEntity.class);
    }

    @Override
    public long countActiveBySpace(String tenantId, String spaceId) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("spaceId").is(spaceId)
                .and("status").in(ACTIVE_STATUSES));
        return mongoTemplate.count(q, PublishingQueueItemEntity.class);
    }

    @Override
    public Optional<PublishingQueueItem> tryCancel(String tenantId, String itemId, String userId,
                                                   LocalDateTime now) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(itemId)
                .and("userId").is(userId)
                .and("status").is(PublishingQueueItemStatus.QUEUED.name()));
        Update u = new Update()
                .set("status", PublishingQueueItemStatus.CANCELLED.name())
                .set("cancelledAt", now);
        // returnNew(false): the caller needs the PRE-cancel snapshot (fastPass flag, blockId).
        return Optional.ofNullable(mongoTemplate.findAndModify(
                        q, u, FindAndModifyOptions.options().returnNew(false),
                        PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingQueueItem> tryLock(String tenantId, String itemId, int position) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(itemId)
                .and("status").is(PublishingQueueItemStatus.QUEUED.name()));
        Update u = new Update()
                .set("status", PublishingQueueItemStatus.LOCKED.name())
                .set("lockedPosition", position);
        return Optional.ofNullable(mongoTemplate.findAndModify(q, u, RETURN_NEW,
                        PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingQueueItem> tryMarkPublished(String tenantId, String itemId,
                                                          LocalDateTime publishedAt) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(itemId)
                .and("status").is(PublishingQueueItemStatus.LOCKED.name()));
        Update u = new Update()
                .set("status", PublishingQueueItemStatus.PUBLISHED.name())
                .set("publishedAt", publishedAt);
        return Optional.ofNullable(mongoTemplate.findAndModify(q, u, RETURN_NEW,
                        PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingQueueItem> tryMarkDiscarded(String tenantId, String itemId,
                                                          LocalDateTime now) {
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(itemId)
                .and("status").is(PublishingQueueItemStatus.LOCKED.name()));
        Update u = new Update()
                .set("status", PublishingQueueItemStatus.CANCELLED.name())
                .set("cancelledAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(q, u, RETURN_NEW,
                        PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }

    @Override
    public Optional<PublishingQueueItem> tryApplyFeePayment(String tenantId, String itemId,
                                                            String orderId, BigDecimal amountXlm,
                                                            LocalDateTime now) {
        // The `appliedOrderIds ne orderId` guard inside the atomic query makes
        // this idempotent: a second confirmation of the same order matches
        // nothing and returns empty.
        Query q = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("_id").is(itemId)
                .and("appliedOrderIds").ne(orderId));
        Update u = new Update()
                .push("appliedOrderIds", orderId)
                .inc("priorityFeeXlm", amountXlm)
                .set("feeLastIncreasedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(q, u, RETURN_NEW,
                        PublishingQueueItemEntity.class))
                .map(mapper::toModel);
    }
}
