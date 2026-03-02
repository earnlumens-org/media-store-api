package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.CollectionMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.CollectionMongoRepository;
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
    public Collection save(Collection collection) {
        CollectionEntity entity = collectionMapper.toEntity(collection);
        CollectionEntity saved = collectionMongoRepository.save(entity);
        return collectionMapper.toModel(saved);
    }
}
