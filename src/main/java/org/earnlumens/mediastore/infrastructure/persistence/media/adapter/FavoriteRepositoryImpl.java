package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Favorite;
import org.earnlumens.mediastore.domain.media.repository.FavoriteRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.FavoriteEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.FavoriteMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.FavoriteMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class FavoriteRepositoryImpl implements FavoriteRepository {

    private final FavoriteMongoRepository favoriteMongoRepository;
    private final FavoriteMapper favoriteMapper;

    public FavoriteRepositoryImpl(FavoriteMongoRepository favoriteMongoRepository,
                                  FavoriteMapper favoriteMapper) {
        this.favoriteMongoRepository = favoriteMongoRepository;
        this.favoriteMapper = favoriteMapper;
    }

    @Override
    public boolean existsByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId) {
        return favoriteMongoRepository.existsByTenantIdAndUserIdAndItemId(tenantId, userId, itemId);
    }

    @Override
    public Optional<Favorite> findByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId) {
        return favoriteMongoRepository.findByTenantIdAndUserIdAndItemId(tenantId, userId, itemId)
                .map(favoriteMapper::toModel);
    }

    @Override
    public Page<Favorite> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable) {
        return favoriteMongoRepository
                .findByTenantIdAndUserIdOrderByAddedAtDesc(tenantId, userId, pageable)
                .map(favoriteMapper::toModel);
    }

    @Override
    public Favorite save(Favorite favorite) {
        FavoriteEntity entity = favoriteMapper.toEntity(favorite);
        FavoriteEntity saved = favoriteMongoRepository.save(entity);
        return favoriteMapper.toModel(saved);
    }

    @Override
    public void deleteByTenantIdAndId(String tenantId, String id) {
        favoriteMongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .ifPresent(entity -> favoriteMongoRepository.deleteById(entity.getId()));
    }
}
