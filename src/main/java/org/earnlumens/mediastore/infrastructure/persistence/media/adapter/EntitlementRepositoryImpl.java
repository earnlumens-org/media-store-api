package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntitlementMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntitlementMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

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
    public Page<Entitlement> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, EntitlementStatus status, Pageable pageable) {
        return entitlementMongoRepository
                .findByTenantIdAndUserIdAndStatusOrderByGrantedAtDesc(
                        tenantId, userId, status.name(), pageable)
                .map(entitlementMapper::toModel);
    }

    @Override
    public Entitlement save(Entitlement entitlement) {
        EntitlementEntity entity = entitlementMapper.toEntity(entitlement);
        EntitlementEntity saved = entitlementMongoRepository.save(entity);
        return entitlementMapper.toModel(saved);
    }
}
