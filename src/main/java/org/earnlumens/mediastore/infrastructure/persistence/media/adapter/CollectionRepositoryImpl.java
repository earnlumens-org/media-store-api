package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.CollectionMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.CollectionMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CollectionRepositoryImpl implements CollectionRepository {

    private final CollectionMongoRepository collectionMongoRepository;
    private final CollectionMapper collectionMapper;

    public CollectionRepositoryImpl(CollectionMongoRepository collectionMongoRepository,
                                    CollectionMapper collectionMapper) {
        this.collectionMongoRepository = collectionMongoRepository;
        this.collectionMapper = collectionMapper;
    }

    @Override
    public Optional<Collection> findByTenantIdAndId(String tenantId, String id) {
        return collectionMongoRepository.findByTenantIdAndId(tenantId, id)
                .map(collectionMapper::toModel);
    }

    @Override
    public List<Collection> findByTenantIdAndIdIn(String tenantId, List<String> ids) {
        return collectionMongoRepository.findByTenantIdAndIdIn(tenantId, ids)
                .stream()
                .map(collectionMapper::toModel)
                .toList();
    }

    @Override
    public Page<Collection> findByTenantIdAndStatusAndVisibility(
            String tenantId, CollectionStatus status, MediaVisibility visibility, Pageable pageable) {
        return collectionMongoRepository.findByTenantIdAndStatusAndVisibilityOrderByPublishedAtDesc(
                tenantId, status.name(), visibility.name(), pageable)
                .map(collectionMapper::toModel);
    }

    @Override
    public Page<Collection> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable) {
        return collectionMongoRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(
                tenantId, userId, pageable)
                .map(collectionMapper::toModel);
    }

    @Override
    public Page<Collection> findByTenantIdAndAuthorUsernameAndStatusAndVisibility(
            String tenantId, String authorUsername, CollectionStatus status, MediaVisibility visibility, Pageable pageable) {
        // Case-insensitive lookup via the denormalized authorUsernameLower field,
        // which is index-backed (unlike the previous IgnoreCase regex query).
        return collectionMongoRepository.findByTenantIdAndAuthorUsernameLowerAndStatusAndVisibilityOrderByPublishedAtDesc(
                tenantId, authorUsername, status.name(), visibility.name(), pageable)
                .map(collectionMapper::toModel);
    }

    @Override
    public List<Collection> findByTenantIdAndStatusAndItemsEntryId(
            String tenantId, CollectionStatus status, String entryId) {
        return collectionMongoRepository.findByTenantIdAndStatusAndItems_EntryId(
                tenantId, status.name(), entryId)
                .stream()
                .map(collectionMapper::toModel)
                .toList();
    }

    @Override
    public Collection save(Collection collection) {
        CollectionEntity entity = collectionMapper.toEntity(collection);
        CollectionEntity saved = collectionMongoRepository.save(entity);
        return collectionMapper.toModel(saved);
    }

    @Override
    public void deleteByTenantIdAndId(String tenantId, String id) {
        collectionMongoRepository.deleteByTenantIdAndId(tenantId, id);
    }
}
