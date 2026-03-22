package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntitlementMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntitlementMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class EntitlementRepositoryImpl implements EntitlementRepository {

    private final EntitlementMongoRepository entitlementMongoRepository;
    private final EntitlementMapper entitlementMapper;

    public EntitlementRepositoryImpl(EntitlementMongoRepository entitlementMongoRepository,
                                     EntitlementMapper entitlementMapper) {
        this.entitlementMongoRepository = entitlementMongoRepository;
        this.entitlementMapper = entitlementMapper;
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
    public Entitlement save(Entitlement entitlement) {
        EntitlementEntity entity = entitlementMapper.toEntity(entitlement);
        EntitlementEntity saved = entitlementMongoRepository.save(entity);
        return entitlementMapper.toModel(saved);
    }
}
