package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntitlementMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntitlementMongoRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class EntitlementRepositoryImpl implements EntitlementRepository {

    private final EntitlementMongoRepository entitlementMongoRepository;
    private final EntitlementMapper entitlementMapper;
    private final MongoTemplate mongoTemplate;

    public EntitlementRepositoryImpl(EntitlementMongoRepository entitlementMongoRepository,
                                     EntitlementMapper entitlementMapper,
                                     MongoTemplate mongoTemplate) {
        this.entitlementMongoRepository = entitlementMongoRepository;
        this.entitlementMapper = entitlementMapper;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status) {
        return entitlementMongoRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                tenantId, userId, entryId, status.name());
    }

    @Override
    public boolean existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
            String tenantId, String userId, TargetType targetType, String collectionId, EntitlementStatus status) {
        return entitlementMongoRepository.existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
                tenantId, userId, targetType.name(), collectionId, status.name());
    }

    @Override
    public Optional<Entitlement> findByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndEntryIdInAndStatus(tenantId, userId, List.of(entryId), status.name())
                .stream()
                .findFirst()
                .map(entitlementMapper::toModel);
    }

    @Override
    public List<Entitlement> findByTenantIdAndUserIdAndCollectionIdsAndStatus(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status) {
        if (collectionIds.isEmpty()) {
            return List.of();
        }
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndCollectionIdInAndStatus(
                        tenantId, userId, TargetType.COLLECTION.name(), collectionIds, status.name())
                .stream()
                .map(entitlementMapper::toModel)
                .toList();
    }

    @Override
    public Set<String> findEntitledEntryIds(
            String tenantId, String userId, List<String> entryIds, EntitlementStatus status) {
        if (entryIds.isEmpty()) {
            return Set.of();
        }
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndEntryIdInAndStatus(tenantId, userId, entryIds, status.name())
                .stream()
                .map(EntitlementEntity::getEntryId)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> findEntitledCollectionIds(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status) {
        if (collectionIds.isEmpty()) {
            return Set.of();
        }
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndCollectionIdInAndStatus(
                        tenantId, userId, TargetType.COLLECTION.name(), collectionIds, status.name())
                .stream()
                .map(EntitlementEntity::getCollectionId)
                .collect(Collectors.toSet());
    }

    @Override
    public Page<Entitlement> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, EntitlementStatus status, Pageable pageable) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndStatusOrderByGrantedAtDesc(
                        tenantId, userId, status.name(), pageable)
                .map(entitlementMapper::toModel);
    }

    @Override
    public Page<Entitlement> findByTenantIdAndUserIdAndTargetTypeAndStatus(
            String tenantId, String userId, TargetType targetType, EntitlementStatus status, Pageable pageable) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatusOrderByGrantedAtDesc(
                        tenantId, userId, targetType.name(), status.name(), pageable)
                .map(entitlementMapper::toModel);
    }

    @Override
    public Set<String> findAllEntitledEntryIds(String tenantId, String userId, EntitlementStatus status) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatus(tenantId, userId, TargetType.ENTRY.name(), status.name())
                .stream()
                .map(EntitlementEntity::getEntryId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> findAllEntitledCollectionIds(String tenantId, String userId, EntitlementStatus status) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatus(tenantId, userId, TargetType.COLLECTION.name(), status.name())
                .stream()
                .map(EntitlementEntity::getCollectionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    @Override
    public List<Entitlement> findAllCollectionEntitlements(String tenantId, String userId, EntitlementStatus status) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatus(tenantId, userId, TargetType.COLLECTION.name(), status.name())
                .stream()
                .filter(e -> e.getCollectionId() != null)
                .map(entitlementMapper::toModel)
                .toList();
    }

    @Override
    public List<Entitlement> findAllEntryEntitlements(String tenantId, String userId, EntitlementStatus status) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatus(tenantId, userId, TargetType.ENTRY.name(), status.name())
                .stream()
                .filter(e -> e.getEntryId() != null)
                .map(entitlementMapper::toModel)
                .toList();
    }

    @Override
    public boolean existsByTenantIdAndCollectionId(String tenantId, String collectionId) {
        return entitlementMongoRepository.existsByTenantIdAndCollectionId(tenantId, collectionId);
    }

    @Override
    public UpsertOutcome upsertPurchase(Entitlement entitlement) {
        TargetType targetType = entitlement.getTargetType() != null ? entitlement.getTargetType() : TargetType.ENTRY;
        Criteria key = Criteria.where("tenantId").is(entitlement.getTenantId())
                .and("userId").is(entitlement.getUserId())
                .and("targetType").is(targetType.name());
        if (targetType == TargetType.COLLECTION) {
            key = key.and("collectionId").is(entitlement.getCollectionId());
        } else {
            key = key.and("entryId").is(entitlement.getEntryId());
        }

        // 1. Re-purchase after a refund: reactivate the same document (H11).
        Query revoked = Query.query(key).addCriteria(
                Criteria.where("status").is(EntitlementStatus.REVOKED.name()));
        Update reactivate = new Update()
                .set("status", EntitlementStatus.ACTIVE.name())
                .set("orderId", entitlement.getOrderId())
                .set("grantedAt", entitlement.getGrantedAt())
                .set("franchiseId", entitlement.getFranchiseId())
                .unset("expiresAt");
        if (mongoTemplate.updateFirst(revoked, reactivate, EntitlementEntity.class).getModifiedCount() > 0) {
            return UpsertOutcome.REACTIVATED;
        }

        // 2. First purchase: insert only if absent; an existing ACTIVE document
        //    is left exactly as it is (R-30).
        EntitlementEntity entity = entitlementMapper.toEntity(entitlement);
        Update insertOnly = new Update()
                .setOnInsert("tenantId", entity.getTenantId())
                .setOnInsert("userId", entity.getUserId())
                .setOnInsert("targetType", targetType.name())
                .setOnInsert("entryId", entity.getEntryId())
                .setOnInsert("collectionId", entity.getCollectionId())
                .setOnInsert("franchiseId", entity.getFranchiseId())
                .setOnInsert("grantType", entity.getGrantType())
                .setOnInsert("orderId", entity.getOrderId())
                .setOnInsert("status", EntitlementStatus.ACTIVE.name())
                .setOnInsert("grantedAt", entity.getGrantedAt())
                .setOnInsert("_class", EntitlementEntity.class.getName());
        try {
            UpdateResult result = mongoTemplate.upsert(Query.query(key), insertOnly, EntitlementEntity.class);
            return result.getUpsertedId() != null ? UpsertOutcome.CREATED : UpsertOutcome.UNCHANGED;
        } catch (DuplicateKeyException concurrentInsert) {
            // Two upserts of the same right raced on the unique index: the other
            // one inserted it, so this right already exists exactly as intended.
            return UpsertOutcome.UNCHANGED;
        }
    }

    @Override
    public List<Entitlement> findActivePurchasesByEntryId(String tenantId, String entryId) {
        return entitlementMongoRepository
                .findByTenantIdAndTargetTypeAndEntryIdAndGrantTypeAndStatus(
                        tenantId, TargetType.ENTRY.name(), entryId,
                        GrantType.PURCHASE.name(), EntitlementStatus.ACTIVE.name())
                .stream()
                .map(entitlementMapper::toModel)
                .toList();
    }

    @Override
    public List<Entitlement> findActivePurchasesByCollectionIds(String tenantId, List<String> collectionIds) {
        if (collectionIds.isEmpty()) {
            return List.of();
        }
        return entitlementMongoRepository
                .findByTenantIdAndTargetTypeAndCollectionIdInAndGrantTypeAndStatus(
                        tenantId, TargetType.COLLECTION.name(), collectionIds,
                        GrantType.PURCHASE.name(), EntitlementStatus.ACTIVE.name())
                .stream()
                .map(entitlementMapper::toModel)
                .toList();
    }

    @Override
    public Set<String> findOrderIdsWithEntitlements(java.util.Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return Set.of();
        }
        return entitlementMongoRepository.findByOrderIdIn(orderIds)
                .stream()
                .map(EntitlementEntity::getOrderId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    @Override
    public Entitlement save(Entitlement entitlement) {
        EntitlementEntity entity = entitlementMapper.toEntity(entitlement);
        EntitlementEntity saved = entitlementMongoRepository.save(entity);
        return entitlementMapper.toModel(saved);
    }
}
