package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.FavoriteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FavoriteMongoRepository extends MongoRepository<FavoriteEntity, String> {

    boolean existsByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId);

    Optional<FavoriteEntity> findByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId);

    Page<FavoriteEntity> findByTenantIdAndUserIdOrderByAddedAtDesc(String tenantId, String userId, Pageable pageable);
}
